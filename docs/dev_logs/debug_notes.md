# Stage 2 调试笔记（Debug Notes）

## 问题 1：Protobuf 版本冲突

- 现象：
  - 运行 `python python_ai/client_demo.py` 时出现错误：
    - `TypeError: Descriptors cannot be created directly`
- 根本原因：
  - Python 端安装的 `google.protobuf` 库版本为新版本（>= 4.x 或高于本地 protoc 的生成能力）。
  - 本地 `sts_state_pb2.py` 由较旧版本的 `protoc` 生成，生成代码风格与当前 Python 库不兼容，导致在导入模块时构造 Descriptor 失败。
- 解决方案：
  - 将 Python 端 Protobuf 依赖锁定在兼容区间：
    - `pip install "protobuf<=3.20.x"`
  - 确保 `sts_state_pb2.py` 使用与 3.20.x 风格兼容的 `protoc` 重新生成（包含 `create_key=_descriptor._internal_create_key` 等字段）。
  - 在 Python 客户端中：
    - 使用兼容旧生成代码的参数名 `always_print_fields_with_no_presence`，而不是较新文档中的 `including_default_value_fields`。
    - 在必要时通过环境变量启用纯 Python 实现以提高兼容性：
      - `PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION=python`

## 问题 2：端口占用（JVM_Bind / ConnectionRefusedError）

- 现象：
  - Java 端：
    - 在某些情况下，`ServerSocket(9999)` 绑定失败，但早期没有清晰的日志，容易误以为 Mod 没有被正确加载。
    - 真实错误为 `Address already in use: JVM_Bind`，说明 9999 端口已被其他进程占用。
  - Python 端：
    - 客户端执行时出现 `ConnectionRefusedError`，无法连接到 `127.0.0.1:9999`。
- 根本原因：
  - 之前调试或异常退出残留的服务进程仍然占用 9999 端口。
  - Java 端未对 `BindException` 做单独捕获，导致日志信息不够直观。
- 解决方法：
  - 在操作系统层面使用 `taskkill` 或类似工具清理残留进程：
    - Windows（示例思路）：先通过 `netstat -ano | findstr 9999` 查找占用端口的 PID，再使用 `taskkill /PID <pid> /F` 结束对应进程。
  - 在 Java 端（`StsAIBridge` 中的 SocketServerRunnable）增加对 `BindException` 的显式捕获和日志输出：
    - 当端口占用时打印：
      - `"[STS-AI-SOCKET] Port 9999 bind failed (possibly in use): Address already in use: JVM_Bind"`
  - 在 Python 客户端中，对连接阶段的异常增加友好提示：
    - 捕获 `ConnectionRefusedError` 与通用 `OSError`，提示检查：
      - STS-AI Bridge Mod 是否已加载并正常运行；
      - 9999 端口是否被其他进程占用；
      - 防火墙或安全软件是否阻止本地连接。

