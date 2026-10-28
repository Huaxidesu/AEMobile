package dev.youtiao.aemobile.client;

import dev.youtiao.aemobile.AEMobile;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 客户端命令：把物品图标导出成 PNG，供 App 的图标库使用。
 *
 * <pre>
 * /aemobile dumpicons            导出全部注册物品（含所有 meta），128px
 * /aemobile dumpicons 64         同上，图标边长 64px
 * /aemobile dumpicons list       只导出 config/aemobile-dump-items.txt 里列出的物品（每行 "mod:item meta"）
 * /aemobile dumpicons list 64    同上 + 指定尺寸
 * </pre>
 *
 * 产物：<游戏目录>/dumps/aemobile/items/<modid>/<item>_<meta>.png + manifest.jsonl
 * 然后跑 tools/build_item_icons.py 合并进 App 的 itempanel.db。
 */
public class CommandDumpIcons extends CommandBase {

    /** 待导出清单：每行 "mod:item meta"（# 开头是注释） */
    private static final String LIST_FILE = "config/aemobile-dump-items.txt";

    @Override
    public String getCommandName() {
        return "aemobile";
    }

    @Override
    public String getCommandUsage(ICommandSender sender) {
        return "/aemobile dumpicons [list] [尺寸]";
    }

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
        if (args.length == 0 || !"dumpicons".equalsIgnoreCase(args[0])) {
            msg(EnumChatFormatting.YELLOW + getCommandUsage(sender));
            return;
        }
        boolean fromList = false;
        int size = 128;
        for (int i = 1; i < args.length; i++) {
            if ("list".equalsIgnoreCase(args[i])) fromList = true;
            else {
                try {
                    size = Integer.parseInt(args[i]);
                } catch (NumberFormatException ignored) {
                }
            }
        }
        size = Math.max(16, Math.min(256, size));

        List<ItemStack> items;
        try {
            items = fromList ? readList() : allItems();
        } catch (Throwable t) {
            msg(EnumChatFormatting.RED + "收集物品列表失败：" + t);
            AEMobile.error("[AEMobile] 收集物品列表失败: " + t, t);
            return;
        }
        if (items.isEmpty()) {
            msg(EnumChatFormatting.RED + "没有可导出的物品"
                    + (fromList ? "（检查 " + LIST_FILE + "）" : ""));
            return;
        }

        File dir = new File(Minecraft.getMinecraft().mcDataDir, "dumps/aemobile/items");
        msg(EnumChatFormatting.GREEN + "开始导出 " + items.size() + " 个物品图标（" + size + "px）→ "
                + dir.getAbsolutePath());
        msg(EnumChatFormatting.GRAY + "导出期间画面会花屏，几秒到几十秒；按 Esc 可中止。");
        Minecraft.getMinecraft().displayGuiScreen(new GuiIconDumper(items, dir, size));
    }

    private static void msg(String s) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.thePlayer != null) mc.thePlayer.addChatMessage(new ChatComponentText("[AEMobile] " + s));
    }

    /** 全部注册物品 × 所有 meta（getSubItems 能拿到的都算上） */
    private List<ItemStack> allItems() {
        List<ItemStack> out = new ArrayList<ItemStack>();
        Set<String> seen = new HashSet<String>();
        for (Object o : Item.itemRegistry) {
            if (!(o instanceof Item)) continue;
            Item item = (Item) o;
            String name = Item.itemRegistry.getNameForObject(item);
            if (name == null) continue;
            List<ItemStack> sub = new ArrayList<ItemStack>();
            try {
                //noinspection unchecked
                item.getSubItems(item, null, sub);
            } catch (Throwable ignored) {
            }
            if (sub.isEmpty()) sub.add(new ItemStack(item, 1, 0));
            for (ItemStack st : sub) {
                if (st == null) continue;
                if (seen.add(name + "|" + st.getItemDamage())) out.add(st);
            }
        }
        Collections.sort(out, (a, b) -> {
            String na = String.valueOf(Item.itemRegistry.getNameForObject(a.getItem()));
            String nb = String.valueOf(Item.itemRegistry.getNameForObject(b.getItem()));
            int c = na.compareTo(nb);
            return c != 0 ? c : Integer.compare(a.getItemDamage(), b.getItemDamage());
        });
        return out;
    }

    /** 只导出清单里列的（App 的库存里差图标的那批，体积小、跑得快） */
    private List<ItemStack> readList() throws Exception {
        File f = new File(Minecraft.getMinecraft().mcDataDir, LIST_FILE);
        List<ItemStack> out = new ArrayList<ItemStack>();
        if (!f.isFile()) throw new IllegalStateException("找不到 " + f.getAbsolutePath());
        BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8));
        try {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue;
                String[] p = line.split("\\s+");
                Object obj = Item.itemRegistry.getObject(p[0]);
                if (!(obj instanceof Item)) continue;
                int meta = 0;
                if (p.length > 1) {
                    try {
                        meta = Integer.parseInt(p[1]);
                    } catch (NumberFormatException ignored) {
                    }
                }
                out.add(new ItemStack((Item) obj, 1, meta));
            }
        } finally {
            r.close();
        }
        return out;
    }
}
