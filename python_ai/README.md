python_ai 运行环境说明
====================

- Python 版本：推荐 3.8 及以上。
- 依赖安装：使用 pip 安装 protobuf，建议版本范围为：
  - `pip install "protobuf<=3.20.x"`
- 协议代码：`sts_state_pb2.py` 由与 3.20.x 兼容的 protoc 版本生成。
- 运行客户端示例：
  - 在项目根目录执行：`python python_ai/client_demo.py`
  - 确保《杀戮尖塔》已启动且加载了 STS-AI Bridge Mod，并监听 9999 端口。
- 兼容性说明：
  - `client_demo.py` 在启动时设置环境变量 `PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION=python`，用于兼容旧版本生成的协议代码。
  - 当你的 protoc 与 Python 端 protobuf 版本均升级到 3.20.x 及以上且重新生成了 `sts_state_pb2.py` 时，可以按需移除该环境变量设置。

