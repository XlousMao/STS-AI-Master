# STS-AI-Master 项目准则 (Project Rules)



## 1. 核心架构原则

- **协议驱动开发 (Protocol-First)**: 所有的跨语言通信必须严格遵循 `docs/protocols/sts.proto`。修改任何通信逻辑前，必须先确认协议定义。

- **模块解耦**: 严禁在 `sts-bridge-mod` (Java) 中编写 Python 逻辑，严禁在 `training-engine` 中直接调用 C++ 库，必须通过定义的接口通信。

- **防死锁设计**: 跨语言通信必须实现 `sequence_id` 序列号校验和 `is_waiting_for_input` 状态判断，严禁无节制下发指令导致缓冲区积压。



## 2. 语言与技术栈规范

- **Java (Mod)**: 遵循 Spire Mod 标准，优先使用事件 Hook 捕获状态，避免破坏原版游戏逻辑循环。

- **Python (RL)**: 兼容 Gymnasium 接口。状态向量 (Observation) 必须进行归一化处理；强化学习算法需集成优先经验回放（PER），动作空间必须基于 Mod 提供的 `valid_actions` 剪枝。

- **C++/Qt**: 必须使用 ONNX Runtime C++ API 进行推理。UI 逻辑与推理逻辑必须分离；推理延迟需记录并输出，目标延迟 < 5ms。



## 3. 协作与 Token 管理

- **拒绝自动驾驶**: 除非明确要求 "Solo Mode"，否则严禁擅自修改多个文件夹的代码。

- **改动说明**: 每次修改代码后，需简述修改原因，并标注是否涉及跨语言协议变动。

- **性能敏感**: 在编写 C++ 或 Java 核心循环时，优先考虑执行效率和内存分配，避免高频创建 JSON 对象，优先使用 Protobuf。



## 4. 目录守则

- `docs/`: 存放全局真理。协议文件修改必须版本号递增（如 `sts_v1.proto` → `sts_v2.proto`），并在 `architecture.md` 中记录变更日志。

- `tools/`: 存放自动化脚本，严禁将业务逻辑写在这里。

- `training-engine/models/`: 仅允许通过 Git LFS 或脚本管理大型模型，严禁直接生成巨大的二进制文件到源码目录。