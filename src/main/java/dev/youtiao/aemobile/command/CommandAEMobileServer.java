package dev.youtiao.aemobile.command;

import dev.youtiao.aemobile.debug.DebugBridge;
import dev.youtiao.aemobile.metrics.TickStats;
import dev.youtiao.aemobile.metrics.WorldNames;
import dev.youtiao.aemobile.net.ChatHub;
import dev.youtiao.aemobile.net.Metrics;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChunkCoordinates;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;
import net.minecraft.world.World;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 服务端命令：
 * <ul>
 *   <li>{@code /aemobile stats [reset]} —— TCP 推送统计（扫描耗时 / 帧体积 / 调度占用）；</li>
 *   <li>{@code /aemobile tps} —— 打印我们自己的 TPS/MSPT 表，<b>并顺手跑一次 {@code /cofh tps}
 *       把它的原文也打出来</b>，一条命令完成对表；结果同时写到
 *       {@code dumps/aemobile/tps-compare.txt}，AI/脚本不用解析聊天记录；</li>
 *   <li>{@code /aemobile dims} —— 导出维度清单（dimid / 世界名 / provider 名 / 最终显示名），
 *       补内置中文表时用。</li>
 * </ul>
 *
 * <p>客户端也有个同名命令（{@code client/CommandDumpIcons}），但在专用服务器上没有客户端命令处理器，
 * 不会冲突；在单人游戏里客户端命令优先，用 debug 命令桥跑服务端版本即可。
 */
public class CommandAEMobileServer extends CommandBase {

    private static final SimpleDateFormat TIME = new SimpleDateFormat("HH:mm:ss");
    /** 重置绑定码的冷却：同一个玩家 30 秒内只能一次 */
    private static final long TOKEN_RESET_COOLDOWN_MS = 30000L;
    private static final java.util.Map<java.util.UUID, Long> TOKEN_RESET_AT =
            new java.util.concurrent.ConcurrentHashMap<java.util.UUID, Long>();

    @Override
    public String getCommandName() {
        return "aemobile";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/aemobile token reset | stats [reset] | tps | dims | say <text> | debug <类型> <名字> [内容]";
    }

