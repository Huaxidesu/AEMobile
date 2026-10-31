package dev.youtiao.aemobile.debug;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.stats.Achievement;
import net.minecraft.stats.AchievementList;
import net.minecraftforge.client.ClientCommandHandler;

import java.util.List;
import java.util.Locale;

/**
 * 客户端调试通道：执行 {@code cmd-client.txt} 里的命令，代替玩家操作，用来跑自动化测试。
 *
 * <p>为什么不直接 {@code sendChatMessage}：客户端专属命令（{@code /aemobile dumpicons}）
 * 走 {@code ClientCommandHandler}，发给服务端那边没有；而服务端命令发给客户端处理器
 * 又没人认领。所以先给客户端处理器，返回 0（没人认领）再当聊天发出去。
 *
 * <p>命令一览（一行一条，写进 {@code <客户端>/.minecraft/dumps/aemobile/cmd-client.txt}）：
 *
 * <pre>
 * chat &lt;文本&gt;        以玩家身份发一条聊天（走 ServerChatEvent，和真人打字等价）
 * cmd &lt;命令&gt;         执行命令：客户端命令优先，没人认领就发给服务端
 * info                玩家名 / 坐标 / 维度 / 世界 / 血量 / 饥饿 / 游戏模式
 * inv                 背包 36 格（物品名带本地化，正好验证汉化）
 * hand                手持物品（显示名 + 注册名 + meta）
 * ach &lt;成就ID&gt;        触发一个成就（如 achievement.mineWood）
 * chatlog [n]         最近 n 条聊天（默认 10，纯文本）
 * chatjson [n]        最近 n 条聊天的组件 JSON（核对 hoverEvent / clickEvent）
 * players             当前世界的玩家名
 * biome               所在生物群系
 * </pre>
 *
 * <p>结果追加到同目录的 {@code cmd-client.out}，多行用 {@code | } 连接。
 * 只由 {@code ClientProxy} 引用，专用服务器上不会被加载。
 */
public final class ClientDebugBridge {

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getMinecraft();
        if (mc == null) return;
        String line = DebugBridge.pollClient();
        if (line == null) return;

