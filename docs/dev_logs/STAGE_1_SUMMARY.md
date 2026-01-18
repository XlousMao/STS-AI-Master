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

