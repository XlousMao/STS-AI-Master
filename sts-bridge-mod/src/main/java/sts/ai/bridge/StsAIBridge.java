package sts.ai.bridge;

import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.megacrit.cardcrawl.actions.GameActionManager;
import com.megacrit.cardcrawl.actions.common.EndTurnAction;
import com.megacrit.cardcrawl.cards.AbstractCard;
import com.megacrit.cardcrawl.cards.CardQueueItem;
import com.megacrit.cardcrawl.characters.AbstractPlayer;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import com.megacrit.cardcrawl.rooms.AbstractRoom;
import com.megacrit.cardcrawl.orbs.AbstractOrb;
import com.megacrit.cardcrawl.powers.AbstractPower;
import com.megacrit.cardcrawl.relics.AbstractRelic;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import com.megacrit.cardcrawl.map.MapEdge;
import com.megacrit.cardcrawl.map.MapRoomNode;
import com.megacrit.cardcrawl.rewards.RewardItem;
import java.lang.reflect.Field;
import com.megacrit.cardcrawl.shop.ShopScreen;
import com.megacrit.cardcrawl.potions.AbstractPotion;
import com.megacrit.cardcrawl.core.CardCrawlGame;
import com.megacrit.cardcrawl.screens.DeathScreen;
import com.megacrit.cardcrawl.screens.VictoryScreen;
import java.util.ArrayList;
import sts.ai.state.v1.*;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * STS-AI Bridge Mod 入口类。
 * 负责：
 * 1. 提供 ModTheSpire 入口（@SpireInitializer + initialize）
 * 2. 在初始化阶段输出心跳日志，验证 Mod 是否被正确加载
 * 3. 通过对 AbstractDungeon.update 的 Patch，周期性采集玩家关键数据（血量、金币）
 * 后续 Protobuf 序列化会在此处接入，将这些数据发送到外部训练 / 推理进程。
 */
@SpireInitializer
public class StsAIBridge {
    /**
     * 日志句柄，采用延迟初始化以避免在类加载早期触发依赖问题。
     * 注意：任何地方使用前必须判空，否则在日志系统未就绪时可能导致 NPE。
     */
    private static Logger logger;

    private static volatile boolean socketServerStarted = false;
    private static ServerSocket serverSocket;
    private static volatile Socket clientSocket;
    private static volatile DataInputStream clientIn;
    private static volatile DataOutputStream clientOut;
    private static final ConcurrentLinkedQueue<GameAction> actionQueue = new ConcurrentLinkedQueue<>();

    /**
     * 数据采样的时间间隔（毫秒）。
     * 目前简单地使用 System.currentTimeMillis 做节流，后续可以根据采样频率需求动态调整。
     */
    private static final long LOG_INTERVAL_MS = 3000L;

    /**
     * Mod 入口，由 ModTheSpire 通过反射调用。
     * 当前职责：
     * 1. 使用 System.out 打印一条最原始的心跳日志，确保在日志框架失效时也能看到加载情况
     * 2. 延迟初始化 Log4j 的 logger，并输出一条结构化的初始化日志
     * 3. 不做任何复杂逻辑，以降低初始化阶段出错的概率
     */
    public static void initialize() {
        System.out.println("[STS-AI] StsAIBridge.initialize() entered.");
        logger = LogManager.getLogger(StsAIBridge.class);
        if (logger != null) {
            logger.info("[STS-AI] Bridge Project Initialized! Ready for Training.");
        }
        if (!socketServerStarted) {
            socketServerStarted = true;
            Thread t = new Thread(new SocketServerRunnable());
            t.setDaemon(true);
            t.setName("STS-AI-SocketServer");
            t.start();
        }
    }

