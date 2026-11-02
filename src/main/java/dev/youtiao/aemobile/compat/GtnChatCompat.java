package dev.youtiao.aemobile.compat;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import dev.youtiao.aemobile.Config;
import dev.youtiao.aemobile.net.ChatFormat;
import net.minecraft.entity.player.EntityPlayerMP;

/**
 * 与 GTNHChat（聊天格式化 / 跨服转发模组）的全部接触面。
 *
 * <p><b>这是整个 AEMobile 里唯一知道 GTNHChat 存在的地方</b>。别的类只问它要数据，
 * 不自己 {@code Class.forName} —— 以前这套反射散在四个地方（转发、本服名、队伍、世界名），
 * 对方改一个方法签名就静默降级，排查起来毫无线索。
 *
 * <p><b>没有编译期依赖</b>：全部反射，没装 GTNHChat 时 {@link #present()} 为 false，
 * 所有方法退化成空值，聊天照常工作 —— 只是队伍 / 服务器 / 世界标签没有内容。
 *
 * <p><b>能力按「方法存不存在」探测，不认版本号</b>：对方加功能不必改这里，
 * 少了哪个方法就少哪项能力，App 收到 {@link #caps()} 后自己决定显示什么。
 *
 * <p>反方向的接触（GTNHChat 调 {@code ChatHub.acceptFormatted}）在对方的
 * {@code bridge/MobileBridge} 里，同样是反射 + 探测失败即停用。
 */
public final class GtnChatCompat {

    /** 聊天有格式化与元数据桥（装了 GTNHChat 就有） */
    public static final String CAP_CHAT = "chat";
    /** 能拿到队伍名与队伍色（还要求装了 ServerUtilities） */
    public static final String CAP_TEAM = "team";
    /** 能拿到世界显示名（GTNHChat 已按 MV 别名 / config 换成中文） */
    public static final String CAP_WORLD = "world";
    /** 手机端发的消息能跨服转发 */
    public static final String CAP_RELAY = "relay";

    private static boolean checked;
    private static boolean present;

    /** {@code ChatRelay.forwardAppChat(String, String)} */
    private static Method mForward;
    /** {@code ChatRelay.clientModPresent(String)}；1.1.0 才有，旧版本为 null */
    private static Method mClientMod;
    /** {@code TeamInfo.of(EntityPlayerMP)} / {@code TeamInfo.available()} */
    private static Method mTeamOf;
    private static Method mTeamAvailable;
    /** {@code ChatFormat.worldName(EntityPlayerMP)} */
    private static Method mWorldOf;
    /** {@code Config.serverDisplay} / {@code Config.relayEnabled} */
    private static Field fDisplay;
    private static Field fRelayEnabled;

    /** 本服显示名，读一次缓存：改配置本来就要重启 */
    private static String display;

    private GtnChatCompat() {}

    // ------------------------------------------------------------ 探测

