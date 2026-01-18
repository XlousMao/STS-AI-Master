# STS-AI Bridge 开发日志（第0阶段性收官）

> 时间：2026-01-18  
> 范围：环境搭建 + Mod 注入 + 实时数据监控

---

## 一、Maven 环境变量问题（mvn 命令无法识别）

### 现象
- 在项目根目录执行 `mvn -f sts-bridge-mod/pom.xml clean package` 时，终端提示：
  - “mvn：无法将 ‘mvn’ 项识别为 cmdlet、函数、脚本文件或可运行程序的名称”
- 表明当前 PowerShell 环境中找不到 Maven 可执行文件。

### 排查过程
- 使用 `where mvn` 检查系统 PATH，确认未返回任何路径。
- 检查本机 JDK 已安装，但 Maven 未单独配置。

### 解决方案
- 安装 Maven（或确认已有 Maven 安装路径），将 `MAVEN_HOME/bin` 添加到系统环境变量 `PATH`。
- 重新打开终端，在项目根目录执行：
  - `mvn -version` 验证安装是否成功。
  - `mvn -f sts-bridge-mod/pom.xml clean package` 完成首次构建。

### 验证结果
- 构建日志成功进入 `clean`、`resources`、`compiler` 阶段，表明 Maven 环境已配置完成。

---

## 二、依赖加载异常：NullPointerException & 日志初始化问题

### 现象
- 使用 ModTheSpire 启动游戏时，控制台输出：
  - “Initializing mods...” 之后，在加载 `bridge-mod` 阶段出现 `java.lang.NullPointerException`。
  - 堆栈顶端位于 `com.evacipated.cardcrawl.modthespire.Patcher.initializeMods`。
- 初始实现中在类静态字段上直接创建 Log4j `Logger`，并在 `initialize()` 以及 Patch 中使用。

### 初步判断
- 在 Mod 加载早期阶段，日志系统或相关类加载顺序可能尚未稳定，导致静态字段初始化时访问到未就绪的依赖，从而触发 NPE。
- 同时 Patch 逻辑也强依赖 `logger`，一旦初始化失败会连带影响。

### 解决方案
- 将 `Logger` 初始化从静态字段移动到 `initialize()` 内部，改为延迟初始化：
  - 类字段：`private static Logger logger;`
  - 在 `initialize()` 中执行 `logger = LogManager.getLogger(StsAIBridge.class);`
- 所有日志输出在使用前先判空：
  - 若 `logger != null`：使用 `logger.info(...)`；
  - 否则退化为 `System.out.println(...)`。
- 在 Patch 中同样采用同样的判空与降级策略，确保不会因 `logger` 为空导致新的 NPE。

### 验证结果
- 重新构建并启动后，Mod 加载阶段不再出现与日志相关的 NullPointerException。
- 控制台在初始化阶段会稳定打印：
  - `[STS-AI] StsAIBridge.initialize() entered.`
  - `[STS-AI] Bridge Project Initialized! Ready for Training.`

---

## 三、MTS 配置文件缺失：版本号警告与加载失败

### 现象
- 启动 ModTheSpire 时出现弹窗：
  - “bridge-mod-1.0.0-SNAPSHOT has a missing or bad version number. Go yell at the author to fix it.”
- 控制台中 Mod 列表能识别 `bridge-mod-1.0.0-SNAPSHOT`，但版本信息被判定为不合法。

### 根因分析
- 初始状态下仅通过 Maven `pom.xml` 设置 `<version>1.0.0-SNAPSHOT</version>`，生成的 jar 名包含 `-SNAPSHOT`。
- 未在 jar 根目录提供 `ModTheSpire.json`，导致 MTS 只能从文件名 / 旧格式元数据推断版本号，最终判定为 “missing or bad version number”。

### 解决方案
1. 规范化 Maven 版本：
   - 将 `sts-bridge-mod/pom.xml` 中的版本号改为稳定版本：
     - `<version>1.0.0</version>`
   - 通过 `maven-jar-plugin` 写入清晰的 Manifest 元数据：
     - `ModID=sts-ai-bridge`
     - `ModName=STS AI Bridge Mod`
     - `ModVersion=1.0.0`
