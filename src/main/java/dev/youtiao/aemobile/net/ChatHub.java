package dev.youtiao.aemobile.net;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import dev.youtiao.aemobile.AEMobile;
import dev.youtiao.aemobile.Config;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.server.MinecraftServer;
import net.minecraft.stats.Achievement;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.player.AchievementEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
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
    /** 玩家加入服务器（只进 App 流，不广播游戏公屏） */
    public static final int KIND_JOIN = 2;
    /** 玩家离开服务器 */
    public static final int KIND_LEAVE = 3;
    /** 服务器/插件广播的聊天（公告、/say、AutoMessage 等） */
    public static final int KIND_SERVER = 4;
    /** AllMusic 的点歌/播放消息，App 用唱片图标 */
    public static final int KIND_MUSIC = 5;
    /** 玩家完成成就 */
    public static final int KIND_ACHIEVEMENT = 6;

    /** 投递缓冲条数（不是历史存储） */
    private static final int BUFFER = 128;
    /** 缓冲里的消息超过这个年龄就丢掉 */
    private static final long TTL_MS = 30000L;
    /** 一帧最多带多少条，防止刚订阅时一次吐几百条 */
    private static final int MAX_PER_FRAME = 40;
    /** 广播队列上限：刷屏时丢弃，别让主线程被拖住 */
    private static final int OUTBOX_MAX = 200;
    /** 服务器广播去重窗口 */
    private static final long SERVER_DEDUPE_MS = 800L;
    /** 玩家聊天 / 进出的广播回显去重窗口 */
    private static final long PLAYER_DEDUPE_MS = 2500L;
    private static final int RECENT_MAX = 8;

    /** 这个线程正在广播 App 消息：不要把它当成服务器聊天再抓一遍 */
    private static final ThreadLocal<Boolean> APP_BROADCAST = new ThreadLocal<Boolean>();
    /**
     * 已经由 {@link #acceptFormatted} 处理过、马上要被 {@code sendChatMsg} 广播的组件。
     *
     * <p>GTNHChat 在广播前把消息和元数据一起交过来，随后才调 {@code sendChatMsg}；
     * 那里会再走一遍 {@link #captureServerChat}。按对象身份认下这一次广播，避免重复入队。
     * 只在同一个线程、紧邻的两步之间有效，所以用 ThreadLocal 而不是全局表。
     */
    private static final ThreadLocal<IChatComponent> BRIDGED = new ThreadLocal<IChatComponent>();
    /** 最近玩家聊天文本，用来去掉 MiaoChat/原版对同一条聊天的二次广播 */
    private static final ArrayDeque<String[]> RECENT_PLAYER_CHAT = new ArrayDeque<String[]>();
    /** 最近加入/退出的玩家名，用来去掉原版进出提示的二次广播 */
    private static final ArrayDeque<String[]> RECENT_PRESENCE = new ArrayDeque<String[]>();
    private static volatile String lastServerText = "";
    private static volatile long lastServerAt = 0L;
    /** 最近用过的 App 昵称（给 @ 补全用） */
    private static final Set<String> APP_NAMES = java.util.Collections.synchronizedSet(new LinkedHashSet<String>());
    /** 成就去重：`玩家|成就名` → 上次时间 */
    private static final Map<String, Long> RECENT_ACH = new ConcurrentHashMap<String, Long>();
    private static final long ACH_DEDUPE_MS = 5000L;
    /** 命令回显：`?/spawn`、`<name> /home` 这类，只有命令真的存在才过滤 */
    private static final java.util.regex.Pattern CMD_ECHO = java.util.regex.Pattern.compile(
            "(?i)^\\s*(?:\\[[^\\]]*\\]\\s*)*(?:<[^>]*>\\s*:?\\s*)?\\??/([A-Za-z0-9_\\-:]+)");

    /** 一条消息（不可变） */
    public static final class Msg {
        public final long seq;
        public final int kind;
        public final String name;
        public final String text;
        public final long ts;
        /** 来源服务器显示名；空串表示本服 */
        public final String server;
        /** 结构化附件（JSON 数组字符串）；空串表示没有 */
        public final String att;
        /** 玩家所在队伍名；空串表示没有队伍 */
        public final String team;
        /** 队伍颜色码字符（{@code d} / {@code b} …）；空串表示没有队伍或没装 ServerUtilities */
        public final String teamColor;
        /**
         * 玩家所在世界的显示名（GTNHChat 已经按 Multiverse 别名 / config 表换好中文）。
         *
         * <p>空串表示不知道：服务器广播、App 消息、老版本 GTNHChat 都没有这个字段。
         * App 拿不到就只显示服务器和队伍，不画世界标签。
         */
        public final String world;

        Msg(long seq, int kind, String name, String text, long ts, String server, String att, String team,
            String teamColor, String world) {
            this.seq = seq;
            this.kind = kind;
            this.name = name;
            this.text = text;
            this.ts = ts;
            this.server = server == null ? "" : server;
            this.att = att == null ? "" : att;
            this.team = team == null ? "" : team;
            this.teamColor = teamColor == null ? "" : teamColor;
            this.world = world == null ? "" : world;
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
        APP_NAMES.clear();
        RECENT_ACH.clear();
    }

    /** 由 CommonProxy.serverStopping 调用 */
    public static void stop() {
        synchronized (RING) {
            RING.clear();
        }
        OUTBOUND.clear();
        RATE.clear();
        APP_NAMES.clear();
        RECENT_ACH.clear();
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

    /** 挂在 {@code MinecraftForge.EVENT_BUS} 上（ServerChatEvent / AchievementEvent 都是 Forge 总线） */
    public static final class ChatListener {
        @SubscribeEvent
        public void onChat(ServerChatEvent event) {
            // 没人订阅就什么都不做：不拷贝字符串、不入环、不占内存
            if (!Config.chatEnabled || !hasSubscribers()) return;
            if (event.player == null || event.message == null) return;
            String text = ChatFormat.sanitizeColored(event.message, Config.chatMaxText, false);
            append(KIND_GAME, event.player.getCommandSenderName(), text);
            // 记下来：原版/插件可能紧接着把同一条聊天再广播一次
            notePlayerChat(ChatFormat.plain(text));
        }

        @SubscribeEvent
        public void onAchievement(AchievementEvent event) {
            if (!Config.chatEnabled || !Config.chatAchievements || !hasSubscribers()) return;
            if (event.entityPlayer == null || event.achievement == null) return;
            // 装了 GTNHChat 时成就公告由它统一发（它走客户端上报，覆盖范围比服务端的
            // AchievementEvent 更全，还带跨服转发）。两边都发的话手机端会出现两条。
            if (gtnhChatPresent()) return;
            String who = event.entityPlayer.getCommandSenderName();
            // 成就名走 StatBase.func_150951_e()（1.7.10 只有这一个取法）；服务端语言是 en_US，
            // 拿不到中文。拿不到名字时退到 statId 并去掉 achievement. 前缀。
            String name = "";
            try {
                IChatComponent comp = event.achievement.func_150951_e();
                if (comp != null) name = comp.getUnformattedText();
            } catch (Throwable ignored) {
            }
            if (name == null || name.isEmpty()) {
                String id = event.achievement.statId;
                name = id == null ? "" : id.replaceFirst("^achievement\\.", "");
            }
            if (name == null || name.isEmpty()) return;
            // 同一个成就可能被 addStat 触发多次（插件补发 / 重登），5 秒内只记一次
            if (!markAchievement(who + "|" + name)) return;
            append(KIND_ACHIEVEMENT, who, name);
        }
    }

    /**
     * 玩家进出：只进 App 聊天流，不往游戏公屏广播（游戏本来就有自己的提示）。
     * 挂在 FML 总线上；没人订阅或配置关掉时第一行就 return，零开销。
     */
    public static final class PlayerEvents {
        @SubscribeEvent
        public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
            if (!Config.chatEnabled || !Config.chatJoinLeave || !hasSubscribers()) return;
            if (event.player == null) return;
            String name = event.player.getCommandSenderName();
            dev.youtiao.aemobile.net.MonitorRegistry.rememberName(event.player);
            append(KIND_JOIN, name, "");
            notePresence(name);
        }

        @SubscribeEvent
        public void onLeave(PlayerEvent.PlayerLoggedOutEvent event) {
            if (!Config.chatEnabled || !Config.chatJoinLeave || !hasSubscribers()) return;
            if (event.player == null) return;
            String name = event.player.getCommandSenderName();
            append(KIND_LEAVE, name, "");
            notePresence(name);
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

        String name = ChatFormat.sanitizeName(rawName, Config.chatMaxName);
        String text = ChatFormat.sanitizeColored(rawText, Config.chatMaxText, false);
        if (name.isEmpty()) throw new ChatException("BAD_NAME");
        if (text.isEmpty()) throw new ChatException("BAD_TEXT");

        String limited = rateCheck(token);
        if (limited != null) throw new ChatException(limited);

        APP_NAMES.add(name);
        if (APP_NAMES.size() > 64) {
            synchronized (APP_NAMES) {
                java.util.Iterator<String> it = APP_NAMES.iterator();
                if (it.hasNext()) {
                    it.next();
                    it.remove();
                }
            }
        }
        long seq = append(KIND_APP, name, text, GtnChatBridge.serverDisplay(), "", "", "", "");

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
     * 主线程广播。只生成 {@link ChatComponentText} 字面量；
     * {@code §}/{@code &} 颜色码会被安全解析（§k 丢弃），{@code @玩家} 用金色高亮。
     */
    private static void broadcast(String name, String text) {
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) return;

        ChatComponentText head = new ChatComponentText("[AEMobile]");
        head.getChatStyle().setColor(EnumChatFormatting.GRAY);
        ChatComponentText who = new ChatComponentText("[" + name + "] ");
        who.getChatStyle().setColor(EnumChatFormatting.AQUA);
        head.appendSibling(who);
        ChatFormat.append(head, text, EnumChatFormatting.GOLD);

        // 这条广播是 App 自己发的，captureServerChat 不要再当成服务器聊天收一遍
        APP_BROADCAST.set(Boolean.TRUE);
        try {
            server.getConfigurationManager().sendChatMsg((IChatComponent) head);
        } finally {
            APP_BROADCAST.remove();
        }
        // 群组服：手机端的消息不经过 ServerChatEvent，GTNHChat 的跨服转发收不到，
        // 这里补一次。没装 GTNHChat 时是空操作，不会多花任何东西。
        GtnChatBridge.forward(name, ChatFormat.plain(text));
        // 服务端日志留档，便于事后查水表（聊天本身不存储）
        AEMobile.info("[AEMobile][聊天] " + name + ": " + ChatFormat.plain(text));
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
     * @param includeEvents 是否把「玩家加入/退出」也发出去（老 App 不带这个参数就不发）
     */
    static Batch drain(long since, int max, boolean includeEvents) {
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
                // 游标必须越过被过滤掉的事件，否则关掉进出提示后每帧都从同一条开始扫
                next = m.seq;
                if (!includeEvents && m.kind >= KIND_JOIN) continue;
                // 6-10 位是可选扩展：来源服务器、结构化附件、队伍名、队伍颜色、世界名。
                // 老 App 只读前 5 位，多出来的会被忽略
                rows.add(new Object[]{m.seq, m.kind, m.name, m.text, m.ts, m.server, m.att, m.team,
                    m.teamColor, m.world});
                if (rows.size() >= max) break;
            }
            if (rows.isEmpty()) return new Batch(null, next);
        }

        Map<String, Object> frame = new LinkedHashMap<String, Object>();
        frame.put("t", "c");
        frame.put("boot", boot);
        frame.put("seq", next);
        if (gap) frame.put("gap", 1);
        frame.put("m", rows);
        return new Batch(frame, next);
    }

    // ------------------------------------------------------------ 收：服务器广播

    /**
     * GTNHChat 在广播前把消息连同元数据交过来（反射调用，见 GTNHChat 的 {@code bridge/MobileBridge}）。
     *
     * <p>不经过这里的话，{@link #captureServerChat} 只能看到「已经格式化好的整行」：
     * 玩家名、来源服务器都揉进文本里，手机端只能一律显示成「服务器」。
     *
     * <p>调用方约定：本方法返回后必须紧接着用同一个组件对象调 {@code sendChatMsg}。
     * 这里把它记进 {@link #BRIDGED}，那次广播会被认下来、不再重复入队。
     *
     * @param player 说话的人
     * @param world  世界显示名（GTNHChat 已换成中文别名）；App 用它画「世界」标签
     * @param raw    玩家原始输入，用于去重
     * @param origin 来源服务器显示名
     * @param remote 是否是别的服务器转发过来的；为 true 时手机端才标注来源
     * @param kind   {@link #KIND_GAME} / {@link #KIND_SERVER} / {@link #KIND_ACHIEVEMENT}
     */
    public static void acceptFormatted(IChatComponent component, String player, String world,
                                       String raw, String origin, boolean remote, int kind) {
        acceptFormatted(component, player, world, raw, origin, remote, kind, "");
    }

    /**
     * 同上，多带一个队伍名（GTNHChat 3.4.0 之后）。
     *
     * <p>保留 7 参重载是为了老版本 GTNHChat：它反射找的是 7 参签名，
     * 少了这个重载桥会整个停用。
     */
    public static void acceptFormatted(IChatComponent component, String player, String world,
                                       String raw, String origin, boolean remote, int kind,
                                       String team) {
        acceptFormatted(component, player, world, raw, origin, remote, kind, team, "");
    }

    /**
     * 同上，多带一个队伍颜色（GTNHChat 3.4.1 之后）。
     *
     * <p>保留 8 参重载是为了老版本 GTNHChat：它反射找的是 8 参签名，
     * 少了这个重载桥会整个停用。
     *
     * @param teamColor 队伍颜色码字符（{@code d} / {@code b} …），手机端拿它给名字上色
     */
    public static void acceptFormatted(IChatComponent component, String player, String world,
                                       String raw, String origin, boolean remote, int kind,
                                       String team, String teamColor) {
        if (!Config.chatEnabled || !hasSubscribers() || component == null) return;

        // 成就公告的组件是整行「玩家 达成了成就 xxx」，而手机端的系统行会自己拼名字和图标。
        // 这里只要成就名（桥把它放在 raw 里），否则会显示成「Steve 完成了成就 Steve 达成了成就 xxx」。
        if (kind == KIND_ACHIEVEMENT) {
            BRIDGED.set(component);
            if (raw == null || raw.isEmpty()) return;
            String who = ChatFormat.sanitizeName(player, Config.chatMaxName);
            String ach = ChatFormat.sanitizeColored(raw, Config.chatMaxText, false);
            if (ach.isEmpty()) return;
            append(kind, who.isEmpty() ? "服务器" : who, ach, "", "", "");
            return;
        }

        // 跨服过来的手机端消息：正文在 raw 里，组件是渲染给游戏内的整行
        // （`[天空树][AEMobile][名字] 正文`）。直接摊平组件的话，App 气泡会变成
        // 「名字: [天空树][AEMobile][名字] 正文」—— 前缀重复两遍。
        if (kind == KIND_APP && raw != null && !raw.isEmpty()) {
            BRIDGED.set(component);
            String who = ChatFormat.sanitizeName(player, Config.chatMaxName);
            String body = ChatFormat.sanitizeColored(raw, Config.chatMaxText, false);
            if (body.isEmpty()) return;
            String from = ChatFormat.sanitizeName(origin, Config.chatMaxName);
            if (from.isEmpty()) from = GtnChatBridge.serverDisplay();
            append(KIND_APP, who.isEmpty() ? "手机端" : who, body, from, "", "",
                "", ChatFormat.sanitizeName(world, Config.chatMaxName));
            return;
        }

        ChatRich.Result rich = ChatRich.parse(component);

        String name = ChatFormat.sanitizeName(player, Config.chatMaxName);
        if (name.isEmpty()) name = "服务器";
        // 来源服始终填：本服消息也标上本服显示名。
        // 3.4.0 时这里写的是 `remote ? origin : ""`（本服不标来源，免得每条都挂前缀），
        // 但手机端的来源标签现在藏在头像后面、点开才看，那个「免得挂前缀」的理由不成立了，
        // 而玩家点开却看不到服务器反而更像 bug。
        String server = ChatFormat.sanitizeName(origin, Config.chatMaxName);
        if (server.isEmpty()) server = GtnChatBridge.serverDisplay();
        String teamName = ChatFormat.sanitizeName(team, Config.chatMaxName);
        // 世界显示名：GTNHChat 已经换成了中文别名（`华溪岛` / `家园 #12`）。
        // App 用它画「世界」标签；拿不到就空串，App 那边整段跳过。
        String worldName = ChatFormat.sanitizeName(world, Config.chatMaxName);
        // 颜色只有一个码字符，不走 sanitizeName —— 那个函数是给名字用的，
        // 对 1 字符的输入会有意外结果（2026-11-01 排查队伍颜色时踩到）
        String color = teamColor == null ? "" : teamColor.trim();
        if (color.length() != 1) color = "";

        BRIDGED.set(component);
        AEMobile.debug("[AEMobile] chat kind=" + kind + " name=[" + name + "] team=[" + teamName
            + "] color=[" + color + "]");

        // 玩家聊天：正文只用玩家打的那几个字。
        // `component` 是 GTNHChat 渲染好的整行（`[天空树][华溪岛][华溪岛]Steve: 你好`），
        // 摊平当正文会变成「Steve: [天空树][华溪岛][华溪岛]Steve: 你好」—— 前缀重复一遍。
        // 服务器/队伍/名字在 App 里是独立标签，正文不该再带这些。
        if (kind == KIND_GAME && raw != null && !raw.isEmpty()) {
            // BQ 的任务分享：玩家「打的那几个字」就是 betterquesting.msg.sharequest:<id>，
            // 原样当正文会在手机上显示成一串乱码。正文留空，App 按附件出「分享了任务」。
            String body = ChatRich.isQuestShare(raw)
                    ? ""
                    : ChatFormat.sanitizeColored(raw, Config.chatMaxText, false);
            if (body.isEmpty() && rich.att.isEmpty()) return;
            notePlayerChat(ChatFormat.plain(raw));
            append(kind, name, body, server, rich.att, teamName, color, worldName);
            return;
        }

        String text = ChatFormat.sanitizeColored(rich.text, Math.max(Config.chatMaxText * 2, 512), false);
        if (text.isEmpty() && rich.att.isEmpty()) return;
        append(kind, name, text, server, rich.att, teamName, color, worldName);
    }

    /**
     * 服务器/插件广播的聊天（公告、/say、AutoMessage 等）。
     *
     * <p>由 {@code MixinServerConfigurationManager} 在 {@code sendChatMsg} 头部调用。
     * 主线程执行；只做过滤和入环，真正发帧还是 ticker 线程。
     *
     * <p>过滤：
     * <ul>
     *   <li>GTNHChat 已经通过 {@link #acceptFormatted} 交过来的那条 —— 按对象身份认下；</li>
     *   <li>App 自己发的广播（{@link #APP_BROADCAST}）—— 它已经以 kind 1 入环；</li>
     *   <li>玩家聊天/进出的二次广播 —— 已有 kind 0/2/3，靠最近的文本/名字去重；</li>
     *   <li>可执行命令的回显（{@code ?/spawn}、{@code <name> /home}）—— 命令存在于服务端才过滤，
     *       未知命令（不可执行）照常显示；</li>
     *   <li>800ms 内完全相同的重复广播。</li>
     * </ul>
     */
    public static void captureServerChat(IChatComponent component) {
        if (!Config.chatEnabled || !hasSubscribers() || component == null) return;

        IChatComponent bridged = BRIDGED.get();
        if (bridged != null) {
            // 不管是不是同一条都清掉：桥调用后没有广播的情况不该影响下一条消息
            BRIDGED.remove();
            if (bridged == component) return;
        }

        if (Boolean.TRUE.equals(APP_BROADCAST.get())) return;

        if (component instanceof ChatComponentTranslation) {
            String key = ((ChatComponentTranslation) component).getKey();
            if (key != null) {
                // 玩家聊天已有 kind 0；进/出已有 kind 2/3，别重复
                if ("chat.type.text".equals(key)
                        || "multiplayer.player.joined".equals(key)
                        || "multiplayer.player.left".equals(key)) {
                    return;
                }
            }
        }

        ChatRich.Result rich = ChatRich.parse(component);
        String plain = ChatFormat.plain(rich.text);
        if (plain.isEmpty() && rich.att.isEmpty()) return;
        if (containsRecent(RECENT_PLAYER_CHAT, plain, PLAYER_DEDUPE_MS)) return;
        if (containsRecent(RECENT_PRESENCE, plain, PLAYER_DEDUPE_MS)) return;
        if (isExecutableCommandEcho(plain)) return;

        long now = System.currentTimeMillis();
        synchronized (ChatHub.class) {
            if (plain.equals(lastServerText) && now - lastServerAt < SERVER_DEDUPE_MS) return;
            lastServerText = plain;
            lastServerAt = now;
        }

        String text = ChatFormat.sanitizeColored(rich.text,
                Math.max(Config.chatMaxText * 2, 512), false);
        if (text.isEmpty()) text = plain;

        // AllMusic 单独一类，App 用唱片图标；只把「正在播放」这类公开消息放进公共聊天，
        // 搜索/帮助/队列结果由 AEMobileBridge 私有返回，不能广播给所有人。
        if (isMusicText(plain)) {
            if (!isMusicPublicText(plain)) return;
            String body = stripMusicPrefix(text);
            append(KIND_MUSIC, "AllMusic", body.isEmpty() ? text : body);
            return;
        }
        // NEI 物品链接：消息是「玩家 分享了 [物品]」，名字在 translation 的第一个参数里。
        // 不取出来的话手机端一律显示成「服务器 分享了 xxx」，看不出是谁分享的。
        // 用 KIND_GAME 而不是 KIND_SERVER：App 那边玩家消息是气泡 + 头像，服务器广播是
        // 喇叭图标 + 灰名，物品卡片挂在玩家气泡里才像「这个人分享了东西」。
        //
        // 正文留空（ChatRich 把 NEI 那句模板整个丢了）：服务端只发「谁 + 什么物品」，
        // 「分享了物品」这句文案由 App 按附件类型生成 —— 服务端不做翻译。
        String who = neiSharer(component);
        if (!who.isEmpty()) {
            String[] team = neiTeam(who);
            // 来源服和世界也要填：手机端的分组身份带 world，不填的话这条会自己另起一组
            // （头像点不开、也不和这个人刚说的话并在一起）。世界名借 GTNHChat 的
            // ChatFormat.worldName 算，保证和普通聊天用的是同一套名字。
            append(KIND_GAME, who, text, GtnChatBridge.serverDisplay(), rich.att, team[0], team[1],
                neiWorld(who));
            return;
        }
        append(KIND_SERVER, "服务器", text, "", rich.att);
    }

    /**
     * 分享物品的玩家所在队伍，返回 {@code [名字, 颜色码]}。
     *
     * <p>NEI 的广播里只有组件，没有玩家对象，所以按名字反查在线玩家，再借 GTNHChat 的
     * {@code TeamInfo} 取队伍 —— 那套反射（ServerUtilities 的 title/简介/职位/颜色）
     * 已经在 GTNHChat 里写好了，这里不再抄一遍。没装 GTNHChat 或玩家不在线时返回空数组，
     * 手机端就不显示队伍标签、名字用默认色。
     */
    private static String[] neiTeam(String playerName) {
        String[] empty = { "", "" };
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null || server.getConfigurationManager() == null) return empty;
            Object player = server.getConfigurationManager().func_152612_a(playerName);
            if (!(player instanceof EntityPlayerMP)) return empty;
            Class<?> teamInfo = Class.forName("com.huaxidesu.gtnchat.chat.TeamInfo");
            Object info = teamInfo.getMethod("of", EntityPlayerMP.class).invoke(null, player);
            if (info == null) return empty;
            Object name = info.getClass().getField("name").get(info);
            Object color = info.getClass().getField("color").get(info);
            return new String[] {
                name == null ? "" : ChatFormat.sanitizeName(String.valueOf(name), Config.chatMaxName),
                color == null ? "" : ChatFormat.sanitizeName(String.valueOf(color), 1) };
        } catch (Throwable t) {
            return empty;
        }
    }

    /**
     * 分享物品的玩家所在世界的显示名。
     *
     * <p>和 {@link #neiTeam} 同一个套路：NEI 的广播里没有玩家对象，按名字反查在线玩家，
     * 再借 GTNHChat 的 {@code ChatFormat.worldName(EntityPlayerMP)} 算 —— 那套别名表
     * （MV alias / config）已经在 GTNHChat 里了，这边自己再查一遍只会出现两套命名。
     *
     * <p>为什么非要填：手机端的分组身份带 world，留空的话物品分享会自己另起一组，
     * 既点不开头像，也不和这个人刚说的话并在一起。
     */
    private static String neiWorld(String playerName) {
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null || server.getConfigurationManager() == null) return "";
            Object player = server.getConfigurationManager().func_152612_a(playerName);
            if (!(player instanceof EntityPlayerMP)) return "";
            Class<?> fmt = Class.forName("com.huaxidesu.gtnchat.chat.ChatFormat");
            Object name = fmt.getMethod("worldName", EntityPlayerMP.class).invoke(null, player);
            return name == null ? "" : ChatFormat.sanitizeName(String.valueOf(name), Config.chatMaxName);
        } catch (Throwable t) {
            return "";
        }
    }

    /** NEI 物品链接的分享者名字；不是 NEI 消息或取不到时返回空串 */
    private static String neiSharer(IChatComponent component) {
        if (!(component instanceof ChatComponentTranslation)) return "";
        try {
            ChatComponentTranslation tr = (ChatComponentTranslation) component;
            String key = tr.getKey();
            if (key == null || !key.startsWith("nei.chat.item_link")) return "";
            Object[] args = tr.getFormatArgs();
            if (args == null || args.length == 0 || args[0] == null) return "";
            // 正常是 IChatComponent；Thermos 下 getDisplayName() 偶尔给的是 String，两种都收
            Object first = args[0];
            String s = first instanceof IChatComponent
                ? ((IChatComponent) first).getUnformattedText()
                : String.valueOf(first);
            return s == null ? "" : ChatFormat.sanitizeName(s, Config.chatMaxName);
        } catch (Throwable t) {
            return "";
        }
    }

    /** 最近用过的 App 昵称（给 App 的 @ 补全用） */
    public static List<String> recentAppNames() {
        synchronized (APP_NAMES) {
            return new ArrayList<String>(APP_NAMES);
        }
    }

    /**
     * 调试注入：直接往聊天流里塞一条，**绕过事件源**。
     *
     * <p>本机测试服没有真实玩家时，`ServerChatEvent` / `AchievementEvent` / 进出事件都走不到，
     * 手机端就没法验证。这个入口让 {@code /aemobile debug} 直接造一条消息，
     * App 侧收到的东西和真玩家发的一模一样（同样的 kind、同样的颜色码处理）。
     * 只用于测试，不进任何正式链路。
     */
    public static long inject(int kind, String rawName, String rawText) {
        if (!Config.chatEnabled) return -1L;
        String name = ChatFormat.sanitizeName(rawName, Config.chatMaxName);
        String text = ChatFormat.sanitizeColored(rawText, Config.chatMaxText, false);
        if (name.isEmpty()) name = "Tester";
        return append(kind, name, text);
    }

    /**
     * 注：颜色码由 {@link ChatRich} 自己按组件树生成，不走 {@code IChatComponent.getFormattedText()}。
     * 那个方法在 reobf 后运行时找不到（{@code NoSuchMethodError: func_150254_d}），
     * 一调就把整条服务器广播链路打断 —— 这是 2026-10-30 踩过的坑。
     */

    /** GTNHChat 是否在场（只探测一次；探测失败不影响任何功能） */
    private static Boolean GTNHCHAT;

    private static boolean gtnhChatPresent() {
        if (GTNHCHAT == null) {
            boolean present;
            try {
                Class.forName("com.huaxidesu.gtnchat.GTNHChat");
                present = true;
            } catch (Throwable t) {
                present = false;
            }
            GTNHCHAT = Boolean.valueOf(present);
        }
        return GTNHCHAT.booleanValue();
    }

    /** 文本是否带配置里的 AllMusic 前缀（[AllMusic3] / [肥猫电台] ...） */
    public static boolean isMusicText(String plain) {
        if (plain == null) return false;
        for (String p : Config.musicPrefixes) {
            if (p != null && !p.isEmpty() && plain.contains(p)) return true;
        }
        return false;
    }

    /** 是否是应该进公共聊天流的 AllMusic 消息（如「正在播放」），避免广播别人的搜索/帮助结果 */
    public static boolean isMusicPublicText(String plain) {
        if (plain == null) return false;
        if (Config.musicPublicMarkers == null || Config.musicPublicMarkers.length == 0) return true;
        for (String marker : Config.musicPublicMarkers) {
            if (marker != null && !marker.isEmpty() && plain.contains(marker)) return true;
        }
        return false;
    }

    /** 去掉所有配置里的 AllMusic 前缀，并 trim */
    static String stripMusicPrefix(String plain) {
        if (plain == null) return "";
        String out = plain;
        for (String p : Config.musicPrefixes) {
            if (p != null && !p.isEmpty()) out = out.replace(p, "");
        }
        return out.trim();
    }
    static void notePlayerChat(String text) {
        pushRecent(RECENT_PLAYER_CHAT, text);
    }

    /** 记录一次玩家进出，供原版进出广播去重（主线程调用） */
    static void notePresence(String name) {
        pushRecent(RECENT_PRESENCE, name);
    }

    /** 成就去重：{@code ACH_DEDUPE_MS} 内同一个 key 只算一次，返回是否应当记录 */
    private static boolean markAchievement(String key) {
        long now = System.currentTimeMillis();
        Long prev = RECENT_ACH.get(key);
        if (prev != null && now - prev < ACH_DEDUPE_MS) return false;
        RECENT_ACH.put(key, now);
        if (RECENT_ACH.size() > 256) {
            java.util.Iterator<Map.Entry<String, Long>> it = RECENT_ACH.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > ACH_DEDUPE_MS) it.remove();
            }
        }
        return true;
    }

    private static void pushRecent(ArrayDeque<String[]> queue, String text) {
        if (text == null || text.isEmpty()) return;
        synchronized (queue) {
            queue.addLast(new String[]{text, String.valueOf(System.currentTimeMillis())});
            while (queue.size() > RECENT_MAX) queue.pollFirst();
        }
    }

    private static boolean containsRecent(ArrayDeque<String[]> queue, String plain, long windowMs) {
        long now = System.currentTimeMillis();
        synchronized (queue) {
            for (String[] row : queue) {
                if (now - Long.parseLong(row[1]) <= windowMs && plain.contains(row[0])) return true;
            }
        }
        return false;
    }

    /**
     * 命令回显过滤：只在命令**真的存在**时过滤。
     * {@code ?/spawn}、{@code <Steve> /home} 这类会被隐藏；{@code ?/notacommand} 照常显示。
     */
    private static boolean isExecutableCommandEcho(String plain) {
        java.util.regex.Matcher m = CMD_ECHO.matcher(plain);
        if (!m.find()) return false;
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getCommandManager() == null) return false;
        String cmd = m.group(1).toLowerCase(java.util.Locale.ROOT);
        return server.getCommandManager().getCommands().containsKey(cmd);
    }

    // ------------------------------------------------------------ 内部

    private static long append(int kind, String name, String text) {
        return append(kind, name, text, "", "", "", "");
    }

    private static long append(int kind, String name, String text, String server, String att) {
        return append(kind, name, text, server, att, "", "");
    }

    private static long append(int kind, String name, String text, String server, String att, String team) {
        return append(kind, name, text, server, att, team, "");
    }

    private static long append(int kind, String name, String text, String server, String att, String team,
                               String teamColor) {
        return append(kind, name, text, server, att, team, teamColor, "");
    }

    private static long append(int kind, String name, String text, String server, String att, String team,
                               String teamColor, String world) {
        long seq = SEQ.incrementAndGet();
        Msg msg = new Msg(seq, kind, name, text, System.currentTimeMillis(), server, att, team, teamColor, world);
        synchronized (RING) {
            RING.addLast(msg);
            while (RING.size() > BUFFER) RING.pollFirst();
        }
        return seq;
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
