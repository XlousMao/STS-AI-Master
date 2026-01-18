# 阶段 1 总结：数据结构化与感知打通

> 时间：2026-01-18  
> 范围：基于 Protobuf 的结构化数据抓取 + 运行时依赖问题攻关

---

## 一、核心成果

- 在 Java Mod 侧完成了**结构化游戏状态采样**：
  - 使用独立的 `sts_state.proto` 定义 `PlayerState`、`MonsterState` 与聚合的 `GameState`。
  - 在 `AbstractDungeon.update` 的 Patch 中，以 3 秒为周期采集当前局内状态。
  - 通过 `System.out.println("[STS-AI-PROTO] " + gameState.toString())` 输出结构化对象，为后续跨进程传输与训练数据录制打下基础。
- 数据覆盖范围：
  - Player：HP, Max HP, Gold, Energy, Block, Floor。
  - Monsters：ID, Name, HP, Max HP, Intent, Block。
- 技术链路：
  - 使用 `protobuf-maven-plugin` 在 `mvn compile` 阶段自动生成 Java 协议代码。
  - 使用 `maven-shade-plugin` 将 `protobuf-java` 打入 Mod，并通过 Relocation 技术规避游戏环境的类加载冲突。

---

## 二、数据结构设计与采样策略

### 1. 协议文件与数据模型

- 新增 `protobuf/sts_state.proto`，定义：
  - `PlayerState`：`hp`, `max_hp`, `gold`, `energy`, `block`, `floor`。
  - `MonsterState`：`id`, `name`, `hp`, `max_hp`, `intent`, `block`。
  - `GameState`：聚合 `PlayerState player` 与 `repeated MonsterState monsters`。
- 生成的 Java 类位于 `sts.ai.state.v1` 包下，通过 `GameState.newBuilder()` / `PlayerState.newBuilder()` 等 Builder API 构造对象。

### 2. 运行时采样逻辑

- Patch 位置：`AbstractDungeon.update` 的 Postfix。
- 采样条件：
  - 仅在 `AbstractDungeon.player != null` 时执行，避免非战斗场景空指针。
  - 通过静态变量 `lastLogTime` + 固定窗口 `LOG_INTERVAL_MS = 3000L` 实现简单节流。
- 采样内容：
  - Player：
    - `hp` ← `AbstractDungeon.player.currentHealth`
    - `max_hp` ← `AbstractDungeon.player.maxHealth`
    - `gold` ← `AbstractDungeon.player.gold`
    - `energy` ← `AbstractDungeon.player.energy.energy`
    - `block` ← `AbstractDungeon.player.currentBlock`
    - `floor` ← `AbstractDungeon.floorNum`
  - Monsters：
    - 通过 `AbstractDungeon.getMonsters().monsters` 遍历当前房间怪物列表。
    - `id` ← `m.id`（内部 ID，便于跨版本对齐）
    - `name` ← `m.name`（展示名）
    - `hp` / `max_hp` ← `m.currentHealth` / `m.maxHealth`
    - `intent` ← `m.intent != null ? m.intent.name() : ""`
    - `block` ← `m.currentBlock`
- 输出形式：
  - 每 3 秒在控制台打印一行：
    - `"[STS-AI-PROTO] " + gameState.toString()`
  - Python / Qt 端后续只需根据前缀 `[STS-AI-PROTO]` 过滤日志，即可解析结构化状态。

---

## 三、核心技术挑战：运行时 NoClassDefFoundError & 依赖重定位

### 1. 问题现象：战斗加载阶段闪退

- 在接入 Protobuf 并开始构造 `GameState` 之后，游戏启动流程如下：
  - 使用 ModTheSpire 加载 `STS AI Bridge Mod`。
  - 进入一场战斗，Patch 的状态采样逻辑第一次执行。
  - 随即发生异常并导致闪退，控制台报错：
    - `java.lang.NoClassDefFoundError: com/google/protobuf/MessageOrBuilder`
- 特征：
  - Maven 编译阶段完全通过，IDE 中也没有找不到类的错误。
  - 仅在**运行时**、且只在真正执行到涉及 Protobuf 的代码路径时触发。

### 2. 根因定位：编译通过但运行失败的原因