2. 添加标准的 `ModTheSpire.json`：
   - 在 `sts-bridge-mod/src/main/resources/` 下新增 `ModTheSpire.json`：
     - `modid`: `sts-ai-bridge`
     - `name`: `STS AI Bridge Mod`
     - `version`: `1.0.0`
     - `sts_version`: `12-18-2022`
     - `mts_version`: `3.30.3`
     - `dependencies`: `[ "basemod", "stslib" ]`

### 验证结果
- 重新构建并清理旧 jar 后，ModTheSpire 不再提示版本号缺失或格式错误。
- 在 Mod 列表中可以看到带有明确版本号与依赖的 bridge-mod 条目。

---

## 四、代码类型冲突：ISubscriber 实现问题

### 现象
- 在早期实现中，为了向 BaseMod 注册订阅，构造函数中调用：
  - `BaseMod.subscribe(this);`
- 编译时 Maven 报错：
  - “不兼容的类型: sts.ai.bridge.StsAIBridge 无法转换为 basemod.interfaces.ISubscriber”

### 根因分析
- BaseMod 的 `subscribe` 方法参数类型是 `ISubscriber`（或其子接口），而 `StsAIBridge` 当时只是普通类，没有实现该接口。
- 尝试直接订阅会在编译阶段触发类型不匹配错误。

### 解决方案演进
1. 初步尝试：
   - 将类定义改为 `implements ISubscriber`，并在 `initialize()` 中执行 `BaseMod.subscribe(new StsAIBridge());`。
   - 这样可以满足编译期类型检查，但增加了与 BaseMod 生命周期的耦合度，在加载顺序不稳定时可能触发新的 NPE。
2. 最终方案：
   - 评估当前 Bridge 阶段需求，仅依赖 `@SpireInitializer` 与 `@SpirePatch` 即可完成初始化与数据抓取，并未使用任何 BaseMod 的事件回调。
   - 因此彻底移除 `BaseMod.subscribe` 与 `ISubscriber` 实现，将 `StsAIBridge` 恢复为普通初始化类。

### 验证结果
- 删除订阅后，编译错误消失，同时避免了在 BaseMod 初始化未完成前进行订阅导致的潜在 NPE。
- 现阶段所有逻辑均通过 ModTheSpire + SpirePatch 路径生效，结构更简单、可控。

---

## 五、当前阶段成果小结

- 完成 Maven 环境搭建与构建链路配置，支持从命令行一键执行 `clean package`。
- 为 `sts-bridge-mod` 配置了规范的 `pom.xml` 与 `ModTheSpire.json`，消除版本号警告，保证在指定 Slay the Spire / ModTheSpire 版本下稳定加载。
- 基于 `@SpireInitializer` 与 `@SpirePatch(AbstractDungeon.update)` 实现了 Bridge Mod 的基础骨架，能够在地牢运行时周期性打印玩家血量与金币信息。
- 使用延迟初始化 + System.out 回退策略，成功绕开早期日志系统未就绪导致的 NullPointerException。
- 通过 system scope 引入本地依赖（BaseMod、StSLib 等），实现与原版游戏及核心 Mod 的基本联调，为后续接入 Protobuf 协议与 RL 训练引擎奠定基础。

---

## 六、阶段 0 收官日志（2026-01-18 / v1.0.1）

- 版本号：v1.0.1  
- 任务类别：协议扩展 + 训练环境准备

### 工作成果

1. 协议层扩展
   - 在 `protobuf/sts_state.proto` 中补充 `GameOutcome`、`GameState.master_deck`、`GameState.game_outcome` 字段。
   - 为 `CardState`、`RelicState`、`PotionState` 预留 `price` 字段，用于后续训练中的购买决策建模。
   - 基于这些字段整理了《环境缺漏自查与补全报告》，对数据缺口与补全情况做系统梳理。

2. Java 桥接层增强
   - 为 Socket 服务引入 `-Dsts.ai.port` 动态端口配置，解除 9999 端口硬编码限制，支持多实例并行训练。
   - 新增 `RESET` 动作处理分支，统一通过 `CardCrawlGame.startOver = true` 实现死亡后的快速重开。
   - 扩充数据采样范围：在战斗流程中采集 Master Deck、当前楼层的结算结果（胜利 / 死亡、分数、进阶等级）、商店可购买物品的价格占位信息。
   - 对关键行为增加结构化日志前缀（如 `[STS-AI-SOCKET]`、`[STS-AI-ACTION]`），方便跨进程排障。

