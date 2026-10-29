package dev.youtiao.aemobile.debug;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraftforge.client.ClientCommandHandler;

/**
 * 客户端命令桥：执行 {@code cmd-client.txt} 里的命令。
 *
 * <p>为什么客户端要单独一条：{@code /aemobile dump ...} 是
 * {@link net.minecraftforge.client.ClientCommandHandler} 注册的客户端命令，
 * 用 {@code sendChatMessage} 发出去只会到服务端（那边没有这个命令）。
 * 所以先走客户端命令处理器，返回 0（没人认领）再当普通聊天发出去。
 *
 * <p>只由 {@code ClientProxy} 引用，专用服务器上不会被加载。
 */
public final class ClientDebugBridge {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null || mc.thePlayer == null) return;
        String line = DebugBridge.pollClient();
        if (line == null) return;
        long t0 = System.currentTimeMillis();
        String result;
        try {
            int r = ClientCommandHandler.instance.executeCommand(mc.thePlayer, line);
            if (r == 0) {
                mc.thePlayer.sendChatMessage(line);
                result = "sent-to-server";
            } else {
                result = "client-result=" + r;
            }
        } catch (Throwable t) {
            result = "error=" + t;
        }
        DebugBridge.appendClient(line, result, System.currentTimeMillis() - t0);
    }
}
