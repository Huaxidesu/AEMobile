package dev.youtiao.aemobile.net;

import java.lang.reflect.Method;

import dev.youtiao.aemobile.Config;

/**
 * 把手机端发出的聊天交给 GTNHChat 转发到群组服里的其它后端。
 *
 * <p><b>为什么需要单独一层</b>：玩家在游戏里打字走 {@code ServerChatEvent}，
 * GTNHChat 在那里取消原版广播、重新渲染并 {@code ChatRelay.forwardChat} 转发。
 * 手机端的消息由 {@link ChatHub#broadcast} 直接 {@code sendChatMsg}，不经过那个事件 ——
 * 于是群组服里手机端说的话只有它连的那台子服能看到，别的子服一片安静。
 *
 * <p><b>不建立编译期依赖</b>：AEMobile 不打包 GTNHChat，没装时整个桥停用，
 * 行为与加这层之前完全一致。反方向的调用见 GTNHChat 的 {@code bridge/MobileBridge}。
 */
final class GtnChatBridge {

    private static boolean checked;
    /** {@code ChatRelay.forwardAppChat(String, String)}；null 表示 GTNHChat 不在场或版本太旧 */
    private static Method forward;
    /** {@code Config.serverDisplay}；null 表示 GTNHChat 不在场，本服显示名取不到 */
    private static java.lang.reflect.Field displayField;
    /** 反射读出来的本服显示名，第一次读到时缓存（配置改了要重启，所以缓存安全） */
    private static String display;

    private GtnChatBridge() {}

    private static synchronized void check() {
        if (checked) return;
        checked = true;
        try {
            Class<?> relay = Class.forName("com.huaxidesu.gtnchat.relay.ChatRelay");
            forward = relay.getMethod("forwardAppChat", String.class, String.class);
        } catch (Throwable t) {
            forward = null;
        }
        try {
            Class<?> cfg = Class.forName("com.huaxidesu.gtnchat.config.Config");
            displayField = cfg.getField("serverDisplay");
        } catch (Throwable t) {
            displayField = null;
        }
    }

    /**
     * 本服在群组服里的显示名（GTNHChat 的 {@code Config.serverDisplay}，例如「天空树」）。
     *
     * <p>没装 GTNHChat 时返回空串 —— 手机端拿不到就不画服务器标签，不猜。
     * 这里只读一次就缓存：配置改了本来就要重启，缓存不会读到过期值。
     */
    static String serverDisplay() {
        check();
        if (display != null) return display;
        if (displayField == null) return display = "";
        try {
            Object v = displayField.get(null);
            display = v == null ? "" : ChatFormat.sanitizeName(String.valueOf(v), Config.chatMaxName);
        } catch (Throwable t) {
            display = "";
        }
        return display;
    }

    /**
     * 转发一条手机端聊天。必须在主线程调用（{@code ChatHub.broadcast} 就在主线程）。
     * 没装 GTNHChat、或那台服的跨服关掉时是空操作。
     */
    static void forward(String name, String text) {
        check();
        if (forward == null) return;
        try {
            forward.invoke(null, name, text);
        } catch (Throwable t) {
            // 旁路：转发失败不能影响本服的广播
        }
    }
}