- 现象分析：
  - 编译阶段能够成功生成并编译 Java 协议类，说明：
    - `protobuf-java` 通过 Maven 依赖添加到了**编译 classpath**。
    - 生成的 `GameState` / `PlayerState` 等类在 IDE 和 Maven 中都是可见的。
  - 运行时却报 `NoClassDefFoundError`，说明：
    - 游戏真实加载的 Mod jar 中**没有**包含 `com.google.protobuf.*` 相关类。
    - 或者存在多个版本的 Protobuf，由于类加载顺序 / 路径问题导致冲突。
- 核心根因：
  1. **依赖未打包进 Mod**：
     - 默认的 Mod 构建只打进自身 class 和资源。
     - `protobuf-java` 虽然出现在 Maven 依赖中，但最终生成的 `bridge-mod-1.0.0.jar` 只包含 Bridge Mod 自己的代码，游戏运行时的 classpath 中并没有自动包含 `protobuf-java.jar`。
  2. **游戏环境可能存在其他 Protobuf 版本或完全没有 Protobuf**：
     - 即使手动在游戏目录放入某个版本的 Protobuf，也会引入版本不一致问题。
     - 不同 Mod 若各自引入 Protobuf，很容易出现 “某个 Mod 期望的版本与全局加载的版本不一致” 的潜在冲突。
- 总结：
  - 编译时：以 Maven 工程为边界，依赖完整可见 → 编译通过。
  - 运行时：以游戏进程 + Mod jar 为边界，依赖不在 classpath 或版本冲突 → `NoClassDefFoundError`。

### 3. 解决方案：Shade + Relocation，构建“自带运行时”的独立 Mod

#### 3.1 使用 maven-shade-plugin 打包依赖

- 在 `sts-bridge-mod/pom.xml` 中新增 `maven-shade-plugin`：
  - 在 `package` 阶段执行 `shade` 目标。
  - 通过 `artifactSet.includes` 显式指定只将 `com.google.protobuf:protobuf-java` 打入 Mod。
  - 关闭 `dependency-reduced-pom`，避免对上层依赖管理造成干扰。
- Shade 的作用：
  - 将 `protobuf-java` 的 class 文件直接合并到 Mod 的最终 jar 中。
  - 确保游戏只需加载一个 jar，即可同时获得：
    - Bridge Mod 逻辑；
    - 对应版本的 Protobuf 实现。
  - 构建结果：
    - Shade 插件先生成 `bridge-mod-1.0.0-shaded.jar`，再替换为主产物。
    - 通过 antrun 插件将最终的 `bridge-mod-1.0.0.jar` 复制到游戏 `mods/` 目录，文件名保持与 ModTheSpire 兼容。

#### 3.2 使用 Relocation（重定位）避免 Protobuf 版本冲突

- 问题背景：
  - 若多个 Mod 都使用 `com.google.protobuf` 命名空间中的类，且各自绑定的版本不同，则：
    - 只要游戏进程中实际加载的是某一个版本的 protobuf 类，其他期望不同版本的 Mod 就有可能在运行时出现兼容性问题。
  - 即使当前只存在一个使用 Protobuf 的 Mod，为了后续生态可扩展性，也应避免“污染”全局命名空间。
- 具体做法：
  - 在 Shade 插件中配置 Relocation：
    - 将所有 `com.google.protobuf` 引用重写为：
      - `sts.ai.bridge.repackaged.protobuf`
  - 构建完成后，Mod 内部的 Protobuf 实现类和调用点全部移动到独立命名空间。
- 效果：
  - Bridge Mod 完全自带所需的 Protobuf 实现，不依赖游戏或其他 Mod 是否引入 protobuf。
  - 即使未来有其他 Mod 使用不同版本的 Protobuf（仍在 `com.google.protobuf` 下），也不会和本 Mod 的运行时实现发生类加载冲突。
  - 解决了：
    - “游戏环境缺少依赖库” → 通过 Shade 内联依赖。
    - “潜在类加载冲突” → 通过 Relocation 隔离命名空间。

---

## 四、验证方式