    /**
     * 整个命令对所有人可见，因为 {@code token reset} 是玩家自助功能；
     * 管理子命令在 {@link #processCommand} 里单独检查权限 2。
     */
    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean canCommandSenderUseCommand(ICommandSender sender) {
        return true;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String sub = args.length == 0 ? "stats" : args[0].toLowerCase(Locale.ROOT);
        if ("token".equals(sub)) {
            resetToken(sender, args);
            return;
        }
        if (!sender.canCommandSenderUseCommand(2, "aemobile")) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                    + "[AEMobile] 权限不足：这个子命令需要管理员"));
            return;
        }
        if ("reset".equals(sub) || ("stats".equals(sub) && args.length > 1 && "reset".equalsIgnoreCase(args[1]))) {
            Metrics.reset();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[AEMobile] 统计已清零"));
            return;
        }
        if ("tps".equals(sub)) {
            reportTps(sender);
            return;
        }
        if ("dims".equals(sub)) {
            reportDims(sender);
            return;
        }
        if ("say".equals(sub)) {
            broadcastTest(sender, args);
            return;
        }
        if ("debug".equals(sub)) {
            debugInject(sender, args);
            return;
        }
        if (!"stats".equals(sub)) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED + getCommandUsage(sender)));
            return;
        }
        // 顺手落盘一份，方便外部读取（AI/脚本不用解析聊天记录）
        Metrics.dump(DebugBridge.serverDir(), 0);
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.AQUA + "[AEMobile] " + Metrics.json()));
    }

    // ------------------------------------------------------------ token reset

    /**
     * {@code /aemobile token reset}：重置自己的绑定码。
     *
     * <p>新码只打在聊天栏（不写日志、不落盘）；旧码立刻注销，所有用它的连接立刻断开。
     */
    private void resetToken(ICommandSender sender, String[] args) {
        if (args.length < 2 || !"reset".equalsIgnoreCase(args[1])) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                    + "[AEMobile] 用法：/aemobile token reset"));
            return;
        }
        net.minecraft.entity.player.EntityPlayer player = sender instanceof net.minecraft.entity.player.EntityPlayer
                ? (net.minecraft.entity.player.EntityPlayer) sender
                : null;
        if (player == null) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                    + "[AEMobile] 只有游戏内玩家能重置自己的绑定码"));
            return;
        }

        java.util.UUID uuid = player.getUniqueID();
        long now = System.currentTimeMillis();
        Long last = uuid == null ? null : TOKEN_RESET_AT.get(uuid);
        if (last != null && now - last < TOKEN_RESET_COOLDOWN_MS) {
            long left = (TOKEN_RESET_COOLDOWN_MS - (now - last) + 999L) / 1000L;
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW
                    + "[AEMobile] 刚重置过，请等 " + left + " 秒再试"));
            return;
        }

        dev.youtiao.aemobile.net.MonitorRegistry.Reset reset =
                dev.youtiao.aemobile.net.MonitorRegistry.reset(player);
        if (reset == null) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                    + "[AEMobile] 重置失败：读取玩家数据出错"));
            return;
        }
        if (uuid != null) TOKEN_RESET_AT.put(uuid, now);
        dev.youtiao.aemobile.net.TcpServer.closeByToken(reset.oldToken);

        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN
                + "[AEMobile] 绑定码已重置：" + reset.newToken));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.YELLOW
                + "[AEMobile] 旧码已立即失效，所有 App 设备需要用新码重新连接。"));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY
                + "[AEMobile] 已同步 " + reset.monitors + " 个监控器"));
        dev.youtiao.aemobile.AEMobile.info("[AEMobile] " + player.getCommandSenderName()
                + " 重置了绑定码（" + reset.monitors + " 个监控器）");
    }

    // ------------------------------------------------------------ say
    /**
     * {@code /aemobile say <text>}：从服务端广播一行聊天，用来验证「服务器聊天进 App」。
     * 权限 2；真正的广播还是走 {@code sendChatMsg}，所以顺带覆盖了 Mixin 的抓取路径。
     */
    private void broadcastTest(ICommandSender sender, String[] args) {
        if (args.length < 2) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                    + "[AEMobile] 用法：/aemobile say <text>"));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < args.length; i++) {
            if (i > 1) sb.append(' ');
            sb.append(args[i]);
        }
        MinecraftServer server = MinecraftServer.getServer();
        if (server == null || server.getConfigurationManager() == null) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                    + "[AEMobile] 服务器还没准备好"));
            return;
        }
        server.getConfigurationManager().sendChatMsg(new ChatComponentText(sb.toString()));
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN
                + "[AEMobile] 已广播：" + sb));
    }

    // ------------------------------------------------------------ debug 注入

    /**
     * {@code /aemobile debug <类型> <名字> [内容]}：直接往聊天流里造一条消息。
     *
     * <p>本机测试服没有真实玩家时，`ServerChatEvent` / `AchievementEvent` / 进出事件都走不到，
     * 手机端就没法验证。这里绕过事件源直接塞给 {@code ChatHub}，
     * App 收到的东西和真玩家发的一样（同样的 kind、同样的颜色码处理）。
     *
     * <p>类型：{@code chat}（游戏公屏）/ {@code app}（手机）/ {@code join} / {@code leave}
     * / {@code server}（服务器广播）/ {@code achievement}（成就，内容是成就名）。
     */
    private void debugInject(ICommandSender sender, String[] args) {
        if (args.length < 3) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                    + "[AEMobile] 用法：/aemobile debug chat|app|join|leave|server|achievement <名字> [内容]"));
            return;
        }
        String what = args[1].toLowerCase(Locale.ROOT);
        int kind;
        if ("chat".equals(what)) kind = ChatHub.KIND_GAME;
        else if ("app".equals(what)) kind = ChatHub.KIND_APP;
        else if ("join".equals(what)) kind = ChatHub.KIND_JOIN;
        else if ("leave".equals(what)) kind = ChatHub.KIND_LEAVE;
        else if ("server".equals(what)) kind = ChatHub.KIND_SERVER;
        else if ("achievement".equals(what)) kind = ChatHub.KIND_ACHIEVEMENT;
        else {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                    + "[AEMobile] 未知类型：" + what
                    + "（chat/app/join/leave/server/achievement）"));
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 3; i < args.length; i++) {
            if (i > 3) sb.append(' ');
            sb.append(args[i]);
        }
        long seq = ChatHub.inject(kind, args[2], sb.toString());
        if (seq < 0) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.RED
                    + "[AEMobile] 聊天功能没开（config [chat] enabled=false）"));
            return;
        }
        sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN
                + "[AEMobile] 已注入 kind=" + kind + " seq=" + seq + " 名=" + args[2]));
    }

    // ------------------------------------------------------------ tps

    private void reportTps(ICommandSender sender) {
        TickStats.Snapshot snap = TickStats.get().snapshot();
        List<String> out = new ArrayList<String>();
        out.add("# AEMobile TPS 对表  " + TIME.format(new Date()));
        if (snap == null || snap.at == 0) {
            out.add("采集还没开始（服务端刚启动？等两秒再试）");
            emit(sender, out, "tps-compare.txt");
            return;
        }

        out.add("");
        out.add("## 我们自己（Forge TickEvent 计时，最近 5 秒窗口）");
        out.add(String.format(Locale.ROOT, "总体      TPS %6.2f  MSPT %7.2f  MAX %7.2f  ticks %d  在线 %d",
                snap.srvTps, snap.srvMspt, snap.srvMax, snap.srvTicks, snap.players));
        out.add(String.format(Locale.ROOT, "%-10s %-22s %7s %7s %7s %5s %5s %6s",
                "dim", "name", "TPS", "MSPT", "MAX", "P", "E", "chunk"));
        for (TickStats.WorldSnap w : snap.worlds) {
            out.add(String.format(Locale.ROOT, "%-10d %-22s %7.2f %7.2f %7.2f %5d %5d %6d  %s",
                    w.dim, w.name, w.tps, w.mspt, w.max, w.players, w.entities, w.chunks,
                    w.personal ? "(家园)" : ""));
        }

        // 对表：执行一次 cofh tps，把它的聊天输出抓下来
        out.add("");
        out.add("## /cofh tps 原文");
        long t0 = System.nanoTime();
        List<String> cofh = runCaptured(sender, "cofh tps");
        long ms = (System.nanoTime() - t0) / 1000000L;
        if (cofh.isEmpty()) {
            out.add("（没抓到输出：可能没装 CoFHCore，或它不走 addChatMessage）");
        } else {
            out.addAll(cofh);
        }
        out.add(String.format(Locale.ROOT, "（执行耗时 %d ms）", ms));

        emit(sender, out, "tps-compare.txt");
    }

    /** 用一个「把消息收集起来」的假 sender 执行命令，拿到原始输出 */
    private List<String> runCaptured(ICommandSender delegate, String command) {
        List<String> lines = new ArrayList<String>();
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null) return lines;
            server.getCommandManager().executeCommand(new CapturingSender(delegate, lines), command);
        } catch (Throwable t) {
            lines.add("执行失败: " + t);
        }
        return lines;
    }

    // ------------------------------------------------------------ dims

    private void reportDims(ICommandSender sender) {
        TickStats.Snapshot snap = TickStats.get().snapshot();
        List<String> out = new ArrayList<String>();
        out.add("# 维度清单  " + TIME.format(new Date())
                + "  （Multiverse alias 已加载 " + WorldNames.aliasCount() + " 条）");
        out.add(String.format(Locale.ROOT, "%-6s %-24s %-26s %-16s %s", "dim", "world", "provider", "display", "flags"));
        if (snap != null) {
            for (TickStats.WorldSnap w : snap.worlds) {
                out.add(String.format(Locale.ROOT, "%-6d %-24s %-26s %-16s %s",
                        w.dim, w.rawName, w.provider, w.name,
                        (w.personal ? "personal " : "") + (w.loaded ? "" : "unloaded")));
            }
        }
        out.add("");
        out.add("补名字：改 config/AEMobile.cfg 的 [tps] names（DIM70=暮色森林），或改 Multiverse 的 alias");
        emit(sender, out, "dims.txt");
    }

    // ------------------------------------------------------------ 输出

    /** 打到聊天栏（最多 30 行，避免刷屏）+ 全量写文件 */
    private void emit(ICommandSender sender, List<String> lines, String fileName) {
        int shown = Math.min(lines.size(), 30);
        for (int i = 0; i < shown; i++) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY + lines.get(i)));
        }
        if (lines.size() > shown) {
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GRAY
                    + "… 还有 " + (lines.size() - shown) + " 行，见 dumps/aemobile/" + fileName));
        }
        File dir = DebugBridge.serverDir();
        if (dir == null) return;
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            Writer w = new OutputStreamWriter(new FileOutputStream(new File(dir, fileName)), "UTF-8");
            try {
                for (String line : lines) {
                    w.write(line);
                    w.write('\n');
                }
            } finally {
                w.close();
            }
        } catch (Exception ignored) {
        }
    }

    /**
     * 只做一件事：把 {@code addChatMessage} 的内容收集到 list 里。
     * 其余方法原样转发给真正的 sender（权限按最高给，否则 {@code cofh tps} 会被拒）。
     */
    private static final class CapturingSender implements ICommandSender {
        private final ICommandSender delegate;
        private final List<String> lines;

        CapturingSender(ICommandSender delegate, List<String> lines) {
            this.delegate = delegate;
            this.lines = lines;
        }

        @Override
        public String getCommandSenderName() {
            return delegate.getCommandSenderName();
        }

        @Override
        public IChatComponent func_145748_c_() {
            return delegate.func_145748_c_();
        }

        @Override
        public void addChatMessage(IChatComponent component) {
            lines.add(component == null ? "" : component.getUnformattedText());
        }

        @Override
        public boolean canCommandSenderUseCommand(int level, String command) {
            return true;
        }

        @Override
        public ChunkCoordinates getPlayerCoordinates() {
            return delegate.getPlayerCoordinates();
        }

        @Override
        public World getEntityWorld() {
            return delegate.getEntityWorld();
        }
    }
}
