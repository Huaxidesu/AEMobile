package dev.youtiao.aemobile;

import cpw.mods.fml.common.event.*;
import dev.youtiao.aemobile.client.CommandDumpIcons;
import net.minecraftforge.client.ClientCommandHandler;

public class ClientProxy extends CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items,
    // etc, and register them with the GameRegistry."
    public void preInit(FMLPreInitializationEvent event) 	{
        super.preInit(event);
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes."
    public void init(FMLInitializationEvent event) {
        super.init(event);
        // /aemobile dumpicons —— 导出物品图标给 App 用（见 client/CommandDumpIcons）
        ClientCommandHandler.instance.registerCommand(new CommandDumpIcons());
        // debug 命令桥的客户端通道：执行 cmd-client.txt 里的客户端命令（默认关闭）
        dev.youtiao.aemobile.debug.DebugBridge.startClient(net.minecraft.client.Minecraft.getMinecraft().mcDataDir);
        cpw.mods.fml.common.FMLCommonHandler.instance().bus()
                .register(new dev.youtiao.aemobile.debug.ClientDebugBridge());
    }

    // postInit "Handle interaction with other mods, complete your setup based on this."
    public void postInit(FMLPostInitializationEvent event) {
        super.postInit(event);
    }

    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        super.serverAboutToStart(event);
    }

    // register server commands in this event handler
    public void serverStarting(FMLServerStartingEvent event) {
        super.serverStarting(event);
    }

    public void serverStarted(FMLServerStartedEvent event) {
        super.serverStarted(event);
    }

    public void serverStopping(FMLServerStoppingEvent event) {
        super.serverStopping(event);
    }

    public void serverStopped(FMLServerStoppedEvent event) {
        super.serverStopped(event);
    }
}