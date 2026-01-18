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