- 构建验证：
  - 在 `sts-bridge-mod` 目录执行：
    - `mvn clean compile`：验证 Protobuf 代码生成与编译链路无误。
    - `mvn clean package`：验证 Shade 插件、Relocation 配置与 antrun 拷贝逻辑全部工作正常。
  - 关键日志：
    - `Including com.google.protobuf:protobuf-java:jar:3.19.1 in the shaded jar.`
    - `Replacing ... bridge-mod-1.0.0.jar with ... bridge-mod-1.0.0-shaded.jar`
    - `[copy] Copying 1 file to ...\SlayTheSpire\mods`
- 运行时验证：
  - 使用 ModTheSpire 启动游戏并加载 `STS AI Bridge Mod`。
  - 进入战斗场景，观察控制台输出：
    - 不再出现 `NoClassDefFoundError: com/google/protobuf/MessageOrBuilder`。
    - 每隔约 3 秒看到一行以 `[STS-AI-PROTO]` 为前缀的日志，内容为 `GameState.toString()` 的结构化状态。
  - 通过对比多场战斗中的日志，确认：
    - Player 与 Monsters 的血量、意图等字段随战斗进程实时变化；
    - 采样频率与节流策略按预期生效，不会刷屏。

---

## 五、阶段 1 小结与展望

- 阶段 1 已完成的关键里程碑：
  - 建立了稳定的 Java Mod 采样通路，支持从游戏内部定期抓取结构化状态。
  - 完成了 Protobuf 协议到 Java 实现的自动化生成与接入。
  - 通过 Shade + Relocation 技术，将 Protobuf 依赖封装到 Mod 内部，解决了游戏环境缺少依赖与潜在类加载冲突问题。
  - 使用控制台前缀 `[STS-AI-PROTO]` 提供简单、稳定的外部观测接口，为后续 Python/Qt 侧的日志消费与管道对接铺平道路。
- 下一步（阶段 2）的自然演进方向：
  - 在 Python 侧实现与 `GameState` 对应的解析逻辑，构建 Gymnasium 环境包装。
  - 将当前的日志流或管道改造为稳定的跨进程通信通道（如命名管道、Socket、或通过现有 `ProtocolMessage` 顶层协议进行统一管理）。
  - 基于结构化状态与合法动作空间，逐步接入 PPO 等强化学习算法，实现从“感知打通”到“决策闭环”的升级。

---

## Appendix: Future Improvements

- **完善协议字段**
  - 在现有 `PlayerState` / `MonsterState` 的基础上，后续需要补充更多环境信息：
    - Relics（遗物）：包括已获得遗物列表及其效果，用于更精确地建模长期被动加成。
    - Potions（药水）：当前持有的药水类型与数量，影响可用策略空间。
    - Keys（钥匙）：在爬塔过程中已收集的钥匙状态，用于支持心脏路线等高级策略。
    - Powers（Buff/Debuff）：玩家与怪物身上的持续效果（Strength、Vulnerable、Frail 等），这是建模战局节奏与爆发能力的关键维度。
- **采样效率与触发机制优化**
  - 当前实现采用固定 3 秒轮询方案，优点是简单可靠，但存在：
    - 在静止状态下产生冗余采样；
    - 在高频操作场景下对关键时刻不够敏感等问题。
  - 未来计划逐步引入事件驱动的采样与推送机制：
    - 例如基于 `CardUse`, `TurnStart`, `TurnEnd`, `OnVictory` 等游戏事件，在**状态变化的关键点**主动构造并发送 `GameState`。
    - 将“固定时间片采样”与“事件触发采样”结合，既保证时序连续性，又降低无效数据量，为后续多进程采样与训练效率优化预留空间。

---

## 六、技术难点一：私有变量访问限制与反射方案

### 1. 问题背景：关键 UI 状态被封装在私有字段中

- 在构建“可训练”的环境时，我们需要从以下界面读取**精确的决策上下文**：
  - 商店界面（`ShopScreen`）：可购买卡牌/遗物/药水的列表、价格、已购买状态。
  - 营火界面（`CampfireUI`）：当前可用的营火选项（休息、升级、移除卡牌等）以及对应节点。
- 实际排查发现：
  - 大部分关键字段（如 `coloredCards`、`relics`、`campfireButtons` 等）均为 `private`，且未对外暴露安全的 getter。
  - Slay the Spire 的 Mod 生态通常通过 AccessTransformers 修改访问级别，但这会：
    - 引入构建和加载链路上的额外复杂度；
    - 增加与其他 Mod 的兼容风险；
    - 破坏“协议优先、逻辑解耦”的整体设计风格。

