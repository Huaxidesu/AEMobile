package dev.youtiao.aemobile;

import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.common.config.Property;

import java.io.File;

public class Config {

    private static class Defaults {
        public static final String greeting = "Hello World";
        public static final String[] tpsNames = new String[0];
        public static final boolean chatEnabled = true;
        public static final int chatMaxName = 16;
        public static final int chatMaxText = 200;
        public static final int chatMinIntervalMs = 2000;
        public static final int chatPerMinute = 30;
        public static final int chatBroadcastPerTick = 5;
        public static final boolean chatGuest = false;
        public static final int chatGuestMax = 8;
        public static final boolean chatJoinLeave = true;
        public static final boolean chatAchievements = true;
        public static final boolean musicEnabled = true;
        public static final String[] musicPrefixes = new String[]{"[AllMusic3]", "[肥猫电台]"};
        /** 只有带这些标记的 AllMusic 消息才进公共聊天流（避免把别人的搜歌/帮助结果广播出去） */
        public static final String[] musicPublicMarkers = new String[]{"正在播放"};
        public static final boolean bridgeEnabled = true;
        public static final String bridgeHost = "127.0.0.1";
        public static final int bridgePort = 44445;
        public static final String bridgeSecret = "aemobile-bridge";
        /** BC 里的子服名（/server 用的那个）；留空则上报的 tab 文案对所有子服生效。 */
        public static final String bridgeServerName = "";
    }

    private static class Categories {
        public static final String general = "general";
        public static final String tps = "tps";
        public static final String chat = "chat";
        public static final String music = "music";
        public static final String bridge = "bridge";
    }

    public static String greeting = Defaults.greeting;

    /**
     * 世界显示名的覆盖表，格式 {@code DIM70=暮色森林} 或 {@code 70=暮色森林}。
     * 优先级仅次于 Multiverse 的 alias，用来补内置表里没有的维度（或改掉不喜欢的译名）。
     */
    public static String[] tpsNames = Defaults.tpsNames;

    /**
     * 聊天通道开关。关掉后：App 的 chat 订阅会收到 CHAT_DISABLED、游戏内公屏也不再进缓冲
     * （没人订阅时本来就零开销，这个开关是给「不想让手机说话」的服主准备的）。
     */
    public static boolean chatEnabled = Defaults.chatEnabled;
    /** 自定义名称最大长度（按码点） */
    public static int chatMaxName = Defaults.chatMaxName;
    /** 单条消息最大长度（按码点） */
    public static int chatMaxText = Defaults.chatMaxText;
    /** 同一 token 两条消息的最小间隔（毫秒） */
    public static int chatMinIntervalMs = Defaults.chatMinIntervalMs;
    /** 同一 token 每分钟最多发几条 */
    public static int chatPerMinute = Defaults.chatPerMinute;
    /** 每 tick 最多往游戏里广播几条（防刷屏拖 tick） */
    public static int chatBroadcastPerTick = Defaults.chatBroadcastPerTick;
    /**
     * 游客只读聊天：允许**不填绑定码**的连接订阅 chat 流（只能看，不能发言、不能调任何 REQ）。
     * 默认关 —— 开了等于把公屏聊天公开给任何知道端口的人。
     */
    public static boolean chatGuest = Defaults.chatGuest;
    /** 同时最多允许几条游客连接（总连接上限只有 32，游客不能把正式用户挤掉） */
    public static int chatGuestMax = Defaults.chatGuestMax;

    /** 是否把玩家加入/退出也推给 App（只进手机，不广播游戏公屏） */
    public static boolean chatJoinLeave = Defaults.chatJoinLeave;
    /** 是否把玩家完成成就推给 App */
    public static boolean chatAchievements = Defaults.chatAchievements;

    /** 音乐桥开关（BC/代理上的 AEMobileBridge） */
    public static boolean musicEnabled = Defaults.musicEnabled;
    /** 通用代理命令桥（AEMobileBridge）：以后别的功能也可以复用 */
    public static boolean bridgeEnabled = Defaults.bridgeEnabled;
    public static String bridgeHost = Defaults.bridgeHost;
    public static int bridgePort = Defaults.bridgePort;
    public static String bridgeSecret = Defaults.bridgeSecret;
    public static String bridgeServerName = Defaults.bridgeServerName;
    public static String[] musicPrefixes = Defaults.musicPrefixes;
    /** 只有带这些标记的 AllMusic 消息才进公共聊天（默认「正在播放」） */
    public static String[] musicPublicMarkers = Defaults.musicPublicMarkers;

