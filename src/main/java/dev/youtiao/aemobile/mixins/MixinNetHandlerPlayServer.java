package dev.youtiao.aemobile.mixins;

import dev.youtiao.aemobile.Config;
import dev.youtiao.aemobile.net.ChatHub;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * AllMusic3 的“正在播放/点歌”消息是逐个玩家 {@code sendMessage}（S02PacketChat），
 * 不走 {@code ServerConfigurationManager.sendChatMsg}，所以单独在这里抓。
 *
 * <p>只认文本里带 {@code [AllMusic3]} 的聊天包，避免把私聊、歌词、命令反馈带进 App；
 * 同一条消息发给多个玩家时由 {@link ChatHub#captureServerChat} 的 800ms 去重合并。
 *
 * <p><b>取组件必须走反射</b>：直接写 {@code packet.func_148915_c()} 编译能过，
 * 但在 Thermos 上运行时报 {@code NoSuchMethodError}，而且是在玩家登录、
 * 服务端发欢迎消息时触发 —— 整条连接被踢掉（{@code A fatal error has occured}）。
 * 方法名和字段名在不同端上不一样，所以两种都试一遍。
 *
 * <p><b>本方法体在任何输入下都不允许抛异常</b>：注入点在聊天包的发送路径上，
 * 异常冒出去就是整条连接被踢（2026-11-03 NEI 的时间回显 {@code nei.chat.time}
 * 带 {@code %d}，vanilla 的模板解析器对它直接 throw，当时把点按钮的玩家踢下了线）。
 * 取文本一律走 {@link ChatHub#safeOwnPlain}，整个方法体再有 {@code catch (Throwable)} 兜底；
 * 失败只丢抓取，不 cancel，原包照发。
 */
@Mixin(NetHandlerPlayServer.class)
public class MixinNetHandlerPlayServer {

    /** 反射拿到的取组件方式，第一次调用时确定 */
    private static Method getter;
    private static Field field;
    private static boolean resolved;

    @Inject(method = "func_147359_a", at = @At("HEAD"))
    private void aemobile$captureMusicChat(Packet packet, CallbackInfo ci) {
        if (!(packet instanceof S02PacketChat)) return;
        if (!Config.chatEnabled) return;
        IChatComponent component = componentOf((S02PacketChat) packet);
        if (component == null) return;
        try {
            if (isNeiLink(component)) {
                // NEI 的物品链接也是逐个玩家发的（`ServerUtils.sendChatToAll`），同样不走
                // `sendChatMsg`；不在这里捞，手机端永远看不到别人分享的物品。
                ChatHub.captureServerChat(component);
                return;
            }
            String plain = ChatHub.safeOwnPlain(component);
            if (plain != null && ChatHub.isMusicText(plain) && ChatHub.isMusicPublicText(plain)) {
                ChatHub.captureServerChat(component);
            }
        } catch (Throwable t) {
            ChatHub.logCaptureFailure("sendPacket", t);
        }
    }

    /**
     * 是不是 NEI 的物品链接。
     *
     * <p>按 translation key 判断而不是按文本：服务端是 en_US，文本是「Steve shared [xxx]」，
     * 匹配文本容易误伤；key 是固定的 {@code nei.chat.item_link.text}。
     */
    private static boolean isNeiLink(IChatComponent component) {
        if (!(component instanceof ChatComponentTranslation)) return false;
        String key = ((ChatComponentTranslation) component).getKey();
        return key != null && key.startsWith("nei.chat.item_link");
    }

    /** S02PacketChat → IChatComponent，方法名和字段名各试一次 */
    private static IChatComponent componentOf(S02PacketChat packet) {
        if (!resolved) {
            resolved = true;
            for (String name : new String[]{"func_148915_c", "getChatComponent"}) {
                try {
                    getter = S02PacketChat.class.getMethod(name);
                    break;
                } catch (Throwable ignored) {
                }
            }
            if (getter == null) {
                for (String name : new String[]{"field_148919_a", "chatComponent"}) {
                    try {
                        Field f = S02PacketChat.class.getDeclaredField(name);
                        f.setAccessible(true);
                        field = f;
                        break;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        try {
            if (getter != null) return (IChatComponent) getter.invoke(packet);
            if (field != null) return (IChatComponent) field.get(packet);
        } catch (Throwable ignored) {
        }
        return null;
    }
}
