package dev.youtiao.aemobile.client;

import codechicken.nei.guihook.GuiContainerManager;
import dev.youtiao.aemobile.AEMobile;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.texture.TextureUtil;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ChatComponentText;
import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.IntBuffer;
import java.util.List;

/**
 * 图标导出界面：把物品列表渲染成一格一格的 PNG，写到 dir 里。
 *
 * <p>渲染流程照抄 NEI 的 {@code GuiItemIconDumper}（那是久经考验的做法）：
 * 开一个 Framebuffer，用正交投影把一屏物品画进去，再 {@code glGetTexImage} 读回来切成小图。
 * 之所以做成 GuiScreen 而不是后台线程，是因为 1.7.10 的 GL 必须在客户端渲染线程里用。
 *
 * <p>每帧处理一屏（约 100 个物品），几秒钟就能跑完几千个，界面会自己关掉并打印结果。
 */
public class GuiIconDumper extends net.minecraft.client.gui.GuiScreen {

    private final List<ItemStack> items;
    private final File dir;
    private final File manifest;
    private final int iconSize;
    private final int borderSize;
    private final int boxSize;

    private int drawIndex;
    private int parseIndex;
    private int written;

    private IntBuffer pixelBuffer;
    private int[] pixelValues;

    public GuiIconDumper(List<ItemStack> items, File dir, int iconSize) {
        this.items = items;
        this.dir = dir;
        this.iconSize = iconSize;
        this.borderSize = Math.max(1, iconSize / 16);
        this.boxSize = iconSize + borderSize * 2;
        this.manifest = new File(dir, "manifest.jsonl");
        if (!dir.exists()) {
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
        }
        try {
            // 每次导出前清掉旧的清单（图标文件本身可以留着，按名字覆盖）
            PrintWriter w = new PrintWriter(manifest, "UTF-8");
            w.close();
        } catch (IOException e) {
            AEMobile.error("[AEMobile] 无法写入图标清单: " + e, e);
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    protected void keyTyped(char c, int keycode) {
        // Esc 中止
        if (keycode == 1) {
            finish("已中止，已导出 " + written + " 个图标");
        }
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        try {
            drawItems();
            exportItems();
        } catch (Throwable t) {
            AEMobile.error("[AEMobile] 导出图标出错: " + t, t);
            finish("导出出错：" + t);
        }
    }

    private void drawItems() {
        int dw = Math.max(1, org.lwjgl.opengl.Display.getWidth());
        int dh = Math.max(1, org.lwjgl.opengl.Display.getHeight());

        GL11.glMatrixMode(GL11.GL_PROJECTION);
        GL11.glLoadIdentity();
        GL11.glOrtho(0.0D, dw * 16D / iconSize, dh * 16D / iconSize, 0.0D, 1000.0D, 3000.0D);
        GL11.glMatrixMode(GL11.GL_MODELVIEW);
        GL11.glClearColor(0, 0, 0, 0);
        GL11.glClear(GL11.GL_COLOR_BUFFER_BIT | GL11.GL_DEPTH_BUFFER_BIT);

        int rows = dh / boxSize;
        int cols = dw / boxSize;
        int fit = Math.max(1, rows * cols);

        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        GL11.glColor4f(1, 1, 1, 1);

        for (int i = 0; drawIndex < items.size() && i < fit; drawIndex++, i++) {
            int x = i % cols * 18;
            int y = i / cols * 18;
            try {
                GuiContainerManager.drawItem(x + 1, y + 1, items.get(drawIndex));
            } catch (Throwable ignored) {
                // 单个物品渲染失败（模型缺失等）不能中断整批
            }
        }

        GL11.glFlush();
    }

    private void exportItems() throws IOException {
        BufferedImage img = screenshot();
        int rows = img.getHeight() / boxSize;
        int cols = img.getWidth() / boxSize;
        int fit = Math.max(1, rows * cols);

        PrintWriter w = new PrintWriter(new java.io.OutputStreamWriter(
                new java.io.FileOutputStream(manifest, true), StandardCharsets.UTF_8));
        try {
            for (int i = 0; parseIndex < items.size() && i < fit; parseIndex++, i++) {
                int x = i % cols * boxSize;
                int y = i / cols * boxSize;
                ItemStack stack = items.get(parseIndex);
                if (stack == null) continue;
                String name = net.minecraft.item.Item.itemRegistry.getNameForObject(stack.getItem());
                if (name == null) continue;
                int meta = stack.getItemDamage();
                String modid = name.contains(":") ? name.substring(0, name.indexOf(':')) : "minecraft";
                String simple = name.contains(":") ? name.substring(name.indexOf(':') + 1) : name;
                File modDir = new File(dir, sanitize(modid));
                if (!modDir.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    modDir.mkdirs();
                }
                File png = new File(modDir, sanitize(simple) + "_" + meta + ".png");
                BufferedImage sub = img.getSubimage(x + borderSize, y + borderSize, iconSize, iconSize);
                javax.imageio.ImageIO.write(sub, "png", png);
                String dname = "";
                try {
                    dname = stack.getDisplayName();
                } catch (Throwable ignored) {
                }
                w.println("{\"name\":\"" + name + "\",\"meta\":" + meta + ",\"dname\":\""
                        + dname.replace("\\", "\\\\").replace("\"", "\\\"") + "\",\"file\":\""
                        + modid + "/" + png.getName() + "\",\"size\":" + iconSize + "}");
                written++;
            }
        } finally {
            w.close();
        }

        if (parseIndex >= items.size()) {
            finish("已导出 " + written + " 个图标 → " + dir.getAbsolutePath());
        }
    }

    private void finish(String message) {
        Minecraft.getMinecraft().displayGuiScreen(null);
        if (Minecraft.getMinecraft().thePlayer != null) {
            Minecraft.getMinecraft().thePlayer.addChatMessage(new ChatComponentText("[AEMobile] " + message));
        }
        AEMobile.info("[AEMobile] " + message);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private BufferedImage screenshot() {
        Framebuffer fb = Minecraft.getMinecraft().getFramebuffer();
        int w = Math.max(1, org.lwjgl.opengl.Display.getWidth());
        int h = Math.max(1, org.lwjgl.opengl.Display.getHeight());
        int texW = w;
        int texH = h;
        if (OpenGlHelper.isFramebufferEnabled()) {
            texW = fb.framebufferTextureWidth;
            texH = fb.framebufferTextureHeight;
        }

        int k = texW * texH;
        if (pixelBuffer == null || pixelBuffer.capacity() < k) {
            pixelBuffer = BufferUtils.createIntBuffer(k);
            pixelValues = new int[k];
        }

        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glPixelStorei(GL11.GL_UNPACK_ALIGNMENT, 1);
        pixelBuffer.clear();

        if (OpenGlHelper.isFramebufferEnabled()) {
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, fb.framebufferTexture);
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
        } else {
            GL11.glReadPixels(0, 0, texW, texH, GL12.GL_BGRA, GL12.GL_UNSIGNED_INT_8_8_8_8_REV, pixelBuffer);
        }

        pixelBuffer.get(pixelValues);
        TextureUtil.func_147953_a(pixelValues, texW, texH);

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        if (OpenGlHelper.isFramebufferEnabled()) {
            int yOff = texH - h;
            for (int y = 0; y < h; ++y) {
                for (int x = 0; x < w; ++x) {
                    img.setRGB(x, y, pixelValues[(y + yOff) * texW + x]);
                }
            }
        } else {
            img.setRGB(0, 0, texW, texH, pixelValues, 0, texW);
        }
        return img;
    }
}
