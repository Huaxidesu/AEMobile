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
        public static final boolean chatJoinLeave = true;
        public static final boolean musicEnabled = true;
        public static final String musicHost = "127.0.0.1";
        public static final int musicPort = 44445;
        public static final String musicSecret = "aemobile-music";
        public static final String[] musicPrefixes = new String[]{"[AllMusic3]", "[肥猫电台]"};
        /** 只有带这些标记的 AllMusic 消息才进公共聊天流（避免把别人的搜歌/帮助结果广播出去） */
        public static final String[] musicPublicMarkers = new String[]{"正在播放"};
    }

    private static class Categories {
        public static final String general = "general";
        public static final String tps = "tps";
        public static final String chat = "chat";
        public static final String music = "music";
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
    /** 是否把玩家加入/退出也推给 App（只进手机，不广播游戏公屏） */
    public static boolean chatJoinLeave = Defaults.chatJoinLeave;

    /** 音乐桥开关（BC/代理上的 AEMobileMusicBridge） */
    public static boolean musicEnabled = Defaults.musicEnabled;
    /** 桥接插件的地址：同机 127.0.0.1；BC 和后端分开部署时填代理内网 IP */
    public static String musicHost = Defaults.musicHost;
    public static int musicPort = Defaults.musicPort;
    public static String musicSecret = Defaults.musicSecret;
    /** AllMusic 聊天前缀，多个用 config 的 [music] prefixes 配；用于公开聊天识别与私有结果解析 */
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
        chatJoinLeave = configuration.getBoolean("joinLeave", Categories.chat, Defaults.chatJoinLeave,
                "Forward player join/leave notices to the App chat stream (never broadcast into game chat).");

        musicEnabled = configuration.getBoolean("enabled", Categories.music, Defaults.musicEnabled,
                "Enable the music bridge (App song requests through the proxy plugin AEMobileMusicBridge).");
        musicHost = configuration.getString("host", Categories.music, Defaults.musicHost,
                "AEMobileMusicBridge host. 127.0.0.1 when proxy and backend run on the same machine.");
        musicPort = configuration.getInt("port", Categories.music, Defaults.musicPort, 1, 65535,
                "Local TCP port of AEMobileMusicBridge on the proxy.");
        musicSecret = configuration.getString("secret", Categories.music, Defaults.musicSecret,
                "Shared secret between this mod and AEMobileMusicBridge.");
        musicPrefixes = configuration.getStringList("prefixes", Categories.music, Defaults.musicPrefixes,
                "AllMusic chat prefixes to recognise (one per line), e.g. [AllMusic3] or [肥猫电台].");
        musicPublicMarkers = configuration.getStringList("publicMarkers", Categories.music, Defaults.musicPublicMarkers,
                "Only AllMusic messages containing one of these markers go to the public App chat (default: 正在播放).\n"
                        + "Search/help/list responses are private to the requester via AEMobileMusicBridge.");

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
