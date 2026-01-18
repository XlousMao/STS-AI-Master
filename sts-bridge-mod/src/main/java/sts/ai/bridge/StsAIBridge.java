package sts.ai.bridge;

import com.evacipated.cardcrawl.modthespire.lib.SpireInitializer;
import com.evacipated.cardcrawl.modthespire.lib.SpirePatch;
import com.megacrit.cardcrawl.dungeons.AbstractDungeon;
import com.megacrit.cardcrawl.monsters.AbstractMonster;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import sts.ai.state.v1.GameAction;
import sts.ai.state.v1.GameState;
import sts.ai.state.v1.MonsterState;
import sts.ai.state.v1.PlayerState;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.BindException;
import java.net.ServerSocket;
import java.net.Socket;

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
                serverSocket = new ServerSocket(9999);
                System.out.println("[STS-AI-SOCKET] Listening on port 9999");
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
                System.out.println("[STS-AI-SOCKET] Port 9999 bind failed (possibly in use): " + e.getMessage());
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
                        System.out.println("[STS-AI-ACTION] Received GameAction: " + action.toString());
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
            long now = System.currentTimeMillis();
            if (now - lastLogTime < LOG_INTERVAL_MS) {
                return;
            }
            lastLogTime = now;
            PlayerState playerState = PlayerState.newBuilder()
                    .setHp(AbstractDungeon.player.currentHealth)
                    .setMaxHp(AbstractDungeon.player.maxHealth)
                    .setGold(AbstractDungeon.player.gold)
                    .setEnergy(AbstractDungeon.player.energy.energy)
                    .setBlock(AbstractDungeon.player.currentBlock)
                    .setFloor(AbstractDungeon.floorNum)
                    .build();

            GameState.Builder gameStateBuilder = GameState.newBuilder()
                    .setPlayer(playerState);

            if (AbstractDungeon.getMonsters() != null && AbstractDungeon.getMonsters().monsters != null) {
                for (AbstractMonster m : AbstractDungeon.getMonsters().monsters) {
                    if (m == null) {
                        continue;
                    }
                    MonsterState monsterState = MonsterState.newBuilder()
                            .setId(m.id)
                            .setName(m.name)
                            .setHp(m.currentHealth)
                            .setMaxHp(m.maxHealth)
                            .setIntent(m.intent != null ? m.intent.name() : "")
                            .setBlock(m.currentBlock)
                            .build();
                    gameStateBuilder.addMonsters(monsterState);
                }
            }

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
