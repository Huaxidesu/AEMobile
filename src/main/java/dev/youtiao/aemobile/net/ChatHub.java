package dev.youtiao.aemobile.net;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.youtiao.aemobile.AEMobile;
import dev.youtiao.aemobile.Config;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.event.ServerChatEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * App ↔ 游戏内公屏的双向聊天通道。
 *
 * <p><b>只在有人订阅时工作</b>：没有 App 连进来时 {@link ChatListener#onChat} 第一行就 return，
 * 不缓冲、不拷贝、不占内存 —— 这是「服务端不缓存」的实现方式。
 *
 * <p><b>不存储</b>：{@link #ring} 只是一个几十条、几十秒的<b>投递缓冲</b>，用来让多条订阅
 * 各按自己的游标取增量、以及短暂断线后补上缺口。服务器重启即清空，不落盘。
 *
 * <p><b>不做命令解析</b>（这是本模块的核心安全约束）：
 * <ul>
 *   <li>绝不调用 {@code ICommandManager.executeCommand} / {@code ServerCommandManager}；</li>
 *   <li>绝不用 {@code IChatComponent.Serializer.jsonToComponent} —— 它会解析
 *       {@code clickEvent} / {@code translate}，是真正的注入面；</li>
 *   <li>只用 {@link ChatComponentText} 字面量拼一行，然后 {@code sendChatMsg} 广播；</li>
 *   <li>用户文本里的 {@code /} 不做任何特殊处理，它就是一个普通字符。</li>
 * </ul>
 *
 * <p>线程模型：{@code onChat} / {@code drainOutbound} 在主线程（服务端 tick）；
 * {@link #send} 在 TCP 读线程（只做过滤、限流、入环、把广播任务塞进队列）；
 * {@link #drain} 在 TCP 推送定时器线程（只读不可变快照）。
 */
public final class ChatHub {

    /** 游戏内玩家公屏 */
    public static final int KIND_GAME = 0;
    /** 手机 App 发出 */
    public static final int KIND_APP = 1;

    /** 投递缓冲条数（不是历史存储） */
    private static final int BUFFER = 128;
    /** 缓冲里的消息超过这个年龄就丢掉 */
    private static final long TTL_MS = 30000L;
    /** 一帧最多带多少条，防止刚订阅时一次吐几百条 */
    private static final int MAX_PER_FRAME = 40;
    /** 广播队列上限：刷屏时丢弃，别让主线程被拖住 */
    private static final int OUTBOX_MAX = 200;

    /** 一条消息（不可变） */
    public static final class Msg {
        public final long seq;
        public final int kind;
        public final String name;
        public final String text;
        public final long ts;

        Msg(long seq, int kind, String name, String text, long ts) {
            this.seq = seq;
            this.kind = kind;
            this.name = name;
            this.text = text;
            this.ts = ts;
        }
    }

    /** 一次取增量的结果：frame 为 null 表示没有新消息，不用发帧 */
    public static final class Batch {
        public final Map<String, Object> frame;
        public final long next;

        Batch(Map<String, Object> frame, long next) {
            this.frame = frame;
            this.next = next;
        }
    }

    /** 服务端这次启动的标识：seq 会随重启归零，App 靠它判断「换了一轮」 */
    private static volatile long boot;
    private static final AtomicLong SEQ = new AtomicLong();
    private static final ArrayDeque<Msg> RING = new ArrayDeque<Msg>();
    private static final AtomicInteger SUBSCRIBERS = new AtomicInteger();
    private static final Queue<Runnable> OUTBOUND = new ConcurrentLinkedQueue<Runnable>();
    /** token → [上次发送时间, 本分钟已用次数, 窗口起始时间] */
    private static final Map<String, long[]> RATE = new ConcurrentHashMap<String, long[]>();

    private ChatHub() {
    }

    /** 由 CommonProxy.serverStarted 调用 */
    public static void start() {
        boot = System.currentTimeMillis();
        SEQ.set(0L);
        synchronized (RING) {
            RING.clear();
        }
        OUTBOUND.clear();
        RATE.clear();
    }

    /** 由 CommonProxy.serverStopping 调用 */
    public static void stop() {
        synchronized (RING) {
            RING.clear();
        }
        OUTBOUND.clear();
        RATE.clear();
    }

    // ------------------------------------------------------------ 订阅计数

    static void addSubscriber() {
        SUBSCRIBERS.incrementAndGet();
    }

    static void removeSubscriber() {
        while (true) {
            int cur = SUBSCRIBERS.get();
            if (cur <= 0) return;
            if (SUBSCRIBERS.compareAndSet(cur, cur - 1)) return;
        }
    }

    static boolean hasSubscribers() {
        return SUBSCRIBERS.get() > 0;
    }

    /** 本次服务端启动的标识（seq 会随重启归零，App 靠它判断要不要重置游标） */
    static long boot() {
        return boot;
    }

    // ------------------------------------------------------------ 收：游戏内公屏

    /** 挂在 {@code MinecraftForge.EVENT_BUS} 上（ServerChatEvent 是 Forge 总线事件，不是 FML 总线） */
    public static final class ChatListener {
        @SubscribeEvent
        public void onChat(ServerChatEvent event) {
            // 没人订阅就什么都不做：不拷贝字符串、不入环、不占内存
            if (!Config.chatEnabled || !hasSubscribers()) return;
            if (event.player == null || event.message == null) return;
            append(KIND_GAME, event.player.getCommandSenderName(), event.message);
        }
    }

    // ------------------------------------------------------------ 发：App → 游戏

    /**
     * App 发一条消息。返回分配到的 seq；参数不合法时抛 {@link ChatException}。
     *
     * <p>在 TCP 读线程调用：只做过滤、限流、入环，真正的广播丢给主线程 tick。
     */
    static long send(String token, String rawName, String rawText) {
        if (!Config.chatEnabled) throw new ChatException("CHAT_DISABLED");
        if (MinecraftServer.getServer() == null) throw new ChatException("SERVER_NOT_READY");

        String name = sanitize(rawName, Config.chatMaxName, true);
        String text = sanitize(rawText, Config.chatMaxText, false);
        if (name.isEmpty()) throw new ChatException("BAD_NAME");
        if (text.isEmpty()) throw new ChatException("BAD_TEXT");

        String limited = rateCheck(token);
        if (limited != null) throw new ChatException(limited);

        long seq = append(KIND_APP, name, text);

        if (OUTBOUND.size() >= OUTBOX_MAX) {
            // 有人刷屏：丢最旧的广播任务，只保留最新的
            OUTBOUND.poll();
            AEMobile.warn("[AEMobile] 聊天广播队列已满，丢弃最早的一条");
        }
        OUTBOUND.add(new Runnable() {
            @Override
            public void run() {
                broadcast(name, text);
            }
        });
        return seq;
    }

    /** 参数错误 / 被限流；message 直接作为 RES 的 m 回给 App */
    static final class ChatException extends RuntimeException {
        ChatException(String message) {
            super(message);
        }
    }

    /**
     * 主线程广播。**这里只拼字面量**，不做任何解析 ——
     * 文本里带 {@code /stop}、{@code §c}、{@code [系统]} 都只会原样显示。
     */
    private static void broadcast(String name, String text) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return;

        ChatComponentText head = new ChatComponentText("[AEMobile]");
        head.getChatStyle().setColor(EnumChatFormatting.GRAY);
        ChatComponentText who = new ChatComponentText("[" + name + "] ");
        who.getChatStyle().setColor(EnumChatFormatting.AQUA);
        ChatComponentText body = new ChatComponentText(text);
        head.appendSibling(who).appendSibling(body);

        server.getConfigurationManager().sendChatMsg((IChatComponent) head);
        // 服务端日志留档，便于事后查水表（聊天本身不存储）
        AEMobile.info("[AEMobile][聊天] " + name + ": " + text);
    }

    /** 每 tick 最多广播这么多条，防止一次刷屏把 tick 拖长 */
    public static final class TickHandler {
        @SubscribeEvent
        public void onTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            int budget = Math.max(1, Config.chatBroadcastPerTick);
            while (budget-- > 0) {
                Runnable task = OUTBOUND.poll();
                if (task == null) break;
                try {
                    task.run();
                } catch (Throwable t) {
                    AEMobile.debug("[AEMobile] 聊天广播失败：" + t);
                }
            }
        }
    }

    // ------------------------------------------------------------ 取：推送增量

    /**
     * 取 {@code seq > since} 的消息。没有新消息返回 {@code frame == null}（调用方不发帧）。
     *
     * @param since 客户端上次收到的最大 seq
     * @param max   一帧最多几条
     */
    static Batch drain(long since, int max) {
        long next = since;
        List<Object> rows = null;
        boolean gap = false;
        synchronized (RING) {
            long now = System.currentTimeMillis();
            // 顺手清掉过期的（只在有人取的时候清，不额外起定时器）
            while (!RING.isEmpty() && now - RING.peekFirst().ts > TTL_MS) RING.pollFirst();
            if (RING.isEmpty()) return new Batch(null, since);

            long oldest = RING.peekFirst().seq;
            if (since > 0 && since < oldest - 1) gap = true;
            if (since > SEQ.get()) {
                // App 记的 seq 比服务端还大：服务端重启过（或 App 缓存来自上一轮），从头给
                since = 0;
                gap = true;
            }
            rows = new ArrayList<Object>();
            for (Msg m : RING) {
                if (m.seq <= since) continue;
                rows.add(new Object[]{m.seq, m.kind, m.name, m.text, m.ts});
                next = m.seq;
                if (rows.size() >= max) break;
            }
            if (rows.isEmpty()) return new Batch(null, since);
        }

        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("t", "c");
        frame.put("boot", boot);
        frame.put("seq", next);
        if (gap) frame.put("gap", 1);
        frame.put("m", rows);
        return new Batch(frame, next);
    }

    // ------------------------------------------------------------ 内部

    private static long append(int kind, String name, String text) {
        long seq = SEQ.incrementAndGet();
        Msg msg = new Msg(seq, kind, name, text, System.currentTimeMillis());
        synchronized (RING) {
            RING.addLast(msg);
            while (RING.size() > BUFFER) RING.pollFirst();
        }
        return seq;
    }

    /**
     * 文本净化。双向都走这里：游戏内聊天进 App、App 消息进游戏。
     *
     * <ul>
     *   <li>删 {@code §} 及其后面的格式码（否则客户端会被染色、改字体）；</li>
     *   <li>删 C0/C1 控制字符，换行/制表转空格（防止伪造多行）；</li>
     *   <li>删双向覆盖符 U+202A–202E / U+2066–2069 / U+200B–200F / U+FEFF（防止视觉伪装）；</li>
     *   <li>名字额外删 {@code [} {@code ]}（防止伪装成别人的前缀）；</li>
     *   <li>按码点截断到 {@code max}。</li>
     * </ul>
     */
    static String sanitize(String raw, int max, boolean isName) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder(Math.min(raw.length(), max + 8));
        int count = 0;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0xA7) { // '§'：连同后面的格式码一起丢掉
                if (i < raw.length()) i += Character.charCount(raw.codePointAt(i));
                continue;
            }
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                b.append(' ');
                count++;
                continue;
            }
            if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F)) continue;
            if (cp == 0xFEFF || (cp >= 0x200B && cp <= 0x200F) || (cp >= 0x202A && cp <= 0x202E)
                    || (cp >= 0x2066 && cp <= 0x2069)) continue;
            if (isName && (cp == '[' || cp == ']')) continue;
            b.appendCodePoint(cp);
            if (++count >= max) break;
        }
        return b.toString().trim();
    }

    /** 单 token 限速：两次之间至少 {@code chatMinIntervalMs}，每分钟最多 {@code chatPerMinute} 条 */
    private static String rateCheck(String token) {
        long now = System.currentTimeMillis();
        long[] st = RATE.get(token);
        if (st == null) {
            st = new long[]{0L, 0L, now};
            RATE.put(token, st);
        }
        synchronized (st) {
            if (now - st[2] >= 60000L) {
                st[2] = now;
                st[1] = 0;
            }
            if (st[1] >= Config.chatPerMinute) {
                return "RATE_LIMITED:每分钟最多 " + Config.chatPerMinute + " 条";
            }
            if (now - st[0] < Config.chatMinIntervalMs) {
                return "RATE_LIMITED:发得太快了，请等一下";
            }
            st[0] = now;
            st[1]++;
        }
        return null;
    }
}
