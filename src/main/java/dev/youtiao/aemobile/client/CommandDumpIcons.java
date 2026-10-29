package dev.youtiao.aemobile.client;

import dev.youtiao.aemobile.AEMobile;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.client.renderer.texture.TextureMap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IIcon;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidRegistry;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 客户端命令：把图标数据导出给 App 的图标库用。
 *
 * <pre>
 * /aemobile dump all [尺寸]      物品 + 流体 + 源质 全导
 * /aemobile dump items [尺寸]    物品图标 PNG（默认 128px）
 * /aemobile dump fluids          流体颜色表
 * /aemobile dump aspects         源质颜色表
 * /aemobile dumpicons [list] [尺寸]   老入口 = dump items（list：只导 config/aemobile-dump-items.txt）
 * /aemobile dumpcolors           老入口 = dump fluids aspects
 * </pre>
 *
 * 产物：<游戏目录>/dumps/aemobile/
 *   items/<modid>/<item>_<meta>.png + manifest.jsonl
 *   fluid-colors.tsv、aspect-colors.tsv
 * 然后跑 tools/build_item_icons.py / build_fluid_icons.py / build_aspects.py 合并进 App 的图标库。
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
        return "/aemobile dump <items|fluids|aspects|all> [尺寸]（dumpicons = dump items，dumpcolors = dump fluids aspects）";
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
        if (args.length == 0) {
            msg(EnumChatFormatting.YELLOW + getCommandUsage(sender));
            return;
        }
        // /aemobile dump <items|fluids|aspects|all> [尺寸]
        if ("dump".equalsIgnoreCase(args[0])) {
            boolean items = false, fluids = false, aspects = false;
            int size = 128;
            for (int i = 1; i < args.length; i++) {
                String a = args[i].toLowerCase(Locale.ENGLISH);
                if ("items".equals(a) || "item".equals(a)) items = true;
                else if ("fluids".equals(a) || "fluid".equals(a)) fluids = true;
                else if ("aspects".equals(a) || "aspect".equals(a)) aspects = true;
                else if ("all".equals(a)) items = fluids = aspects = true;
                else {
                    try {
                        size = Integer.parseInt(a);
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (!items && !fluids && !aspects) {
                msg(EnumChatFormatting.YELLOW + getCommandUsage(sender));
                return;
            }
            if (fluids || aspects) dumpColors(fluids, aspects);
            if (items) startItemDump(false, size);
            return;
        }
        // 老入口：dumpcolors = 流体颜色 + 源质颜色
        if ("dumpcolors".equalsIgnoreCase(args[0])) {
            dumpColors(true, true);
            return;
        }
        if (!"dumpicons".equalsIgnoreCase(args[0])) {
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
        startItemDump(fromList, size);
    }

    /** 渲染物品图标（要开 Framebuffer，所以做成 GuiScreen 一帧一屏地跑） */
    private void startItemDump(boolean fromList, int size) {
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

    /**
     * 导出流体 / 源质的颜色。
     *
     * <p>为什么颜色要游戏来给：这两类贴图都是**灰度/白色蒙版**，颜色是渲染时乘上去的
     * （`Fluid.getColor()` / `Aspect.getColor()`），jar 里没有彩色版本。所以「贴图从 jar 抽、
     * 颜色从游戏导」，再由 tools/build_fluid_icons.py / build_aspects.py 相乘烘成彩色 PNG。
     *
     * <p>这一步不需要 GL，主线程跑几百毫秒。
     */
    private void dumpColors(boolean doFluids, boolean doAspects) {
        File dir = new File(Minecraft.getMinecraft().mcDataDir, "dumps/aemobile");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        if (doFluids) {
            dumpFluidColors(new File(dir, "fluid-colors.tsv"));
            dumpFluidAtlas(dir);
        }
        if (doAspects) dumpAspectColors(dir);
    }

    /**
     * 把方块贴图图集（blocks atlas）整个读出来，并记下每个流体图标在图集里的位置。
     *
     * <p>为什么不再靠「贴图 × 颜色」算：GT 的流体颜色并不都在 {@code Fluid.getColor()} 里
     * （实测水、岩浆、熔融金属全是 FFFFFF），颜色是贴图/渲染阶段就定了的。
     * 与其猜，不如直接把游戏里真正画出来的那张图集读回来，Python 端按坐标裁就行。
     *
     * <p>产物：{@code atlas.raw}（BGRA，行序从下往上，GL 原始顺序）+ {@code fluid-atlas.tsv}
     * （`名字<TAB>x<TAB>y<TAB>宽<TAB>高`，y 已换算成「左上角为原点」的图片坐标）。
     */
    private void dumpFluidAtlas(File dir) {
        Minecraft mc = Minecraft.getMinecraft();
        if (mc.getTextureMapBlocks() == null) {
            msg(EnumChatFormatting.RED + "贴图图集还没加载，先进世界再跑 dump fluids");
            return;
        }
        Map<String, Fluid> fluids = allFluids();
        if (fluids.isEmpty()) {
            msg(EnumChatFormatting.RED + "拿不到流体注册表，流体图标没导");
            return;
        }

        int w, h;
        ByteBuffer buf;
        try {
            mc.getTextureManager().bindTexture(TextureMap.locationBlocksTexture);
            w = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
            h = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);
            if (w <= 0 || h <= 0) {
                msg(EnumChatFormatting.RED + "读不到图集尺寸（" + w + "x" + h + "）");
                return;
            }
            buf = BufferUtils.createByteBuffer(w * h * 4);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL11.GL_UNSIGNED_BYTE, buf);
        } catch (Throwable t) {
            msg(EnumChatFormatting.RED + "读图集失败：" + t);
            AEMobile.error("[AEMobile] 读图集失败: " + t, t);
            return;
        }

        File raw = new File(dir, "atlas.raw");
        File tsv = new File(dir, "fluid-atlas.tsv");
        int n = 0;
        try {
            FileOutputStream fos = new FileOutputStream(raw);
            try {
                byte[] chunk = new byte[1024 * 1024];
                while (buf.hasRemaining()) {
                    int len = Math.min(chunk.length, buf.remaining());
                    buf.get(chunk, 0, len);
                    fos.write(chunk, 0, len);
                }
            } finally {
                fos.close();
            }

            PrintWriter pw = new PrintWriter(new OutputStreamWriter(new FileOutputStream(tsv),
                    StandardCharsets.UTF_8));
            try {
                pw.println("# atlas\t" + w + "\t" + h);
                pw.println("# <流体名>\t<x>\t<y>\t<宽>\t<高>");
                for (Fluid f : fluids.values()) {
                    if (f == null || f.getName() == null) continue;
                    IIcon icon = null;
                    try {
                        icon = f.getStillIcon();
                    } catch (Throwable ignored) {
                    }
                    if (icon == null) continue;
                    int iw = icon.getIconWidth(), ih = icon.getIconHeight();
                    if (iw <= 0 || ih <= 0) continue;
                    int x = Math.round(icon.getMinU() * w);
                    // GL 的 v 轴朝上，图片坐标朝下：图标上边缘 = 1 - maxV
                    int y = Math.round((1.0F - icon.getMaxV()) * h);
                    pw.println(f.getName() + "\t" + x + "\t" + y + "\t" + iw + "\t" + ih);
                    n++;
                }
            } finally {
                pw.close();
            }
        } catch (Throwable t) {
            msg(EnumChatFormatting.RED + "写图集失败：" + t);
            AEMobile.error("[AEMobile] 写图集失败: " + t, t);
            return;
        }
        msg(EnumChatFormatting.GREEN + "图集 " + w + "x" + h + "，记下 " + n + " 个流体图标位置 → "
                + raw.getAbsolutePath());
    }

    private void dumpFluidColors(File out) {
        Map<String, Fluid> fluids = allFluids();
        if (fluids.isEmpty()) {
            msg(EnumChatFormatting.RED + "拿不到流体注册表（FluidRegistry 结构变了），流体颜色没导");
            return;
        }

        PrintWriter w = null;
        int n = 0;
        try {
            w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8));
            w.println("# /aemobile dump fluids —— 给 tools/build_fluid_icons.py 烘颜色用");
            w.println("# <流体名>\t<RRGGBB>");
            for (Fluid f : fluids.values()) {
                if (f == null) continue;
                String name = f.getName();
                if (name == null || name.isEmpty()) continue;
                int color = 0xFFFFFF;
                try {
                    color = f.getColor() & 0xFFFFFF;
                } catch (Throwable ignored) {
                }
                w.println(name + "\t" + String.format("%06X", color));
                n++;
            }
        } catch (Throwable t) {
            msg(EnumChatFormatting.RED + "写流体颜色表失败：" + t);
            AEMobile.error("[AEMobile] 写流体颜色表失败: " + t, t);
            return;
        } finally {
            if (w != null) w.close();
        }
        msg(EnumChatFormatting.GREEN + "已导出 " + n + " 条流体颜色 → " + out.getAbsolutePath());
    }

    /**
     * 源质颜色 + 贴图位置：TC4 的 {@code Aspect.aspects}（LinkedHashMap<tag, Aspect>），全反射。
     *
     * <p>两个产物：{@code aspect-colors.tsv}（tag → RRGGBB）和 {@code aspect-images.tsv}
     * （tag → {@code domain:path}）。后者是给附属模组用的：它们注册的要素（custom1-5 / caelum /
     * tabernus / terminus / evolutio）贴图文件名跟 tag 对不上，只能靠 {@code Aspect.getImage()}
     * 给的真实路径去 jar 里找，否则只能显示问号。
     */
    private void dumpAspectColors(File dir) {
        File out = new File(dir, "aspect-colors.tsv");
        File outImages = new File(dir, "aspect-images.tsv");
        Map<String, Integer> colors = new LinkedHashMap<String, Integer>();
        Map<String, String> images = new LinkedHashMap<String, String>();
        try {
            Class<?> cls = Class.forName("thaumcraft.api.aspects.Aspect");
            Object raw = cls.getField("aspects").get(null);
            if (raw instanceof Map) {
                for (Object o : ((Map<?, ?>) raw).values()) {
                    if (o == null) continue;
                    String tag = null;
                    int color = 0xFFFFFF;
                    String image = null;
                    try {
                        tag = (String) cls.getMethod("getTag").invoke(o);
                    } catch (Throwable t) {
                        Field tf = cls.getDeclaredField("tag");
                        tf.setAccessible(true);
                        tag = (String) tf.get(o);
                    }
                    try {
                        Object c = cls.getMethod("getColor").invoke(o);
                        if (c instanceof Integer) color = ((Integer) c) & 0xFFFFFF;
                    } catch (Throwable ignored) {
                    }
                    try {
                        Object img = cls.getMethod("getImage").invoke(o);
                        if (img != null) image = img.toString();
                    } catch (Throwable ignored) {
                    }
                    if (tag != null && !tag.isEmpty()) {
                        String key = tag.toLowerCase(Locale.ENGLISH);
                        colors.put(key, color);
                        if (image != null && !image.isEmpty()) images.put(key, image);
                    }
                }
            }
        } catch (Throwable t) {
            msg(EnumChatFormatting.RED + "读不到源质表（没装神秘时代？）：" + t);
            return;
        }
        if (colors.isEmpty()) {
            msg(EnumChatFormatting.RED + "源质表是空的，没导出");
            return;
        }

        PrintWriter w = null;
        try {
            w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8));
            w.println("# /aemobile dump aspects —— 给 tools/build_aspects.py 烘颜色用");
            w.println("# <tag>\t<RRGGBB>");
            for (Map.Entry<String, Integer> e : colors.entrySet()) {
                w.println(e.getKey() + "\t" + String.format("%06X", e.getValue()));
            }
        } catch (Throwable t) {
            msg(EnumChatFormatting.RED + "写源质颜色表失败：" + t);
            AEMobile.error("[AEMobile] 写源质颜色表失败: " + t, t);
            return;
        } finally {
            if (w != null) w.close();
        }

        // 贴图路径：附属模组的要素文件名和 tag 对不上，只能靠这个去 jar 里精确找
        PrintWriter wi = null;
        try {
            wi = new PrintWriter(new OutputStreamWriter(new FileOutputStream(outImages),
                    StandardCharsets.UTF_8));
            wi.println("# /aemobile dump aspects —— 给 tools/build_aspects.py 定位贴图用");
            wi.println("# <tag>\t<domain:path>（Aspect.getImage()，路径相对 assets/<domain>/）");
            for (Map.Entry<String, String> e : images.entrySet()) {
                wi.println(e.getKey() + "\t" + e.getValue());
            }
        } catch (Throwable t) {
            msg(EnumChatFormatting.RED + "写源质贴图表失败：" + t);
        } finally {
            if (wi != null) wi.close();
        }

        msg(EnumChatFormatting.GREEN + "已导出 " + colors.size() + " 条源质颜色、"
                + images.size() + " 条贴图路径 → " + dir.getAbsolutePath());
    }

    /**
     * 1.7.10 的 {@code FluidRegistry} 没有公开的「全部流体」访问器（只有 getFluid(name)），
     * 只能反射读内部集合；字段/方法名在不同 Forge 分支上不一样，逐个试。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Fluid> allFluids() {
        for (String method : new String[]{"getRegisteredFluids", "getRegisteredFluidNames"}) {
            try {
                Method m = FluidRegistry.class.getMethod(method);
                Object v = m.invoke(null);
                Map<String, Fluid> out = new LinkedHashMap<String, Fluid>();
                if (v instanceof Collection) {
                    for (Object o : (Collection<Object>) v) {
                        if (o instanceof Fluid) out.put(((Fluid) o).getName(), (Fluid) o);
                        else if (o instanceof String) {
                            Fluid f = FluidRegistry.getFluid((String) o);
                            if (f != null) out.put(f.getName(), f);
                        }
                    }
                } else if (v instanceof Map) {
                    for (Object o : ((Map<Object, Object>) v).values()) {
                        if (o instanceof Fluid) out.put(((Fluid) o).getName(), (Fluid) o);
                    }
                }
                if (!out.isEmpty()) return out;
            } catch (Throwable ignored) {
            }
        }
        for (String field : new String[]{"fluids", "fluidNames", "fluidRegistry", "fluidIDs"}) {
            try {
                Field f = FluidRegistry.class.getDeclaredField(field);
                f.setAccessible(true);
                Object v = f.get(null);
                if (!(v instanceof Map)) continue;
                Map<String, Fluid> out = new LinkedHashMap<String, Fluid>();
                for (Map.Entry<Object, Object> e : ((Map<Object, Object>) v).entrySet()) {
                    Object val = e.getValue();
                    if (val instanceof Fluid) out.put(((Fluid) val).getName(), (Fluid) val);
                    else if (e.getKey() instanceof Fluid) out.put(((Fluid) e.getKey()).getName(), (Fluid) e.getKey());
                }
                if (!out.isEmpty()) return out;
            } catch (Throwable ignored) {
            }
        }
        return Collections.emptyMap();
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
