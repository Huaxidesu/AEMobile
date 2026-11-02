package dev.youtiao.aemobile.mixins;

import dev.youtiao.aemobile.net.ChatHub;
import net.minecraft.server.management.ServerConfigurationManager;
import net.minecraft.util.IChatComponent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 服务器广播聊天（公告、/say、AutoMessage、插件消息）进 App 聊天流。
 *
 * <p>只挂在 {@code sendChatMsg(IChatComponent)} 的 HEAD，不改变原方法行为。
 * 玩家聊天 / 进出的二次广播、App 自己的广播、可执行命令回显都在
 * {@link ChatHub#captureServerChat} 里过滤。
 *
 * <p><b>方法体不允许抛异常</b>：sendChatMsg 是广播的发送路径，抓聊天里的任何异常
 * 上抛都会打断广播本身（命令回显整条丢失）。取文本的安全约束见
 * {@link ChatHub#safeOwnPlain}；这里再加一层 {@code catch (Throwable)} 异常保护。
 */
@Mixin(ServerConfigurationManager.class)
public class MixinServerConfigurationManager {

    @Inject(method = "sendChatMsg", at = @At("HEAD"))
    private void aemobile$captureServerChat(IChatComponent component, CallbackInfo ci) {
        try {
            ChatHub.captureServerChat(component);
        } catch (Throwable t) {
            ChatHub.logCaptureFailure("sendChatMsg", t);
        }
    }
}
