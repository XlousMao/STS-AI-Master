# END_TURN 采样乱序问题技术复盘

## 一、问题现象

- 场景：玩家处于出牌阶段，手牌约 5 张。
- 操作：Python 侧向 Java Mod 发送 `END_TURN` 指令。
- 观察：日志中先出现抽牌相关行为（手牌数短暂增多），然后才看到结束回合的动作生效。
- 结果：AI 在「回合转换的间隙」看到了一个不稳定的中间态：
  - 采样到的手牌既不是当前回合真正的终态；
  - 也不是下一个回合真正开始后的稳态；
  - 导致策略侧根据错误的 Observation 做出决策。

本质上，这是一个“游戏内部状态机”和“外部采样器”之间的时序竞争问题：  
当引擎正在从「玩家回合」切到「敌人回合」或下一回合时，我们的采样逻辑仍然按固定间隔运行，可能刚好踩在状态机中间的临界点上。

## 二、失败尝试：自定义布尔锁 isProcessingAction

### 1. 设计初衷

- 在 `DungeonUpdateMonitorPatch.Postfix` 中：
  - 从 `actionQueue` 取出 `GameAction` 后，将 `isProcessingAction` 设为 `true`，表示“当前帧正在执行 AI 指令”；
  - 在采样逻辑中增加 `!isProcessingAction` 条件，期望做到“同一帧里只要执行过动作，就绝不采样”。
- 再通过实现 `PostUpdateSubscriber.receivePostUpdate()`：
  - 当检测到 `AbstractDungeon.actionManager.phase == WAITING_ON_USER` 且 `manager.actions.isEmpty()` 时，将 `isProcessingAction` 重置为 `false`。

### 2. 实际问题

1. 解锁时机过早
   - `receivePostUpdate()` 是基于 BaseMod 的钩子，调用时机与 `Dungeon.update`、`GameActionManager` 的内部调度并非“一对一可控”。
   - 在某些帧序列下，`phase` 和 `actions` 虽然看起来已经“空闲”，但游戏内部关于抽牌、buff 结算等流程还未完全结束。
   - 这导致布尔锁在逻辑上“提前解锁”，下一次 3 秒采样窗口到来时，采样器已经重新开放。

2. 与引擎状态机脱节
   - `isProcessingAction` 是我们凭空定义的外部状态，并没有与 Slay the Spire 内部的状态机强绑定。
   - 只要我们对“何时算空闲”的理解有一点偏差，就会重新引入竞态条件。

3. 维护成本高、可观察性差
   - 需要在多个钩子之间手动维护同一个布尔量；
   - 出现问题时很难从日志中直接判断锁的流转是否完全覆盖了所有状态路径。

总结：**自定义布尔锁违背了“尽量复用游戏内建状态机”的原则，导致解锁时机不可验证，最终难以保证彻底消除竞态。**

## 三、最终方案：基于原生状态位的“四重门”静默采样

### 1. 核心思路

放弃自定义锁，改为完全依赖游戏引擎已有的状态信息，通过严苛的采样条件来实现“静默”：

1. 仅在玩家真正可操作、且战斗状态稳定时采样；
2. 在执行 `END_TURN` 时，显式使用原生状态位 `AbstractPlayer.isEndingTurn` 标记“正在结束回合”；
3. 采样逻辑中显式拒绝任何处于“结束回合中”的帧。

### 2. 关键实现

1. 删除自定义锁和订阅
   - 移除：
     - `private static volatile boolean isProcessingAction = false;`
     - `implements PostUpdateSubscriber`
     - `BaseMod.subscribe(new StsAIBridge());`
     - `receivePostUpdate()` 的所有逻辑
   - 让 Mod 不再依赖额外的解锁回调，而是紧贴引擎本身的状态。

2. 执行 END_TURN 时标记原生状态
   - 在 `DungeonUpdateMonitorPatch.Postfix` 中消费 `END_TURN` 动作时：
     - 调用 `AbstractDungeon.player.isEndingTurn = true;`
     - 然后 `manager.addToBottom(new EndTurnAction());`
   - 意义：
     - 告诉游戏内部状态机“这个回合已经进入结束流程”；
     - 同时也为采样器提供一个可靠的布尔信号，用于屏蔽这一段极其敏感的过渡区间。

3. 四重门采样条件
   - 最终采样的入口条件为：
     - 时间门：`now - lastLogTime >= LOG_INTERVAL_MS`
     - 阶段门：`manager.phase == GameActionManager.Phase.WAITING_ON_USER`
     - 队列门：`manager.actions.isEmpty()`
     - 状态门：`!AbstractDungeon.player.isEndingTurn`
     - 场景门：`AbstractDungeon.getCurrRoom().phase == AbstractRoom.RoomPhase.COMBAT`
   - 任何一个条件不满足，都直接跳过采样。

### 3. 效果

- 采样器只会在“玩家可正常出牌、没有动作在结算、不处于结束回合中、且确实在战斗里”的帧运行。
- 即使外部脚本在边缘时机发送 `END_TURN`，也不会再出现：
  - “先抽牌再结束回合”的日志顺序错乱；
  - AI 读取到手牌数短暂异常的中间态。

## 四、经验与教训：游戏状态机 vs 外部控制逻辑

1. 尽量复用引擎的原生状态位
   - `AbstractPlayer.isEndingTurn`、`GameActionManager.phase`、`actions.isEmpty()` 等字段，是游戏引擎为自身状态机服务的“一手信息”。
   - 外部控制逻辑（例如 RL、自动化脚本）应尽可能基于这些状态来做决策，而不是额外发明一层“影子状态机”。

2. 外部锁永远落后于内部状态
   - 自定义布尔锁本质上是在“猜测”引擎何时忙、何时闲；
   - 只要猜测与真实状态机有一帧的偏差，就可能在关键时刻放开采样或执行动作，产生难以重现的竞态 bug。

3. 采样时机必须和“可交互时机”强绑定
   - 对于回合制游戏来说，真正有意义的 Observation 是：
     - 玩家可以做出决定的那些稳定时间点，而不是所有帧。
   - 因此，采样条件应该表达为“玩家可交互”而不是“时间到了就采样”。

4. Debug 时要勇于怀疑自己的“锁”
   - 初看上去，`isProcessingAction` 这类布尔锁似乎很好理解、也容易下断点；
   - 但一旦问题与状态机时序有关，优先检查是否有原生状态位可以替代手写锁，这是更符合长期可维护性的解法。

本次问题最大的收获在于：  
**在具有复杂内部状态机的游戏/系统中，外部控制逻辑应尽可能“贴着状态机”做约束，而不是试图在外部再造一套并行的锁机制。**