        long t0 = System.currentTimeMillis();
        String result;
        if (mc.thePlayer == null || mc.theWorld == null) {
            // 主菜单时只放行 connect：自动化要先能自己进服
            String head = line.trim().toLowerCase(Locale.ROOT);
            result = head.startsWith("connect")
                ? connect(mc, line.trim().substring("connect".length()).trim())
                : "not-in-game";
        } else {
            try {
                result = exec(mc, line);
            } catch (Throwable t) {
                result = "error=" + t;
            }
        }
        DebugBridge.appendClient(line, result, System.currentTimeMillis() - t0);
    }

    private static String exec(Minecraft mc, String line) {
        String cmd = line;
        String arg = "";
        int sp = line.indexOf(' ');
        if (sp > 0) {
            cmd = line.substring(0, sp);
            arg = line.substring(sp + 1).trim();
        }
        switch (cmd.toLowerCase(Locale.ROOT)) {
            case "chat":
                return chat(mc, arg);
            case "cmd":
                return command(mc, arg);
            case "info":
                return info(mc);
            case "inv":
                return inventory(mc);
            case "hand":
                return hand(mc);
            case "ach":
                return achievement(mc, arg);
            case "chatlog":
                return chatLog(arg, false);
            case "chatjson":
                return chatLog(arg, true);
            case "players":
                return players(mc);
            case "biome":
                return biome(mc);
            default:
                // 不带子命令时保持老行为：整行当命令处理
                return command(mc, line);
        }
    }

    // ------------------------------------------------------------ 动作

    /**
     * 主菜单连服：{@code connect <host> [port]}。
     *
     * <p>用 Forge 的 {@code FMLClientHandler.connectToServer}，等价于点多人游戏里的服务器条目。
     * 自动化测试靠它从主菜单直接进服，不然要人点一下。
     */
    private static String connect(Minecraft mc, String arg) {
        if (arg.isEmpty()) return "usage: connect <host> [port]";
        String[] parts = arg.trim().split(" +");
        String host = parts[0];
        int port = 25565;
        if (parts.length > 1) {
            try {
                port = Integer.parseInt(parts[1]);
            } catch (Throwable t) {
                return "bad-port: " + parts[1];
            }
        }
        try {
            cpw.mods.fml.client.FMLClientHandler.instance().connectToServer(
                mc.currentScreen,
                new net.minecraft.client.multiplayer.ServerData("aemobile-test", host + ":" + port));
            return "connecting " + host + ":" + port;
        } catch (Throwable t) {
            return "connect-error=" + t;
        }
    }

    /** 以玩家身份发聊天：服务端会走 ServerChatEvent，和真人打字完全一样 */
    private static String chat(Minecraft mc, String text) {
        if (text.isEmpty()) return "empty-text";
        mc.thePlayer.sendChatMessage(text);
        return "chat-sent";
    }

    /** 客户端命令优先，没人认领再发给服务端 */
    private static String command(Minecraft mc, String cmd) {
        if (cmd.isEmpty()) return "empty-command";
        int r = ClientCommandHandler.instance.executeCommand(mc.thePlayer, cmd);
        if (r != 0) return "client-result=" + r;
        mc.thePlayer.sendChatMessage(cmd);
        return "sent-to-server";
    }

    /** 触发成就：1.7.10 的成就是客户端统计，addStat 会触发 Forge 的 AchievementEvent */
    private static String achievement(Minecraft mc, String statId) {
        if (statId.isEmpty()) return "usage: ach <achievement.id>";
        for (Object o : AchievementList.achievementList) {
            if (!(o instanceof Achievement)) continue;
            Achievement a = (Achievement) o;
            if (statId.equals(a.statId)) {
                mc.thePlayer.addStat(a, 1);
                return "achievement-triggered: " + statId;
            }
        }
        return "not-found: " + statId;
    }

    // ------------------------------------------------------------ 信息

    private static String info(Minecraft mc) {
        EntityPlayer p = mc.thePlayer;
        StringBuilder sb = new StringBuilder();
        sb.append("name=").append(p.getCommandSenderName());
        sb.append(" pos=").append(fmt(p.posX)).append(',').append(fmt(p.posY)).append(',').append(fmt(p.posZ));
        sb.append(" dim=").append(p.dimension);
        sb.append(" world=").append(worldName(mc));
        sb.append(" hp=").append(fmt(p.getHealth()));
        sb.append(" food=").append(p.getFoodStats().getFoodLevel());
        sb.append(" mode=").append(p.capabilities.isCreativeMode ? "creative" : "survival");
        return sb.toString();
    }

    private static String inventory(Minecraft mc) {
        ItemStack[] main = mc.thePlayer.inventory.mainInventory;
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (int i = 0; i < main.length; i++) {
            ItemStack s = main[i];
            if (s == null) continue;
            if (used++ > 0) sb.append('\n');
            sb.append(i).append(": ").append(describe(s));
        }
        return used == 0 ? "empty" : sb.toString();
    }

    private static String hand(Minecraft mc) {
        ItemStack held = mc.thePlayer.getHeldItem();
        return held == null ? "empty" : describe(held);
    }

    private static String players(Minecraft mc) {
        StringBuilder sb = new StringBuilder();
        for (Object o : mc.theWorld.playerEntities) {
            if (!(o instanceof EntityPlayer)) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(((EntityPlayer) o).getCommandSenderName());
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }

    private static String biome(Minecraft mc) {
        try {
            int x = (int) Math.floor(mc.thePlayer.posX);
            int z = (int) Math.floor(mc.thePlayer.posZ);
            return String.valueOf(mc.theWorld.getBiomeGenForCoords(x, z).biomeName);
        } catch (Throwable t) {
            return "unknown";
        }
    }

    private static String chatLog(String arg, boolean json) {
        int n = 10;
        try {
            if (!arg.isEmpty()) n = Integer.parseInt(arg.trim());
        } catch (Throwable ignored) {}
        List<ClientChatLog.Entry> list = ClientChatLog.recent(n);
        if (list.isEmpty()) return "empty";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append('\n');
            ClientChatLog.Entry e = list.get(i);
            sb.append(json ? e.json : e.text);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ 工具

    /** 物品描述：显示名（客户端已汉化）+ 注册名 + meta */
    private static String describe(ItemStack s) {
        String name;
        try {
            name = s.getDisplayName();
        } catch (Throwable t) {
            name = "?";
        }
        String id;
        try {
            id = String.valueOf(Item.itemRegistry.getNameForObject(s.getItem()));
        } catch (Throwable t) {
            id = "?";
        }
        return name + " [" + id + ":" + s.getItemDamage() + "] x" + s.stackSize;
    }

    private static String worldName(Minecraft mc) {
        try {
            String provider = mc.theWorld.provider.getDimensionName();
            String folder = mc.theWorld.provider.getSaveFolder();
            return provider + (folder == null || folder.isEmpty() ? "" : "(" + folder + ")");
        } catch (Throwable t) {
            return "?";
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.2f", v);
    }
}
