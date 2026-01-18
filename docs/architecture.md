STS-AI-Master/ (Root)

├── .git/

├── .gitignore # 完善的忽略规则

├── docs/ # 项目灵魂：文档中心

│ ├── protocols/ # Protobuf/JSON 协议文件

│ ├── dev_logs/ # 每日开发日志 + Debug 心得

│ ├── architecture.md # 架构设计 + 模块交互图

│ └── setup_guide.md # 环境搭建指南

│

├── sts-bridge-mod/ # 阶段0：Java Mod 工程

│ ├── src/main/java/

│ └── pom.xml

│

├── training-engine/ # 阶段1：Python 训练工程

│ ├── envs/ # Gym 环境封装

│ ├── models/ # 训练好的模型（.pth/.onnx）

│ ├── scripts/ # train.py / evaluate.py

│ ├── experiments/ # 超参数实验记录

│ └── requirements.txt

│

├── qt-decision-client/ # 阶段2：Qt 客户端工程

│ ├── src/

│ ├── include/

│ ├── resources/ # UI 资源

│ └── CMakeLists.txt

│

├── cloud-backend/ # 阶段4：云服务工程

│ ├── app/ # Flask 接口逻辑

│ └── database/ # 数据库脚本

│

├── tools/ # 自动化工具箱

│ ├── launch_headless.sh # 一键启动无头实例

│ ├── data_sniffer.py # 通信流量监控

│ ├── protobuf_compile.sh # Protobuf 一键编译

│ └── performance_test.py # 推理性能测试

│

└── test/ # 单元测试 + 联调测试用例

├── mod_test/

├── training_test/

└── client_test/