    public static void syncronizeConfiguration(File configFile) {
        Configuration configuration = new Configuration(configFile);
        configuration.load();

        Property greetingProperty = configuration.get(Categories.general, "greeting", Defaults.greeting, "How shall I greet?");
        greeting = greetingProperty.getString();

        tpsNames = configuration.getStringList("names", Categories.tps, Defaults.tpsNames,
                "Override the display name of a world, one per line: DIM70=暮色森林 (or 70=暮色森林).\n"
                        + "Priority: Multiverse alias > this list > built-in table > provider name > world folder name.");

        chatEnabled = configuration.getBoolean("enabled", Categories.chat, Defaults.chatEnabled,
                "Enable the in-game <-> App chat channel. When false the App cannot send and player chat is not forwarded.");
        chatMaxName = configuration.getInt("maxName", Categories.chat, Defaults.chatMaxName, 1, 64,
                "Max length of the App-side display name (code points). Brackets are always stripped.");
        chatMaxText = configuration.getInt("maxText", Categories.chat, Defaults.chatMaxText, 1, 512,
                "Max length of one chat message (code points).");
        chatMinIntervalMs = configuration.getInt("minIntervalMs", Categories.chat, Defaults.chatMinIntervalMs, 0, 60000,
                "Minimum gap between two messages from the same token.");
        chatPerMinute = configuration.getInt("perMinute", Categories.chat, Defaults.chatPerMinute, 1, 600,
                "Max messages per minute from the same token.");
        chatBroadcastPerTick = configuration.getInt("broadcastPerTick", Categories.chat, Defaults.chatBroadcastPerTick, 1, 50,
                "Max messages broadcast into the game per server tick.");
        chatGuest = configuration.getBoolean("guest", Categories.chat, Defaults.chatGuest,
                "Allow read-only guest connections WITHOUT a token: they can subscribe to the chat stream only"
                        + " (cannot send, cannot call any request). Off by default - the public chat becomes"
                        + " readable by anyone who knows the port.");
        chatGuestMax = configuration.getInt("guestMax", Categories.chat, Defaults.chatGuestMax, 0, 32,
                "Max simultaneous guest connections. Keep it well below the 32-connection cap so guests never"
                        + " crowd out authenticated users.");
        chatJoinLeave = configuration.getBoolean("joinLeave", Categories.chat, Defaults.chatJoinLeave,
                "Forward player join/leave notices to the App chat stream (never broadcast into game chat).");
        chatAchievements = configuration.getBoolean("achievements", Categories.chat, Defaults.chatAchievements,
                "Forward player achievements to the App chat stream as system lines.");

        musicEnabled = configuration.getBoolean("enabled", Categories.music, Defaults.musicEnabled,
                "Enable the App music feature (song requests through AEMobileBridge).");
        bridgeEnabled = configuration.getBoolean("enabled", Categories.bridge, Defaults.bridgeEnabled,
                "Enable the generic proxy command bridge (AEMobileBridge).");
        bridgeHost = configuration.getString("host", Categories.bridge, Defaults.bridgeHost,
                "AEMobileBridge host. 127.0.0.1 when proxy and backend run on the same machine.");
        bridgePort = configuration.getInt("port", Categories.bridge, Defaults.bridgePort, 1, 65535,
                "TCP port of AEMobileBridge on the proxy.");
        bridgeSecret = configuration.getString("secret", Categories.bridge, Defaults.bridgeSecret,
                "Shared secret between this mod and AEMobileBridge.");
        bridgeServerName = configuration.getString("server-name", Categories.bridge, Defaults.bridgeServerName,
                "Name of this backend in the proxy config (the one used by /server).\n"
                        + "Leave this EMPTY in almost every case: AEMobile reports this server's port and\n"
                        + "AEMobileBridge resolves the backend name by itself. Only fill it in when the\n"
                        + "proxy reaches this backend on a different port than server.properties says.");
        musicPrefixes = configuration.getStringList("prefixes", Categories.music, Defaults.musicPrefixes,
                "AllMusic chat prefixes to recognise (one per line), e.g. [AllMusic3] or [肥猫电台].");
        musicPublicMarkers = configuration.getStringList("publicMarkers", Categories.music, Defaults.musicPublicMarkers,
                "Only AllMusic messages containing one of these markers go to the public App chat (default: 正在播放).\n"
                        + "Search/help/list responses are private to the requester via AEMobileBridge.");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
