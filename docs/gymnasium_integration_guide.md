# Gymnasium 环境使用手册 (Gymnasium Integration Guide)

## 1. 环境概述
`SlayTheSpireEnv` 是基于 Gymnasium (v0.26+) 标准开发的 Slay the Spire 强化学习环境。
它通过 TCP Socket 与 Java 侧的 Mod (`StsAIBridge`) 通信，实现对游戏的全面观测与控制。

## 2. 安装与配置

### 依赖安装
请确保安装以下 Python 库：
```bash
pip install gym==0.26.2 stable-baselines3 protobuf pyyaml numpy
```

### 配置文件
环境运行依赖项目根目录下的 `env_config.yaml`：
```yaml
run_mode: headless      # 运行模式: headless (无头) / visual (可视化)
port: 9999              # 通信端口
normalize_obs: true     # 是否归一化观测数据
seed: 42                # 随机种子
```

## 3. 核心 API

### 初始化
```python
import gym
import gym_sts

env = gym.make("SlayTheSpire-v0", run_mode="headless", port=9999)
```

### 观测空间 (Observation Space)
环境返回一个嵌套字典 (`Dict`)，包含：
- `player`: 玩家状态 (HP, Energy, Block, Floor 等归一化数值)
- `monsters`: 怪物列表 (5x6 矩阵)
- `hand`: 手牌列表 (10x10 矩阵)
- `game_global`: 全局状态 (屏幕类型, 战斗状态)

### 动作空间 (Action Space)
环境使用离散动作空间 (`Discrete(50)`)，涵盖战斗、地图、商店等全流程操作。
具体映射请参考 `env.action_map` 或源码中的 `ACTION_OFFSETS`。

### 动作掩码 (Action Masking)
环境在 `info["action_mask"]` 中返回当前有效的动作掩码（二进制数组）。
推荐使用支持 Maskable PPO 的算法（如 `sb3-contrib`）以提升训练效率。

## 4. 快速开始

### 运行单局测试
```bash
python test_single_episode.py
```

### 启动 PPO 训练
```bash
python train_ppo.py
```

### 模型验证
```bash
python validate_model.py
```
