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

    }

    public void serverStarted(FMLServerStartedEvent event) {
        // 协议 3.0.0：不再起 Spring/Tomcat，直接开 TCP 监听（端口见 config 的 [server] port）
        // 启动前先从 playerdata 恢复 token 表和监控器坐标表：
        // 不这么做的话，重启后没人走到监控器附近时 App 会误报「密码错误」。
        dev.youtiao.aemobile.net.MonitorRegistry.loadFromPlayerData();
        TcpServer.start(AEMobile.port);
    }

    public void serverStopping(FMLServerStoppingEvent event) {
        TcpServer.stop();
    }

    public void serverStopped(FMLServerStoppedEvent event) {

    }
}
