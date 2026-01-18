# STS-AI-Master: 基于多进程无头环境与 PPO 算法的杀戮尖塔 AI 决策系统

[![Technical Stack](https://img.shields.io/badge/Stack-Java%20%7C%20Python%20%7C%20C%2B%2B-blue)](https://github.com/YourUsername/STS-AI-Master)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Phase](https://img.shields.io/badge/Phase-0%20Completed%20(Protocol%20%2B%20Bridge)-orange)](https://github.com/YourUsername/STS-AI-Master)

## 🌟 项目愿景
本项目旨在打造一款高效、解耦、可扩展的《杀戮尖塔》AI 决策系统。通过无头渲染（Headless）技术绕开游戏引擎动画开销，利用云边端协同架构，实现从大规模并行训练到本地低延迟推理的全链路闭环。

---

## 🛠️ 技术核心与架构设计

项目采用 Monorepo 结构管理，核心逻辑遵循 “Protocol-First”（协议先行） 原则。

### 1. 跨语言协同架构
- Java（Mod）负责游戏状态拦截与指令注入，通过 Hook 游戏核心类（如 `AbstractDungeon`），在逻辑层直接提取数据。
- Python（RL）基于 Stable Baselines3 实现 PPO 强化学习算法，支持多实例并行数据采集，相比 UI 模式训练效率提升 200%–500%。
- C++/Qt（Client）作为轻量化决策客户端，集成 ONNX Runtime，实现单步推理延迟小于 5ms。

### 2. 通信协议（Protobuf）
项目放弃传统 JSON 传输，统一使用 Google Protobuf 作为跨语言契约：
- 强类型校验：避免跨语言开发中的数据类型对齐问题。
- 高性能序列化：降低高频采样时的 CPU 开销。
- 防死锁机制：在协议中引入 `sequence_id` 与 `is_waiting_for_input` 字段，保证指令执行的原子性。

当前使用的协议版本为 `docs/protocols/sts_v1.proto`，是所有跨语言通信的单一真理来源。

---

## 📂 项目组织结构

```text
STS-AI-Master/
├── docs/                     # 项目灵魂：协议定义与架构文档
│   ├── protocols/            # .proto 契约文件 (真理来源)
│   └── dev_logs/             # 每日开发日志与 Debug 记录
├── sts-bridge-mod/           # [Java] 游戏数据拦截 Mod
├── training-engine/          # [Python] 强化学习训练引擎 (Gym/PPO)
├── qt-decision-client/       # [C++] Qt/ONNX 落地推理客户端
├── cloud-backend/            # [Flask] 云端数据同步与策略中心
└── tools/                    # 自动化脚本工具箱 (一键编译/无头启动)
```

---

## 🚀 快速开始

### 环境准备

- Java 8+
- Python 3.10+（依赖见 `training-engine/requirements.txt`）
- CMake 与 C++17 编译器
- protoc ≥ 3.13.0（已在本仓库开发环境中验证）

### 拉取项目

```bash
git clone https://github.com/YourUsername/STS-AI-Master.git
cd STS-AI-Master
```

### 编译 Protobuf 协议

- Linux/WSL：

```bash
chmod +x tools/protobuf_compile.sh
./tools/protobuf_compile.sh
```

- Windows（PowerShell 或 CMD）：

```powershell
cd C:\Projects\GitHub\STS-AI-Master
.\tools\protobuf_compile.bat
```

执行成功后，将分别生成：

- Java 协议代码：`sts-bridge-mod/src/main/java/`
- Python 协议代码：`training-engine/envs/sts_v1_pb2.py`
- C++ 协议代码：`qt-decision-client/include/sts_v1.pb.h`

---

## ✅ 当前进度

- Phase 0：协议定义与 Java Mod Bridge —— **已完成**
  - 基于 `docs/protocols/sts_v1.proto` 建立统一跨语言协议（包含 `GameState`、`ActionCommand`、`ProtocolMessage` 等核心结构）。
  - 完成 `sts-bridge-mod` Mod 工程搭建，通过 `@SpireInitializer` + `@SpirePatch(AbstractDungeon.update)` 拦截游戏状态。
  - 接入 `protobuf-maven-plugin` 与独立的 `protobuf/sts_state.proto`，实现结构化的玩家与怪物状态采样。
  - 使用 `maven-shade-plugin` 将 `protobuf-java` 打入 Mod 并通过 Relocation 将 `com.google.protobuf` 重定位到 `sts.ai.bridge.repackaged.protobuf`，解决运行时依赖缺失与潜在类加载冲突。
  - 在战斗中每 3 秒输出一条带有 `[STS-AI-PROTO]` 前缀的 `GameState` 文本日志，验证数据实时性与完整性（详见 `docs/dev_logs/STAGE_1_SUMMARY.md`）。

- Stage 1：数据结构化与感知打通 —— **已完成**
  - 将原始字符串日志升级为 Protobuf 协议对象 `GameState` 的构造与打印。
  - 数据覆盖范围：
    - Player：HP、Max HP、Gold、Energy、Block、Floor。
    - Monsters：ID、Name、HP、Max HP、Intent、Block。
  - 通过 `[STS-AI-PROTO]` 日志为 Python / Qt 侧提供稳定的外部观测入口，为后续 Gym 环境与训练闭环打基础。

---

## 🗺️ Roadmap

| Phase | 名称                             | 说明                                                                                         | 状态     |
|-------|----------------------------------|----------------------------------------------------------------------------------------------|----------|
| 0     | 协议定义与 Java Mod Bridge      | 设计跨语言 Protobuf 协议，搭建 Mod 拦截层与结构化状态采样通路（含 Shade + Relocation）     | 已完成   |
| 1     | Python 训练引擎                  | 构建 Gymnasium 环境封装与 PPO 训练流水线，打通多进程采样到训练闭环                         | 规划中   |
| 2     | Qt 决策客户端                    | 基于 ONNX Runtime 的本地推理客户端与可视化面板                                              | 规划中   |
| 3     | 大规模训练与评估                 | 多环境并行训练、策略评估与对比实验工具链                                                    | 规划中   |
| 4     | 云端策略中心与数据同步           | 云端数据存储、策略管理与下发，支撑多终端共享策略                                            | 规划中   |

Phase 0 聚焦于协议定义与 Java Mod 工程，目前已完成基础协议、Bridge Mod 和结构化采样链路；后续阶段将依次推进 Python 训练引擎、Qt 客户端与云端策略中心。

---

## 🧰 快速排障（Troubleshooting）

- Protobuf 相关错误：
  - 现象：运行 Python 客户端时出现 `TypeError: Descriptors cannot be created directly`。
  - 快速检查：
    - 使用 `pip show protobuf` 确认当前版本是否在 `<= 3.20.x` 区间。
    - 确认 `sts_state_pb2.py` 是由兼容版本的 `protoc` 重新生成（包含 `create_key=_descriptor._internal_create_key` 等字段）。
    - 在 Python 客户端中，优先使用 `always_print_fields_with_no_presence` 等兼容参数，而非较新文档中的替代参数名。
    - 必要时设置环境变量：`PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION=python` 以提升兼容性。

- 端口占用与 Socket 连接失败：
  - 现象：
    - Java 端日志中出现 `Address already in use: JVM_Bind` 或无法看到 `Listening on port 9999`。
    - Python 客户端报错 `ConnectionRefusedError`，无法连接到 `127.0.0.1:9999`。
  - 快速检查：
    - 使用 `netstat -ano | findstr 9999` 确认端口占用情况，如有残留进程，使用 `taskkill /PID <pid> /F` 清理。
    - 确认 STS-AI Bridge Mod 已成功加载，并在控制台看到 `[STS-AI-SOCKET] Listening on port 9999` 日志。
    - 检查防火墙或杀毒软件是否拦截本地回环连接。

---

## 📄 许可证

本项目采用 MIT License，详情见 `LICENSE` 文件。
