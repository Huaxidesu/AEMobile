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
    }

    private static class Categories {
        public static final String general = "general";
        public static final String tps = "tps";
        public static final String chat = "chat";
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

        if (configuration.hasChanged()) {
            configuration.save();
        }
    }
}
