package dev.youtiao.aemobile.command;

import dev.youtiao.aemobile.debug.DebugBridge;
import dev.youtiao.aemobile.net.Metrics;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

/**
 * 服务端命令：{@code /aemobile stats} / {@code /aemobile stats reset}。
 *
 * <p>把 {@link Metrics} 的累计统计打到控制台（同时写一份 {@code dumps/aemobile/stats.json}）。
 * 客户端也有个同名命令（{@code client/CommandDumpIcons}），但在专用服务器上没有客户端命令处理器，
 * 不会冲突；在单人游戏里客户端命令优先，用 debug 命令桥跑服务端版本即可。
 */
public class CommandAEMobileServer extends CommandBase {

    @Override
    public String getCommandName() {
        return "aemobile";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/aemobile stats [reset] —— 打印 TCP 推送统计（扫描耗时 / 帧体积 / 调度占用）";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void processCommand(ICommandSender sender, String[] args) {
        String sub = args.length == 0 ? "stats" : args[0].toLowerCase();
        if ("reset".equals(sub)) {
            Metrics.reset();
            sender.addChatMessage(new ChatComponentText(EnumChatFormatting.GREEN + "[AEMobile] 统计已清零"));
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
}