### 2. 方案选择：使用受控的 Java 反射层

- 最终决策是不引入 AccessTransformers，而是在 Bridge Mod 内部实现一层**受控反射访问层**：
  - 对外暴露的是“结构化的观察结果”（例如：商品列表、价格、是否已购买），而不是 UI 类本身。
  - 对内通过 `java.lang.reflect.Field` 读取 `ShopScreen` / `CampfireUI` 的私有字段。
- 设计要点：
  - 封装统一的工具方法（伪代码）：
    - `getPrivateField(instance, "fieldName", FieldType.class)`。
  - 支持在当前类和直接父类中查找字段，兼容少量版本差异。
  - 所有反射调用都包裹在 `try/catch` 中，失败时返回 `null` 或空集合，而不是让异常向上传递到游戏主循环。

### 3. 商店数据抽取：从 UI 到协议字段

- 对商店界面，我们重点关心：
  - 卡牌商品：卡牌 ID、稀有度、价格、是否已售出。
  - 遗物商品：遗物 ID、价格、是否已售出。
  - 药水商品：药水 ID、价格、是否已售出。
- 实现路径：
  - 使用反射获取 `ShopScreen` 中的私有列表字段（如卡牌/遗物/药水的存储集合）。
  - 对每个元素：
    - 读取其基础描述（ID、名称等），并映射到 Protobuf 协议中的对应字段；
    - 尝试读取价格字段（通常也是私有或受保护属性），并写入 `price`；
    - 对已购买商品，根据 UI 状态标记为不可再选。
- 这样一来，训练端看到的是**一个纯粹的、与 UI 框架解耦的“商店商品列表”**，既保留了细节，又避免了对 UI 实现的硬依赖。

### 4. 营火数据抽取：序列化可用动作

- 对营火界面，我们关心的是“当前有哪些可用的营火动作”以及“它们对应的逻辑含义”：
  - 例如：休息（回复生命）、升级卡牌、移除卡牌、特殊事件选项等。
- 实现路径：
  - 使用反射获取 `CampfireUI` 内部的按钮列表（通常是若干 `AbstractCampfireOption` 子类）。
  - 对每个选项，读取：
    - 当前是否可用；
    - 描述文本或类型标识；
    - 若能映射到标准动作空间（如 REST、SMITH），则转成统一的枚举/离散 ID。
- 最终，营火在协议侧被表示为“一组可用离散动作”，与战斗动作空间保持抽象上的一致性。

### 5. 安全性与可维护性权衡

- 安全性控制：
  - 所有反射访问均采用“失败即降级”的策略：如果某个字段在新版本中消失或改名，不会导致游戏崩溃，只会在观察中缺失该部分信息。
  - 对于关键逻辑，始终以“是否可读到数据”作为前置条件，防止在空引用上继续构造协议对象。
- 可维护性：
  - 将所有反射相关逻辑集中在 Bridge Mod 内部的少数几个方法中，未来若字段名发生变化，只需在这一处修改。
  - 通过在文档中明确记录“哪些观察字段依赖反射”，为后续版本升级留出检查清单。

---

## 七、技术难点二：地图节点注入与安全跳转

### 1. 问题背景：从“点地图”到“控制逻辑”

- 强化学习环境必须能决定“下一层走哪条路”，对应玩家在地图界面上点击某个节点。
- 如果从 UI 层模拟点击：
  - 需要模拟鼠标坐标和点击事件，容易受分辨率/界面缩放影响；
  - 很难保证与逻辑层完全同步，也不利于在 headless 或多实例环境中运行。
- 因此更理想的方案是：**直接在逻辑层选择地图节点并触发过渡**，而不是伪造 UI 输入。

### 2. 方案设计：基于 `AbstractDungeon` 的节点定位与路径维护

- 环境侧定义了离散动作 `CHOOSE_MAP_NODE(x, y)`，其中 `(x, y)` 对应地图上的列号与层数。
- 在 Java 侧执行该动作时：
  - 遍历 `AbstractDungeon.map` 中的 `MapRoomNode`；
  - 找到 `node.x == x && node.y == y` 的节点；
  - 将其设置为 `AbstractDungeon.nextRoom`；
  - 在 `AbstractDungeon.pathX` / `AbstractDungeon.pathY` 中追加对应坐标，用于维护爬塔路径。
