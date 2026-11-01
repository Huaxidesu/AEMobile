package dev.youtiao.aemobile;

import appeng.core.AppEng;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.*;
import cpw.mods.fml.common.registry.FMLControlledNamespacedRegistry;
import cpw.mods.fml.common.registry.GameData;
import cpw.mods.fml.common.registry.GameRegistry;
import dev.youtiao.aemobile.blocks.BlockAEMonitor;
import dev.youtiao.aemobile.blocks.TileAEMonitor;
import dev.youtiao.aemobile.net.TcpServer;
import net.minecraft.block.Block;
import net.minecraft.block.material.Material;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.CraftingManager;
import net.minecraftforge.common.MinecraftForge;

import static net.minecraft.block.Block.soundTypePiston;

public class CommonProxy {

    // preInit "Run before anything else. Read your config, create blocks, items,
    // etc, and register them with the GameRegistry."
    private static Block aemonitor;
    public void preInit(FMLPreInitializationEvent event) 	{
        Config.syncronizeConfiguration(event.getSuggestedConfigurationFile());

        // 世界显示名覆盖（config 的 [tps] names），只影响 TPS 页面里显示的名字
        dev.youtiao.aemobile.metrics.WorldNames.setOverrides(Config.tpsNames);

        AEMobile.info(Config.greeting);
        AEMobile.info("I am " + Tags.MODNAME + " at version " + Tags.VERSION + " and group name " + Tags.GROUPNAME);
    }

    // load "Do your mod setup. Build whatever data structures you care about. Register recipes."
    public void init(FMLInitializationEvent event) {
        FMLControlledNamespacedRegistry<Block> blockRegistry = GameData.getBlockRegistry();
        aemonitor = new BlockAEMonitor(Material.anvil).
                setHardness(3.5F).
                setStepSound(soundTypePiston).
                setBlockName("block.aemonitor").
                setCreativeTab(CreativeTabs.tabDecorations);
        GameRegistry.registerBlock(aemonitor,  "aemonitor");
        GameRegistry.registerTileEntity(TileAEMonitor.class, "aemonitor");
    }

    // postInit "Handle interaction with other mods, complete your setup based on this."
    public void postInit(FMLPostInitializationEvent event) {
        CraftingManager.getInstance().addRecipe(
                new ItemStack(Item.getItemFromBlock(aemonitor), 1), new Object[] {"X#X", "#O#", "X#X", 'X',
                        Item.itemRegistry.getObject("appliedenergistics2:tile.BlockController"),'O',
                        Item.itemRegistry.getObject("appliedenergistics2:item.ItemBasicStorageCell.16k")
                        ,'#',Item.itemRegistry.getObject("appliedenergistics2:tile.BlockFluix")}
        );
        // MSPT 监听已移除（2026-10-27）：MSPT 监控整体砍掉，/api/serverMSPT 由以后的服务端插件提供
    }

    public void serverAboutToStart(FMLServerAboutToStartEvent event) {

    }

    // register server commands in this event handler
    public void serverStarting(FMLServerStartingEvent event) {
        // /aemobile stats —— 打印 TCP 推送统计（扫描耗时 / 帧体积 / 调度占用）
        event.registerServerCommand(new dev.youtiao.aemobile.command.CommandAEMobileServer());
    }

    /** 事件处理器只注册一次：单机退出到主菜单再进另一个世界时 serverStarted 会再跑一遍 */
    private static boolean eventsRegistered = false;

    public void serverStarted(FMLServerStartedEvent event) {
        // 只有**专用服务器**才起 TCP 监听和采集。
        // 单人游戏里这个事件也会触发（集成服务器），以前会把 44444 也监听起来 ——
        // 玩家自己开单机时白占一个端口，而且客户端 jar 里根本不需要这套东西。
        // 注意：FMLServerStartedEvent 没有 getServer()，得从 MinecraftServer 拿。
        net.minecraft.server.MinecraftServer mcServer = net.minecraft.server.MinecraftServer.getServer();
        if (mcServer == null || !mcServer.isDedicatedServer()) return;
        // 协议 3.0.0：不再起 Spring/Tomcat，直接开 TCP 监听（端口见 config 的 [server] port）
        // 启动前先从 playerdata 恢复 token 表和监控器坐标表：
        // 不这么做的话，重启后没人走到监控器附近时 App 会误报「密码错误」。
        dev.youtiao.aemobile.net.MonitorRegistry.loadFromPlayerData();
        TcpServer.start(AEMobile.port);
        // TPS/MSPT 采集（总体 + 逐维度）：两个 TickEvent 都在主线程，只累加 long
        dev.youtiao.aemobile.metrics.WorldNames.init(net.minecraft.server.MinecraftServer.getServer());
        dev.youtiao.aemobile.metrics.TickStats.get().reset();
        if (!eventsRegistered) {
            cpw.mods.fml.common.FMLCommonHandler.instance().bus()
                    .register(dev.youtiao.aemobile.metrics.TickStats.get());
            // 聊天通道：ServerChatEvent 在 Forge 总线上（不是 FML 总线），广播排空在 tick 上
            MinecraftForge.EVENT_BUS.register(new dev.youtiao.aemobile.net.ChatHub.ChatListener());
            cpw.mods.fml.common.FMLCommonHandler.instance().bus()
                    .register(new dev.youtiao.aemobile.net.ChatHub.TickHandler());
            // 玩家加入/退出（只进 App 聊天流）
            cpw.mods.fml.common.FMLCommonHandler.instance().bus()
                    .register(new dev.youtiao.aemobile.net.ChatHub.PlayerEvents());
            // debug 命令桥（默认关闭）：把命令队列交给主线程执行，只能在 tick 里做
            cpw.mods.fml.common.FMLCommonHandler.instance().bus()
                    .register(new dev.youtiao.aemobile.debug.DebugBridge.ServerTickHandler());
            eventsRegistered = true;
        }
        dev.youtiao.aemobile.net.ChatHub.start();
        dev.youtiao.aemobile.debug.DebugBridge.startServer();
        // 聊天能力是「探测到才有」：没装 GTNHChat 时队伍/服务器/世界标签整体关闭，
        // 手机端靠握手里的 caps 知道这一点。这一行让服主一眼看出当前是哪一档。
        AEMobile.info("[AEMobile] " + dev.youtiao.aemobile.compat.GtnChatCompat.describe());
    }

    public void serverStopping(FMLServerStoppingEvent event) {
        dev.youtiao.aemobile.debug.DebugBridge.stop();
        TcpServer.stop();
        dev.youtiao.aemobile.net.ChatHub.stop();
        dev.youtiao.aemobile.metrics.TickStats.get().reset();
        // 单机切世界是同一个 JVM：不清表会把上一张存档的 token 带进下一张
        dev.youtiao.aemobile.net.AuthTokens.clear();
        dev.youtiao.aemobile.net.MonitorRegistry.clear();
        dev.youtiao.aemobile.blocks.TileAEMonitor.tilesInTheWorld.clear();
    }

    public void serverStopped(FMLServerStoppedEvent event) {

    }
}
