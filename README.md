# STS-AI-Master: 基于多进程无头环境与 PPO 算法的杀戮尖塔 AI 决策系统

[![Technical Stack](https://img.shields.io/badge/Stack-Java%20%7C%20Python%20%7C%20C%2B%2B-blue)](https://github.com/YourUsername/STS-AI-Master)
[![License](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)
[![Phase](https://img.shields.io/badge/Phase-0%20(Protocol%20Design)-orange)](https://github.com/YourUsername/STS-AI-Master)

## 🌟 项目愿景
本项目旨在打造一款高效、解耦、可扩展的《杀戮尖塔》AI 决策系统。通过**无头渲染（Headless）**技术绕开游戏引擎动画开销，利用**云边端协同架构**，实现从大规模并行训练到本地低延迟推理的全链路闭环。

---

## 🛠️ 技术核心与架构设计

项目采用 **Monorepo** 结构管理，核心逻辑遵循 **"Protocol-First" (协议先行)** 原则。

### 1. 跨语言协同架构
- **Java (Mod)**: 负责游戏状态拦截与指令注入。通过 Hook 游戏核心类（如 `AbstractDungeon`），在逻辑层直接提取数据。
- **Python (RL)**: 基于 **Stable Baselines3** 实现 PPO 强化学习算法。支持多实例并行数据采集，训练效率较 UI 模式提升 200%-500%。
- **C++/Qt (Client)**: 轻量化决策客户端。集成 **ONNX Runtime**，实现推理延迟 < 5ms 的极致体验。

### 2. 通信协议 (Protobuf)
本项目放弃了传统的 JSON 传输，采用 **Google Protobuf** 定义通信契约，确保了：
- **强类型校验**: 避免跨语言开发中的数据类型对齐问题。
- **高性能序列化**: 极大地降低了高频采样（Sampling）时的 CPU 开销。
- **防死锁机制**: 引入 `sequence_id` 与 `is_waiting_for_input` 状态位，确保指令执行的原子性。



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