package dev.youtiao.aemobile.mixins;

import dev.youtiao.aemobile.net.ChatHub;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.server.S02PacketChat;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * AllMusic3 的“正在播放/点歌”消息是逐个玩家 {@code sendMessage}（S02PacketChat），
 * 不走 {@code ServerConfigurationManager.sendChatMsg}，所以单独在这里抓。
 *
 * <p>只认文本里带 {@code [AllMusic3]} 的聊天包，避免把私聊、歌词、命令反馈带进 App；
 * 同一条消息发给多个玩家时由 {@link ChatHub#captureServerChat} 的 800ms 去重合并。
 */
@Mixin(NetHandlerPlayServer.class)
public class MixinNetHandlerPlayServer {

    @Inject(method = "func_147359_a", at = @At("HEAD"))
    private void aemobile$captureMusicChat(Packet packet, CallbackInfo ci) {
        if (!(packet instanceof S02PacketChat)) return;
        IChatComponent component = ((S02PacketChat) packet).func_148915_c();
        if (component == null) return;
        String plain = component.getUnformattedText();
        if (plain != null && ChatHub.isMusicText(plain) && ChatHub.isMusicPublicText(plain)) {
            ChatHub.captureServerChat(component);
        }
    }
}