- 之后调用 `AbstractDungeon.nextRoomTransitionStart()`，让游戏以原生流程完成房间切换，包括：
  - 播放过场动画；
  - 初始化下一房间（战斗、问号、商店、营火等）。

### 3. 界面关闭与状态一致性

- 在地图跳转完成后，如果地图界面仍处于打开状态，可能会干扰后续输入：
  - 玩家视角下会卡在地图界面；
  - 环境侧的其他动作（如进入战斗后的出牌）也无法继续。
- 为此，在设置完 `nextRoom` 之后，我们会：
  - 检查 `AbstractDungeon.dungeonMapScreen` 是否存在；
  - 将其 `dismissable` 标记为 `true`；
  - 调用 `AbstractDungeon.closeCurrentScreen()`，收起地图界面。
- 这样既遵循了游戏原生的界面关闭逻辑，又避免了直接操作 UI 内部状态带来的不确定性。

### 4. 错误处理与非法动作防御

- 为避免 agent 选择“不可达”的节点导致异常，我们在 Java 侧增加了多层防御：
  - 如果在地图中找不到对应 `(x, y)` 的 `MapRoomNode`，则拒绝执行本次动作；
  - 如果房间状态不允许切换（例如当前事件还未结束），则忽略本次跳转请求；
  - 所有异常都在 Bridge Mod 内部被捕获并记录，不会让游戏主循环崩溃。
- 对于训练端而言，这些非法动作会被静默丢弃或转化为“无效动作”，从而将“环境安全性”与“策略优劣”解耦开来。

---

## 八、当前稳定基准版本能力快照

### 1. 观测空间（Observation）

- 玩家状态：
  - 生命值、最大生命、金币、能量、格挡、当前楼层。
  - 当前手牌、抽牌堆、弃牌堆、消耗堆的卡牌 ID 列表（已规范化为稳定 ID）。
- 怪物状态：
  - 每个怪物的 ID、名称、生命值、最大生命、意图、格挡。
  - 怪物身上的关键 Buff/Debuff（用于支持节奏建模）。
- 地图与全局信息：
  - 当前层数、地图结构摘要、已选路径（`pathX` / `pathY`）。
  - 当前房间类型（战斗、营火、商店、事件等）。
- UI 相关扩展观测：
  - 商店商品列表（卡牌/遗物/药水及其价格与可用性）。
  - 营火可用选项列表（可映射为标准离散动作）。

### 2. 动作空间（Action）

- 战斗内动作：
  - 出牌（包括是否指定目标）、使用药水、防御性动作等。
  - 通过能量检测和“可出牌性”检查过滤明显非法动作。
- 地图动作：
  - `CHOOSE_MAP_NODE(x, y)`：选择下一层的地图节点。
- 营火动作：
  - REST、SMITH 等标准营火操作，统一映射到离散动作 ID。
- 商店动作：
  - 按商品索引或 ID 选择购买对象，并通过金币与库存检查保证安全性。

### 3. 环境控制能力

- Episode 管理：
  - 支持通过专用指令触发 `RESET`，调用 `CardCrawlGame.startOver` 或等价逻辑，开始新的爬塔。
  - 在战斗结束、角色死亡或通关时，能够正确报告终止状态和奖励（包括胜负结果与关键信息）。
- 运行模式：
  - 支持 headless 启动与多实例并行，通过可配置端口实现并发训练。
  - 通过统一的日志前缀与协议消息，实现跨语言的稳定通信。

### 4. 面向 Gymnasium 封装的准备度

- 从当前阶段的视角来看，我们已经具备：
  - 足够丰富且结构化的观测空间，覆盖了战斗、地图、商店、营火等核心决策点；
  - 与之配套的离散动作空间及非法动作防御机制；
  - 可复用的 Episode 控制与重置机制；
  - 通过“协议优先 + 反射补洞”的方式，在不侵入游戏主循环的前提下打通了关键数据通路。
- 下一步将围绕这些能力，在 Python 侧实现 Gymnasium 环境封装，实现从“稳定环境”到“可训练接口”的最后一跳。