    private static class SocketServerRunnable implements Runnable {
        @Override
        public void run() {
            try {
                // ARCHITECTURE CHANGE: Read port from System Property, default to 9999
                String portProp = System.getProperty("sts.ai.port", "9999");
                int port = 9999;
                try {
                    port = Integer.parseInt(portProp);
                } catch (NumberFormatException e) {
                    System.err.println("[STS-AI-SOCKET] Invalid port property: " + portProp + ", using default 9999.");
                }

                serverSocket = new ServerSocket(port);
                System.out.println("[STS-AI-SOCKET] Listening on port " + port);
                while (true) {
                    Socket socket = serverSocket.accept();
                    System.out.println("[STS-AI-SOCKET] Client connected: " + socket.getRemoteSocketAddress());
                    synchronized (StsAIBridge.class) {
                        closeClientQuietly();
                        clientSocket = socket;
                        clientIn = new DataInputStream(socket.getInputStream());
                        clientOut = new DataOutputStream(socket.getOutputStream());
                        Thread reader = new Thread(new ClientActionReader(socket, clientIn));
                        reader.setDaemon(true);
                        reader.setName("STS-AI-ActionReceiver");
                        reader.start();
                    }
                }
            } catch (BindException e) {
                System.out.println("[STS-AI-SOCKET] Port bind failed (possibly in use): " + e.getMessage());
            } catch (IOException e) {
                System.out.println("[STS-AI-SOCKET] Server error: " + e.getMessage());
            } finally {
                closeServerQuietly();
            }
        }
    }

    private static void closeClientQuietly() {
        if (clientIn != null) {
            try {
                clientIn.close();
            } catch (IOException ignored) {
            }
        }
        if (clientOut != null) {
            try {
                clientOut.close();
            } catch (IOException ignored) {
            }
        }
        if (clientSocket != null) {
            try {
                clientSocket.close();
            } catch (IOException ignored) {
            }
        }
        clientIn = null;
        clientOut = null;
        clientSocket = null;
    }

    private static void closeServerQuietly() {
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        serverSocket = null;
    }

    private static class ClientActionReader implements Runnable {
        private final Socket socket;
        private final DataInputStream in;

        private ClientActionReader(Socket socket, DataInputStream in) {
            this.socket = socket;
            this.in = in;
        }

        @Override
        public void run() {
            try {
                while (!socket.isClosed()) {
                    int length;
                    try {
                        length = in.readInt();
                    } catch (IOException e) {
                        System.out.println("[STS-AI-ACTION] Failed to read action length: " + e.getMessage());
                        break;
                    }
                    if (length <= 0) {
                        continue;
                    }
                    byte[] payload = new byte[length];
                    try {
                        in.readFully(payload);
                    } catch (IOException e) {
                        System.out.println("[STS-AI-ACTION] Failed to read action payload: " + e.getMessage());
                        break;
                    }
                    try {
                        GameAction action = GameAction.parseFrom(payload);
                        actionQueue.add(action);
                        System.out.println("[STS-AI-ACTION] Enqueued GameAction: " + action.toString());
                    } catch (Exception e) {
                        System.out.println("[STS-AI-ACTION] Failed to parse GameAction: " + e.getMessage());
                    }
                }
            } finally {
                synchronized (StsAIBridge.class) {
                    if (socket == clientSocket) {
                        closeClientQuietly();
                    }
                }
            }
        }
    }