3. 工具链与环境
   - 新增 `tools/launch_headless.sh`，封装无头模式启动参数，支持：
     - 按需指定端口：`./tools/launch_headless.sh 10001`。
     - 默认加载 `basemod, stslib, sts-ai-bridge` 三个 Mod。
   - 经联调验证，当前环境已达到 “Pre-Gymnasium Ready” 状态，可直接接入 Python 侧 Gymnasium 封装。

### 关键问题与解决方案

1. 端口硬编码导致并行训练冲突
   - 现象：第二个无头实例启动时，日志报 `Address already in use: JVM_Bind`，且 Python 客户端无法连接新实例。
   - 排查思路：
     - 在 `StsAIBridge` 中搜索 `new ServerSocket(9999)`，确认端口为硬编码。
     - 通过 `netstat -ano | findstr 9999` 观察已有占用。
   - 解决方案：
     - 将端口读取逻辑改为 `System.getProperty("sts.ai.port", "9999")`，提供默认值并对非法输入打印告警日志。
   - 验证方式：
     - 分别启动两个实例：`-Dsts.ai.port=10001`、`-Dsts.ai.port=10002`，观察控制台是否正确输出不同的监听端口。
     - 在 Python 端分别连接对应端口，确认无冲突。

2. RESET 动作实现方式选择错误
   - 现象：尝试调用 `CardCrawlGame.startNewRun()` 时，编译阶段报方法不存在或签名不匹配。
   - 排查思路：
     - 使用 IDE 直接跳转到 `CardCrawlGame`，确认可用 API。
     - 通过反编译原版 `desktop-1.0.jar`，核对实际提供的重开机制。
   - 解决方案：
     - 放弃直接调用不存在的方法，改为使用现有的 `CardCrawlGame.startOver` 标志位，由游戏主循环驱动新一局的启动。
   - 验证方式：
     - 在处理 `RESET` 分支前后打印日志 `[STS-AI-ACTION] 执行 RESET 动作`。
     - 游戏内手动启动一局，触发 RESET 后观察是否自动返回主菜单并开始新 Run。

3. 游戏结果与分数获取路径不清晰
   - 现象：早期尝试直接调用 `DeathScreen.calcScore()` 等内部方法，存在访问权限或依赖 UI 状态的问题。
   - 排查思路：
     - 在 `DEATH`、`VICTORY` 屏幕出现时，通过日志打印 `AbstractDungeon.floorNum`、`AbstractDungeon.ascensionLevel` 等核心字段。
     - 检查是否存在稳定、无需额外 UI 状态的分数来源。
   - 解决方案：
     - 当前版本采用简化计算逻辑：以楼层数与进阶等级近似估算分数，并通过 `GameOutcome` 结构对外暴露。
     - 在日志中明确标注这是 “近似得分”，为后续精确还原留出空间。
   - 验证方式：
     - 在不同难度与楼层结束战斗，比较日志中的估算分数与游戏结算界面的实际分数，评估偏差并记录样本。

4. 商店与药水价格字段缺失
   - 现象：在为 `PotionState.price`、`CardState.price`、`RelicState.price` 赋值时，发现 `AbstractPotion` 等类缺少公开的 `price` 字段或访问受限。
   - 排查思路：
     - 使用 IDE 结构视图或反编译类文件，确认价格相关字段是否为 `private` / `protected`。
     - 在不修改权限的前提下尝试通过现有 API 获取价格，验证是否可行。
   - 解决方案：
     - 当前版本中仅在协议层预留价格字段，对部分无法直接访问的字段暂不赋值，并在代码中通过日志注明“待 AccessTransformer 支持”。
   - 验证方式：
     - 进入商店场景，检查日志中关于商店物品序列化的输出，确认不会因价格字段访问失败导致崩溃。
     - 后续在引入 AccessTransformer 后再次补充验证用例。
