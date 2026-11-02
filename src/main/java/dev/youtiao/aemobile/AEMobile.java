package dev.youtiao.aemobile;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.*;
import net.minecraftforge.common.config.Configuration;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 版本策略（2026-11-01）：
 *
 * <p>{@code acceptableRemoteVersions = "[3.4.0,)"} —— 客户端只要 **≥ 3.4.0** 就能连，
 * 服务端后续升级版本也不再断开旧客户端连接。Forge 默认要求两边版本**完全一致**，所以以前每次发版
 * 玩家都得换 jar，这是「模组服分发模组很麻烦」的根源。
 *
 * <p>能这么放宽是因为：App 走的是**裸 TCP**（端口见 config），聊天/TPS/AE 查询这些功能
 * 一个字节都不经过 Minecraft 网络，客户端 mod 只负责把管理器方块画出来。
 * 所以只要**方块注册名、TE id、贴图路径**不变，客户端 jar 可以一直用同一个。
 *
 * <p>唯一还会逼客户端更新的情况：**新增方块或方块 GUI**。加设备前先想清楚，
 * 优先做成「同一个方块 + NBT 区分类型」。
 */
@Mod(modid = Tags.MODID, version = Tags.VERSION, name = Tags.MODNAME, acceptedMinecraftVersions = "[1.7.10]",
        acceptableRemoteVersions = "[3.4.0,)")
public class AEMobile {

    private static Logger LOG = LogManager.getLogger(Tags.MODID);

    /**
     * TCP 监听端口（协议 3.0.0）。在 config/AEMobile.cfg 的 {@code [server] port} 里改，
     * 默认 44444。App 里填的端口必须和这里一致。
     */
    public static int port;

    /**
     * debug 命令桥开关（config 的 {@code [debug] enabled}）。
     * 打开后 mod 会监听 {@code dumps/aemobile/cmd.txt} / {@code cmd-client.txt}，
     * 把文件里的每一行当成命令执行 —— 给维护者/AI 用，**线上默认关闭**。
     */
    public static boolean debugEnabled;

    /** 是否定期把运行时统计写到 {@code dumps/aemobile/stats.json}（{@code [metrics] enabled}） */
    public static boolean statsEnabled = true;

    /** 统计落盘间隔（秒），默认 60 */
    public static int statsIntervalSec = 60;

    @SidedProxy(clientSide= Tags.GROUPNAME + ".ClientProxy", serverSide=Tags.GROUPNAME + ".CommonProxy")
    public static CommonProxy proxy;

    @Mod.EventHandler
    // preInit "Run before anything else. Read your config, create blocks, items,
    // etc, and register them with the GameRegistry."
    public void preInit(FMLPreInitializationEvent event) {
        Configuration config = new Configuration(event.getSuggestedConfigurationFile());
        config.load();
        // 协议 3.0.0：mod 自行监听一个裸 TCP 端口（不再有 Tomcat/WebUI）。
        // 端口占用会导致启动失败（仅输出日志，不影响服务器本体），需要在这里改。
        port = config.get("server", "port", 44444,
                "TCP port of the AEMobile protocol (the port you type in the app). Default 44444.").getInt();
        // 运行时统计：只累加计数，不改变任何行为，用来判断该优化哪一段（默认开）
        statsEnabled = config.getBoolean("enabled", "metrics", true,
                "Write runtime stats to dumps/aemobile/stats.json every few seconds.");
        statsIntervalSec = config.getInt("intervalSeconds", "metrics", 60, 5, 3600,
                "How often stats.json is refreshed (seconds).");
        // debug 命令桥：监听 dumps/aemobile/cmd.txt 并执行里面的命令。线上保持 false。
        debugEnabled = config.getBoolean("enabled", "debug", false,
                "Enable the file-driven command bridge (dumps/aemobile/cmd.txt). Keep it false on public servers.");
        config.save();
        proxy.preInit(event);
    }

    @Mod.EventHandler
    // load "Do your mod setup. Build whatever data structures you care about. Register recipes."
    public void init(FMLInitializationEvent event) {
        proxy.init(event);
    }

    @Mod.EventHandler
    // postInit "Handle interaction with other mods, complete your setup based on this."
    public void postInit(FMLPostInitializationEvent event) {
        proxy.postInit(event);
    }

    @Mod.EventHandler
    public void serverAboutToStart(FMLServerAboutToStartEvent event) {
        proxy.serverAboutToStart(event);
    }

    @Mod.EventHandler
    // register server commands in this event handler
    public void serverStarting(FMLServerStartingEvent event) {
        proxy.serverStarting(event);
    }

    @Mod.EventHandler
    public void serverStarted(FMLServerStartedEvent event) {
        proxy.serverStarted(event);
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        proxy.serverStopping(event);
    }

    @Mod.EventHandler
    public void serverStopped(FMLServerStoppedEvent event) {
        proxy.serverStopped(event);
    }

    public static void debug(String message) {
        LOG.debug(message);
    }

    public static void info(String message) {
        LOG.info(message);
    }

    public static void warn(String message) {
        LOG.warn(message);
    }

    public static void error(String message) {
        LOG.error(message);
    }

    public static void error(String message, Throwable t) {
        LOG.error(message, t);
    }
}