    // Reflection Helper
    private static <T> T getPrivateField(Object instance, String fieldName, Class<T> type) {
        if (instance == null) return null;
        try {
            Field field = instance.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return type.cast(field.get(instance));
        } catch (NoSuchFieldException e) {
            try {
                Field field = instance.getClass().getSuperclass().getDeclaredField(fieldName);
                field.setAccessible(true);
                return type.cast(field.get(instance));
            } catch (Exception ex) {
                // Silent fail or log if needed
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 对 AbstractDungeon.update 做 Patch。
     * 利用 Postfix 在每帧逻辑更新后插入采样点：
     * 1. 只在玩家存在（AbstractDungeon.player != null）时采样
     * 2. 通过时间节流，每 LOG_INTERVAL_MS 采样一次，避免刷屏
     * 3. 当前只采集 HP 和 Gold，后续可扩展为完整的 GameState，并序列化为 Protobuf
     */
    @SpirePatch(
            clz = AbstractDungeon.class,
            method = "update"
    )
    public static class DungeonUpdateMonitorPatch {
        /**
         * 上一次成功输出采样日志的时间戳（毫秒）。
         * 使用静态字段跨帧记忆，配合 LOG_INTERVAL_MS 做简单的节流控制。
         */
        private static long lastLogTime = 0L;

        /**
         * 在 AbstractDungeon.update 执行完成后被调用。
         * 当前实现：
         * - 判空保护：玩家不存在时直接返回
         * - 时间窗口：间隔不足时不输出
         * - 输出渠道：
         *   - 优先使用 logger.info，方便后续统一收集日志
         *   - 当 logger 尚未初始化时退化为 System.out.println，保证最小可观测性
         */
        public static void Postfix(AbstractDungeon __instance) {
            if (AbstractDungeon.player == null) {
                return;
            }
            AbstractPlayer player = AbstractDungeon.player;
            GameActionManager manager = AbstractDungeon.actionManager;
            if (manager == null) {
                return;
            }
            if (manager.phase != GameActionManager.Phase.WAITING_ON_USER || !manager.actions.isEmpty()) {
                return;
            }
            GameAction action;
            while ((action = actionQueue.poll()) != null) {
                if ("END_TURN".equals(action.getActionType())) {
                    AbstractRoom room = AbstractDungeon.getCurrRoom();
                    if (room != null
                            && room.phase == AbstractRoom.RoomPhase.COMBAT
                            && !manager.turnHasEnded) {
                        System.out.println("[STS-AI-ACTION] 执行 END_TURN 动作");
                        AbstractDungeon.overlayMenu.endTurnButton.disable(true);
                        player.isEndingTurn = true;
                        manager.addToBottom(new EndTurnAction());
                        return;
                    } else {
                        System.out.println("[STS-AI-ACTION] 忽略 END_TURN 动作（非战斗或非玩家回合 / 状态不稳定）");
                    }
                } else if ("RESET".equals(action.getActionType())) {
                    System.out.println("[STS-AI-ACTION] 执行 RESET 动作");
                    CardCrawlGame.startOver = true;
                    return;
                } else if ("PLAY_CARD".equals(action.getActionType())) {
                    int cardIndex = action.getCardIndex();
                    if (cardIndex < 0 || cardIndex >= player.hand.size()) {
                        System.out.println("[STS-AI-ACTION] 无效的 card_index，忽略 PLAY_CARD 动作");
                        continue;
                    }
                    AbstractCard card = player.hand.group.get(cardIndex);
                    AbstractMonster target = null;
                    if (card.target == AbstractCard.CardTarget.ENEMY || card.target == AbstractCard.CardTarget.SELF_AND_ENEMY) {
                        if (AbstractDungeon.getMonsters() != null && AbstractDungeon.getMonsters().monsters != null) {
                            int targetIndex = action.getTargetIndex();
                            if (targetIndex >= 0 && targetIndex < AbstractDungeon.getMonsters().monsters.size()) {
                                target = AbstractDungeon.getMonsters().monsters.get(targetIndex);
                            }
                        }
                        if (target == null) {
                            System.out.println("[STS-AI-ACTION] 无法找到有效目标怪物，忽略 PLAY_CARD 动作");
                            continue;
                        }
                    } else {
                        // 对于非指向性卡牌（SELF, ALL_ENEMY, NONE 等），忽略 target_index
                        // target 保持为 null 即可
                    }
                    if (!card.hasEnoughEnergy()) {
                        System.out.println("[STS-AI-ACTION] Energy insufficient for " + card.cardID);
                        continue;
                    }
                    if (!card.cardPlayable(target)) {
                        System.out.println("[STS-AI-ACTION] Card not playable: " + card.cardID + " (Target: " + (target != null ? target.name : "null") + ")");
                        continue;
                    }
                    System.out.println("[STS-AI-ACTION] 执行 PLAY_CARD 动作: " + card.cardID + " -> " + (target != null ? target.name : "null"));
                    AbstractDungeon.actionManager.cardQueue.add(new CardQueueItem(card, target, card.energyOnUse, true, true));
                    return;
                } else if ("CHOOSE_REWARD".equals(action.getActionType())) {
                    if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD) {
                        int rewardIndex = action.getTargetIndex();
                        if (AbstractDungeon.combatRewardScreen != null && AbstractDungeon.combatRewardScreen.rewards != null
                            && rewardIndex >= 0 && rewardIndex < AbstractDungeon.combatRewardScreen.rewards.size()) {
                             RewardItem item = AbstractDungeon.combatRewardScreen.rewards.get(rewardIndex);
                             if (!item.isDone) {
                                 item.isDone = true;
                                 item.claimReward();
                                 System.out.println("[STS-AI-ACTION] Claimed reward index: " + rewardIndex);
                                 // After claiming, we might need to close screen if all done, but game usually handles it or user sends SKIP
                                 return;
                             }
                        }
                    }
                } else if ("SKIP_REWARD".equals(action.getActionType())) {
                     if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD) {
                         AbstractDungeon.closeCurrentScreen();
                         System.out.println("[STS-AI-ACTION] Skipped rewards.");
                         return;
                     }
                } else if ("CHOOSE_SHOP_CARD".equals(action.getActionType())) {
                     // Implement buying card
                     if (AbstractDungeon.shopScreen != null) {
                         // Need logic to buy card
                     }
                } else if ("CHOOSE_SHOP_POTION".equals(action.getActionType())) {
                     // Implement buying potion
                } else if ("CHOOSE_SHOP_RELIC".equals(action.getActionType())) {
                     // Implement buying relic
                } else if ("PURGE_CARD".equals(action.getActionType())) {
                     // Implement purging
                } else if ("LEAVE_SHOP".equals(action.getActionType())) {
                     if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.SHOP) {
                         AbstractDungeon.overlayMenu.cancelButton.hb.clicked = true;
                         System.out.println("[STS-AI-ACTION] Left shop.");
                         return;
                     }
                } else if ("CHOOSE_REST_OPTION".equals(action.getActionType())) {
                     if (AbstractDungeon.getCurrRoom() instanceof com.megacrit.cardcrawl.rooms.RestRoom) {
                         com.megacrit.cardcrawl.rooms.RestRoom restRoom = (com.megacrit.cardcrawl.rooms.RestRoom) AbstractDungeon.getCurrRoom();
                         if (restRoom.campfireUI != null) {
                              int optionIndex = action.getTargetIndex();
                              // Reflectively access options or simplify? 
                              // For V1, maybe just logging as we don't have public access to options list easily
                              System.out.println("[STS-AI-ACTION] CHOOSE_REST_OPTION index: " + optionIndex + " (Logic pending reflection access)");
                         }
                     }
                } else if ("LEAVE_REST".equals(action.getActionType())) {
                     if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.NONE && AbstractDungeon.getCurrRoom() instanceof com.megacrit.cardcrawl.rooms.RestRoom) {
                         AbstractDungeon.closeCurrentScreen(); // Or proceed
                         System.out.println("[STS-AI-ACTION] Left rest site.");
                         return;
                     }
                } else if ("CHOOSE_MAP_NODE".equals(action.getActionType())) {
                     if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP) {
                         int x = action.getCardIndex(); // Reuse card_index as X
                         int y = action.getTargetIndex(); // Reuse target_index as Y
                         
                         boolean found = false;
                         if (AbstractDungeon.map != null) {
                             for (ArrayList<MapRoomNode> row : AbstractDungeon.map) {
                                 for (MapRoomNode node : row) {
                                     if (node.x == x && node.y == y) {
                                         // Hard transition logic
                                         AbstractDungeon.nextRoom = node;
                                         AbstractDungeon.pathX.add(x);
                                         AbstractDungeon.pathY.add(y);
                                         AbstractDungeon.nextRoomTransitionStart();
                                         if (AbstractDungeon.dungeonMapScreen != null) {
                                             AbstractDungeon.dungeonMapScreen.dismissable = true;
                                             AbstractDungeon.closeCurrentScreen();
                                         }
                                         System.out.println("[STS-AI-ACTION] CHOOSE_MAP_NODE executed: " + x + "," + y);
                                         found = true;
                                         break;
                                     }
                                 }
                                 if (found) break;
                             }
                         }
                         if (!found) {
                             System.out.println("[STS-AI-ACTION] Map node not found: " + x + "," + y);
                         }
                     }
                }
            }
            long now = System.currentTimeMillis();
            boolean isStable = false;
            if (manager.phase == GameActionManager.Phase.WAITING_ON_USER && manager.actions.isEmpty() && !AbstractDungeon.player.isEndingTurn) {
                if (AbstractDungeon.getCurrRoom().phase == AbstractRoom.RoomPhase.COMBAT) {
                    isStable = true;
                } else if (AbstractDungeon.isScreenUp) {
                    // Check various screens for stability
                    if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP ||
                        AbstractDungeon.screen == AbstractDungeon.CurrentScreen.SHOP ||
                        AbstractDungeon.getCurrRoom() instanceof com.megacrit.cardcrawl.rooms.RestRoom ||
                        AbstractDungeon.screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD ||
                        AbstractDungeon.screen == AbstractDungeon.CurrentScreen.DEATH) {
                        isStable = true;
                    }
                } else if (AbstractDungeon.getCurrRoom().phase == AbstractRoom.RoomPhase.COMPLETE) {
                    // Room complete, usually waiting for map or reward
                    isStable = true;
                } else if (AbstractDungeon.getCurrRoom().phase == AbstractRoom.RoomPhase.EVENT) {
                    isStable = true;
                }
            }

            if (now - lastLogTime >= LOG_INTERVAL_MS && isStable) {
                lastLogTime = now;
                PlayerState.Builder playerStateBuilder = PlayerState.newBuilder()
                        .setHp(AbstractDungeon.player.currentHealth)
                        .setMaxHp(AbstractDungeon.player.maxHealth)
                        .setGold(AbstractDungeon.player.gold)
                        .setEnergy(AbstractDungeon.player.energy.energy)
                        .setBlock(AbstractDungeon.player.currentBlock)
                        .setFloor(AbstractDungeon.floorNum)
                        .setStance(AbstractDungeon.player.stance != null ? AbstractDungeon.player.stance.ID : "");

                if (AbstractDungeon.player.powers != null) {
                    for (AbstractPower p : AbstractDungeon.player.powers) {
                        playerStateBuilder.addPowers(PowerState.newBuilder()
                                .setId(p.ID)
                                .setName(p.name)
                                .setAmount(p.amount)
                                .build());
                    }
                }

                if (AbstractDungeon.player.relics != null) {
                    for (AbstractRelic r : AbstractDungeon.player.relics) {
                        playerStateBuilder.addRelics(RelicState.newBuilder()
                                .setId(r.relicId)
                                .setName(r.name)
                                .setCounter(r.counter)
                                .build());
                    }
                }

                if (AbstractDungeon.player.orbs != null) {
                    for (AbstractOrb o : AbstractDungeon.player.orbs) {
                        playerStateBuilder.addOrbs(OrbState.newBuilder()
                                .setId(o.ID)
                                .setName(o.name)
                                .setEvokeAmount(o.evokeAmount)
                                .setPassiveAmount(o.passiveAmount)
                                .build());
                    }
                }

                GameState.Builder gameStateBuilder = GameState.newBuilder()
                        .setPlayer(playerStateBuilder.build());

                // Collect Master Deck
                if (AbstractDungeon.player != null && AbstractDungeon.player.masterDeck != null) {
                    for (AbstractCard c : AbstractDungeon.player.masterDeck.group) {
                         CardState cardState = CardState.newBuilder()
                                .setId(c.cardID == null ? "" : c.cardID)
                                .setName(c.name == null ? "" : c.name)
                                .setCost(c.cost)
                                .setType(c.type != null ? c.type.name() : "")
                                .setDamage(c.baseDamage)
                                .setBlock(c.baseBlock)
                                .setIsUpgraded(c.upgraded)
                                .setMagicNumber(c.magicNumber)
                                .setExhaust(c.exhaust)
                                .build();
                         gameStateBuilder.addMasterDeck(cardState);
                    }
                }

                if (AbstractDungeon.player != null && AbstractDungeon.player.hand != null && AbstractDungeon.player.hand.group != null) {
                    for (AbstractCard c : AbstractDungeon.player.hand.group) {
                        if (c == null) {
                            continue;
                        }
                        c.calculateCardDamage(null);
                        int cost = c.costForTurn;
                        CardState cardState = CardState.newBuilder()
                                .setId(c.cardID == null ? "" : c.cardID)
                                .setName(c.name == null ? "" : c.name)
                                .setCost(cost)
                                .setType(c.type != null ? c.type.name() : "")
                                .setDamage(c.baseDamage)
                                .setTarget(c.target != null ? c.target.name() : "")
                                .setBlock(c.block)
                                .setIsUpgraded(c.upgraded)
                                .setMagicNumber(c.magicNumber)
                                .setExhaust(c.exhaust)
                                .setIsPlayable(c.costForTurn <= AbstractDungeon.player.energy.energy && c.hasEnoughEnergy() && c.cardPlayable(null))
                                .build();
                        gameStateBuilder.addHand(cardState);
                    }
                }

                if (AbstractDungeon.getMonsters() != null && AbstractDungeon.getMonsters().monsters != null) {
                    for (AbstractMonster m : AbstractDungeon.getMonsters().monsters) {
                        if (m == null) {
                            continue;
                        }
                        MonsterState.Builder monsterStateBuilder = MonsterState.newBuilder()
                                .setId(m.id)
                                .setName(m.name)
                                .setHp(m.currentHealth)
                                .setMaxHp(m.maxHealth)
                                .setIntent(m.intent != null ? m.intent.name() : "")
                                .setBlock(m.currentBlock)
                                .setIsGone(m.isEscaping || m.isDead);

                        if (m.powers != null) {
                            for (AbstractPower p : m.powers) {
                                monsterStateBuilder.addPowers(PowerState.newBuilder()
                                        .setId(p.ID)
                                        .setName(p.name)
                                        .setAmount(p.amount)
                                        .build());
                            }
                        }
                        
                        // 注意：move_id 和 specific intent details 需要更深入的 access，这里暂存基础 intent

                        gameStateBuilder.addMonsters(monsterStateBuilder.build());
                    }
                }

                // Collect Potions
                if (AbstractDungeon.player.potions != null) {
                    for (int i = 0; i < AbstractDungeon.player.potions.size(); i++) {
                        AbstractPotion p = AbstractDungeon.player.potions.get(i);
                        gameStateBuilder.addPotions(PotionState.newBuilder()
                                .setId(p.ID)
                                .setName(p.name)
                                .setSlotIndex(i)
                                .setIsUsable(p.isObtained && !p.isThrown) // basic usability check
                                .setCanTarget(p.targetRequired)
                                // .setPrice(p.price) // Price not available on AbstractPotion
                                .build());
                    }
                }

                // Collect Map
                if (AbstractDungeon.map != null && !AbstractDungeon.map.isEmpty()) {
                    DungeonMapState.Builder mapBuilder = DungeonMapState.newBuilder()
                            .setFloor(AbstractDungeon.floorNum)
                            .setBossName(AbstractDungeon.bossKey != null ? AbstractDungeon.bossKey : "");
                    
                    for (ArrayList<MapRoomNode> row : AbstractDungeon.map) {
                        for (MapRoomNode node : row) {
                            if (node == null) continue;
                            
                            MapNodeState.Builder nodeBuilder = MapNodeState.newBuilder()
                                    .setX(node.x)
                                    .setY(node.y)
                                    .setRoomType(node.room != null ? node.room.getClass().getSimpleName() : "Unknown")
                                    .setIsAvailable(true); // Simplified availability check for now
                                    
                            if (node.getEdges() != null) {
                                for (MapEdge edge : node.getEdges()) {
                                    nodeBuilder.addChildren(MapEdgeState.newBuilder()
                                            .setDstX(edge.dstX)
                                            .setDstY(edge.dstY)
                                            .build());
                                }
                            }
                            mapBuilder.addNodes(nodeBuilder.build());
                        }
                    }
                    gameStateBuilder.setMap(mapBuilder.build());
                }

                // Determine Screen Type
                String screenType = "NONE";
                
                // Check Game Over
                if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.DEATH || AbstractDungeon.screen == AbstractDungeon.CurrentScreen.VICTORY) {
                    screenType = AbstractDungeon.screen == AbstractDungeon.CurrentScreen.VICTORY ? "VICTORY" : "GAME_OVER";
                    GameOutcome outcome = GameOutcome.newBuilder()
                            .setIsDone(true)
                            .setVictory(AbstractDungeon.screen == AbstractDungeon.CurrentScreen.VICTORY)
                            .setScore(AbstractDungeon.floorNum * 10) // Simplified score for now
                            .setAscensionLevel(AbstractDungeon.isAscensionMode ? AbstractDungeon.ascensionLevel : 0)
                            .build();
                    gameStateBuilder.setGameOutcome(outcome);
                }
                
                if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.COMBAT_REWARD) {
                    screenType = "REWARD";
                    // Collect Rewards
                    if (AbstractDungeon.combatRewardScreen != null && AbstractDungeon.combatRewardScreen.rewards != null) {
                        RewardState.Builder rewardBuilder = RewardState.newBuilder();
                        for (RewardItem item : AbstractDungeon.combatRewardScreen.rewards) {
                            RewardItemState.Builder itemBuilder = RewardItemState.newBuilder()
                                    .setType(item.type.name())
                                    .setIsClaimed(item.isDone);
                            
                            if (item.type == RewardItem.RewardType.GOLD) {
                                itemBuilder.setAmount(item.goldAmt);
                            } else if (item.type == RewardItem.RewardType.RELIC) {
                                itemBuilder.setId(item.relic != null ? item.relic.relicId : "");
                            } else if (item.type == RewardItem.RewardType.POTION) {
                                itemBuilder.setId(item.potion != null ? item.potion.ID : "");
                            } else if (item.type == RewardItem.RewardType.CARD && item.cards != null) {
                                for (AbstractCard c : item.cards) {
                                     // Re-use card serialization logic or simplify for rewards
                                     itemBuilder.addCards(CardState.newBuilder()
                                            .setId(c.cardID)
                                            .setName(c.name)
                                            .setType(c.type.name())
                                            .build());
                                }
                            }
                            rewardBuilder.addItems(itemBuilder.build());
                        }
                        gameStateBuilder.setReward(rewardBuilder.build());
                    }
                } else if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.MAP) {
                    screenType = "MAP";
                } else if (AbstractDungeon.screen == AbstractDungeon.CurrentScreen.SHOP) {
                    screenType = "SHOP";
                    // Collect Shop
                    if (AbstractDungeon.shopScreen != null) {
                        ShopState.Builder shopBuilder = ShopState.newBuilder()
                                .setCurrentGold(AbstractDungeon.player.gold)
                                .setPurgeCost(ShopScreen.actualPurgeCost);

                        // Reflection for Relics
                        ArrayList<?> relics = getPrivateField(AbstractDungeon.shopScreen, "relics", ArrayList.class);
                        if (relics != null) {
                            for (Object sr : relics) {
                                AbstractRelic r = getPrivateField(sr, "relic", AbstractRelic.class);
                                Integer price = getPrivateField(sr, "price", Integer.class);
                                if (r != null && price != null) {
                                    shopBuilder.addRelics(RelicState.newBuilder()
                                            .setId(r.relicId)
                                            .setName(r.name)
                                            .setPrice(price)
                                            .build());
                                }
                            }
                        }

                        // Reflection for Potions
                        ArrayList<?> potions = getPrivateField(AbstractDungeon.shopScreen, "potions", ArrayList.class);
                        if (potions != null) {
                            for (Object sp : potions) {
                                AbstractPotion p = getPrivateField(sp, "potion", AbstractPotion.class);
                                Integer price = getPrivateField(sp, "price", Integer.class);
                                if (p != null && price != null) {
                                    shopBuilder.addPotions(PotionState.newBuilder()
                                            .setId(p.ID)
                                            .setName(p.name)
                                            .setPrice(price)
                                            .build());
                                }
                            }
                        }

                        // Reflection for Cards
                        ArrayList<?> coloredCards = getPrivateField(AbstractDungeon.shopScreen, "coloredCards", ArrayList.class);
                        ArrayList<?> colorlessCards = getPrivateField(AbstractDungeon.shopScreen, "colorlessCards", ArrayList.class);
                        
                        if (coloredCards != null) {
                            for (Object o : coloredCards) {
                                if (o instanceof AbstractCard) {
                                    AbstractCard c = (AbstractCard) o;
                                    shopBuilder.addCards(CardState.newBuilder()
                                            .setId(c.cardID)
                                            .setName(c.name)
                                            .setPrice(c.price)
                                            .setType(c.type.name())
                                            .build());
                                }
                            }
                        }
                        if (colorlessCards != null) {
                            for (Object o : colorlessCards) {
                                if (o instanceof AbstractCard) {
                                    AbstractCard c = (AbstractCard) o;
                                    shopBuilder.addCards(CardState.newBuilder()
                                            .setId(c.cardID)
                                            .setName(c.name)
                                            .setPrice(c.price)
                                            .setType(c.type.name())
                                            .build());
                                }
                            }
                        }
                        
                        gameStateBuilder.setShop(shopBuilder.build());
                    }
                } else if (AbstractDungeon.isScreenUp && (AbstractDungeon.getCurrRoom() instanceof com.megacrit.cardcrawl.rooms.RestRoom)) {
                      screenType = "REST";
                     // Collect Rest Site
                     if (AbstractDungeon.getCurrRoom() instanceof com.megacrit.cardcrawl.rooms.RestRoom) {
                         com.megacrit.cardcrawl.rooms.RestRoom restRoom = (com.megacrit.cardcrawl.rooms.RestRoom) AbstractDungeon.getCurrRoom();
                         RestSiteState.Builder restBuilder = RestSiteState.newBuilder()
                                 .setHealAmount((int)(AbstractDungeon.player.maxHealth * 0.3f));

                         if (restRoom.campfireUI != null) {
                              ArrayList<?> buttons = getPrivateField(restRoom.campfireUI, "buttons", ArrayList.class);
                              if (buttons != null) {
                                  for (Object opt : buttons) {
                                      String optClass = opt.getClass().getSimpleName();
                                      boolean usable = true;
                                      try {
                                          Field f = opt.getClass().getDeclaredField("usable");
                                          f.setAccessible(true);
                                          usable = f.getBoolean(opt);
                                     } catch (Exception e) {
                                         // If field not found, assume true or check superclass if needed
                                     }
                                     
                                     if (usable) {
                                         if (optClass.contains("RestOption")) restBuilder.setHasRest(true);
                                         else if (optClass.contains("SmithOption")) restBuilder.setHasSmith(true);
                                         else if (optClass.contains("LiftOption")) restBuilder.setHasLift(true);
                                         else if (optClass.contains("TokeOption")) restBuilder.setHasToke(true);
                                         else if (optClass.contains("DigOption")) restBuilder.setHasDig(true);
                                     }
                                 }
                             }
                         }
                         gameStateBuilder.setRestSite(restBuilder.build());
                     }
                } else if (AbstractDungeon.getCurrRoom() != null && AbstractDungeon.getCurrRoom().phase == AbstractRoom.RoomPhase.COMBAT) {
                    screenType = "COMBAT";
                } else if (AbstractDungeon.getCurrRoom() instanceof com.megacrit.cardcrawl.rooms.EventRoom) {
                    screenType = "EVENT";
                    // Collect Event
                    if (AbstractDungeon.getCurrRoom().event != null) {
                         EventState.Builder eventBuilder = EventState.newBuilder()
                                 .setEventId(AbstractDungeon.getCurrRoom().event.getClass().getSimpleName());
                         // Options scraping is complex due to UI structure
                         gameStateBuilder.setEvent(eventBuilder.build());
                    }
                }
                
                gameStateBuilder.setScreenType(screenType);

                GameState gameState = gameStateBuilder.build();
                System.out.println("[STS-AI-PROTO] " + gameState.toString());
                DataOutputStream out = clientOut;
                if (out == null) {
                    return;
                }
                byte[] payload = gameState.toByteArray();
                try {
                    out.writeInt(payload.length);
                    out.write(payload);
                    out.flush();
                } catch (IOException e) {
                    System.out.println("[STS-AI-SOCKET] Send failed: " + e.getMessage());
                    synchronized (StsAIBridge.class) {
                        closeClientQuietly();
                    }
                }
            }
        }
    }
}