    private static synchronized void check() {
        if (checked) return;
        checked = true;
        try {
            // 探测该类而不是 GTNHChat 主类：手机端的数据全靠它推送，它存在即说明「聊天元数据桥」可用
            Class.forName("com.huaxidesu.gtnchat.bridge.MobileBridge");
            present = true;
        } catch (Throwable t) {
            present = false;
            return;
        }
        try {
            Class<?> relay = Class.forName("com.huaxidesu.gtnchat.relay.ChatRelay");
            mForward = method(relay, "forwardAppChat", String.class, String.class);
            mClientMod = method(relay, "clientModPresent", String.class);
        } catch (Throwable ignored) {
            // 跨服转发不可用，其余照常
        }
        try {
            Class<?> cfg = Class.forName("com.huaxidesu.gtnchat.config.Config");
            fDisplay = field(cfg, "serverDisplay");
            fRelayEnabled = field(cfg, "relayEnabled");
        } catch (Throwable ignored) {
            // 获取不到本服显示名与跨服开关
        }
        try {
            Class<?> teamInfo = Class.forName("com.huaxidesu.gtnchat.chat.TeamInfo");
            mTeamOf = method(teamInfo, "of", EntityPlayerMP.class);
            mTeamAvailable = method(teamInfo, "available");
        } catch (Throwable ignored) {
            // 没有队伍信息
        }
        try {
            Class<?> fmt = Class.forName("com.huaxidesu.gtnchat.chat.ChatFormat");
            mWorldOf = method(fmt, "worldName", EntityPlayerMP.class);
        } catch (Throwable ignored) {
            // 没有世界名
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... args) {
        try {
            Method m = owner.getMethod(name, args);
            m.setAccessible(true);
            return m;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Field field(Class<?> owner, String name) {
        try {
            Field f = owner.getField(name);
            f.setAccessible(true);
            return f;
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------ 能力

    /** GTNHChat 是否在场 */
    public static boolean present() {
        check();
        return present;
    }

    /**
     * 这台服实际能提供的聊天能力，握手时下发给 App（`HELLO_ACK.caps`）。
     *
     * <p>未安装 GTNHChat 时为空列表 —— App 据此把队伍 / 服务器 / 世界标签整块关闭，
     * 而不是保留点击后无反应的入口。
     */
    public static List<String> caps() {
        check();
        if (!present) return new ArrayList<String>();
        List<String> out = new ArrayList<String>(4);
        out.add(CAP_CHAT);
        if (teamAvailable()) out.add(CAP_TEAM);
        if (mWorldOf != null) out.add(CAP_WORLD);
        if (relayEnabled()) out.add(CAP_RELAY);
        return out;
    }

    /** 调试 / 日志用：把能力拼成 `chat/team/world/relay` */
    public static String capsText() {
        List<String> list = caps();
        if (list.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        for (String s : list) {
            if (sb.length() > 0) sb.append('/');
            sb.append(s);
        }
        return sb.toString();
    }

    private static boolean teamAvailable() {
        check();
        if (mTeamAvailable == null) return false;
        try {
            return Boolean.TRUE.equals(mTeamAvailable.invoke(null));
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean relayEnabled() {
        check();
        if (fRelayEnabled == null) return false;
        try {
            return Boolean.TRUE.equals(fRelayEnabled.get(null));
        } catch (Throwable t) {
            return false;
        }
    }

    // ------------------------------------------------------------ 数据

    /**
     * 本服在群组服里的显示名（GTNHChat 的 {@code Config.serverDisplay}，例如「天空树」）。
     *
     * <p>未安装 GTNHChat 时返回空串 —— 手机端获取不到就不绘制服务器标签，不做猜测。
     */
    public static String serverDisplay() {
        check();
        if (!present) return "";
        if (display != null) return display;
        if (fDisplay == null) return display = "";
        try {
            Object v = fDisplay.get(null);
            display = v == null ? "" : ChatFormat.sanitizeName(String.valueOf(v), Config.chatMaxName);
        } catch (Throwable t) {
            display = "";
        }
        return display;
    }

    /**
     * 转发一条手机端聊天，让群组服里的其它后端也能看到。
     *
     * <p>必须在主线程调用（{@code ChatHub.broadcast} 就在主线程）。
     * 未安装 GTNHChat、或该服务器的跨服转发停用时为空操作。
     */
    public static void forwardAppChat(String name, String text) {
        check();
        if (mForward == null) return;
        try {
            mForward.invoke(null, name, text);
        } catch (Throwable t) {
            // 旁路：转发失败不能影响本服的广播
        }
    }

    /**
     * 玩家所在队伍，返回 {@code [名字, 颜色码]}；没有队伍或没装模组返回空串对。
     *
     * <p>NEI 的广播里没有玩家对象，只能按名字反查在线玩家再向 GTNHChat 查询 ——
     * 那套 ServerUtilities 反射已在 GTNHChat 中实现，本模组不必重复实现。
     */
    public static String[] teamOf(EntityPlayerMP player) {
        check();
        if (mTeamOf == null || player == null) return new String[] { "", "" };
        try {
            Object info = mTeamOf.invoke(null, player);
            if (info == null) return new String[] { "", "" };
            Class<?> type = info.getClass();
            Object name = type.getField("name").get(info);
            Object color = type.getField("color").get(info);
            return new String[] {
                name == null ? "" : ChatFormat.sanitizeName(String.valueOf(name), Config.chatMaxName),
                // 颜色只有一个码字符，不走 sanitizeName（该函数供名字净化使用）
                color == null ? "" : String.valueOf(color).trim() };
        } catch (Throwable t) {
            return new String[] { "", "" };
        }
    }

    /** 玩家所在世界的显示名；获取不到时返回空串 */
    public static String worldOf(EntityPlayerMP player) {
        check();
        if (mWorldOf == null || player == null) return "";
        try {
            Object name = mWorldOf.invoke(null, player);
            return name == null ? "" : ChatFormat.sanitizeName(String.valueOf(name), Config.chatMaxName);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 该玩家的客户端是否安装 GTNHChat（决定本地成就由谁播报）。
     *
     * @return {@code 1} 已安装；{@code 0} 未安装；{@code -1} 未知（未安装 GTNHChat，
     *         或对方是没有回执机制的旧版本）
     */
    public static int clientMod(String player) {
        check();
        if (mClientMod == null || player == null || player.isEmpty()) return -1;
        try {
            Object v = mClientMod.invoke(null, player);
            return v instanceof Integer ? ((Integer) v).intValue() : -1;
        } catch (Throwable t) {
            return -1;
        }
    }

    /** 给 `/aemobile stats` 之类的地方打一行，确认桥的状态 */
    public static String describe() {
        if (!present()) return "GTNHChat 未安装（队伍 / 服务器 / 世界标签关闭）";
        return "GTNHChat 已接上，能力 " + capsText();
    }

    /** 仅供调试：把探测到的能力列表按固定顺序摊开 */
    public static List<String> allCaps() {
        return Arrays.asList(CAP_CHAT, CAP_TEAM, CAP_WORLD, CAP_RELAY);
    }
}
