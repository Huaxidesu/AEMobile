package dev.youtiao.aemobile.client;

import cpw.mods.fml.common.eventhandler.EventPriority;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.settings.KeyBinding;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * 现代 TAB 兜底钩子。
 *
 * <p>背景：GTNH 客户端里 {@code ServerUtilities} 的 {@code MixinGuiIngameForge_ModernTab}
 * 虽然在 {@code renderPlayerList} HEAD 处 {@code ci.cancel()} 掉了原版样式，但实测
 * {@code serverutils.client.tab.ModernTabRenderer} 迟迟没有被加载（说明它的
 * {@code ModernTabRenderer.INSTANCE.render(res)} 分支没有走到）。结果是原版被取消、
 * 现代样式又没画，页眉页脚永远出不来。
 *
 * <p>这里不依赖它的 mixin 分支：直接订阅 {@code RenderGameOverlayEvent.Pre(PLAYER_LIST)}，
 * 抢回渲染权并反射调用 SU 自带的 {@code ModernTabRenderer.render(ScaledResolution)}。
 * 好处是**样式、页眉页脚、头像、ping 全部沿用 SU 自己的实现**，我们只负责"叫醒它"；
 * 服务器下发 {@code SU|TabHF} 的行为完全不用改。
 *
 * <p>如果 PLAYER_LIST 事件压根没被 post（mixin 分支真的没跑），退而求其次在 CHAT 元素
 * 之后画一次，保证 TAB 一定有内容。
 */
public final class TabOverlayHook {

    private static final Logger LOG = LogManager.getLogger("AEMobileTab");

    private static final String RENDERER = "serverutils.client.tab.ModernTabRenderer";

    private static boolean registered;
    private static boolean busy;
    private static boolean resolved;
    private static volatile Object renderInstance;
    private static volatile java.lang.reflect.Method renderMethod;
    private static long lastWarnAt;

    private static int preHits;
    private static int fallbackDraws;
    private static long lastPlayerListPre;

    private TabOverlayHook() {}

    public static void init() {
        if (registered) {
            return;
        }
        registered = true;
        MinecraftForge.EVENT_BUS.register(new TabOverlayHook());
        LOG.info("[TabHook] registered (renderer={})", RENDERER);
    }

    /** TAB 是否被按住。lwjgl3ify 下 KeyBinding 的 pressed 有时不刷新，所以再从按键码查一次。 */
    private static boolean holdingTab() {
        try {
            KeyBinding kb = Minecraft.getMinecraft().gameSettings.keyBindPlayerList;
            if (kb == null) return false;
            if (kb.getIsKeyPressed()) return true;
            // 不再硬编码 15：玩家改过键位时照样能识别
            int code = kb.getKeyCode();
            return code >= 0 && org.lwjgl.input.Keyboard.isKeyDown(code);
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** 反射只解析一次：按住 TAB 时每帧都会走这里，别每帧做 Class.forName/getMethod。 */
    private static synchronized void resolveRenderer() {
        if (resolved) return;
        resolved = true;
        try {
            Class<?> cls = Class.forName(RENDERER);
            renderInstance = cls.getField("INSTANCE").get(null);
            renderMethod = cls.getMethod("render", ScaledResolution.class);
        } catch (ClassNotFoundException e) {
            LOG.warn("[TabHook] {} 不存在，跳过现代 TAB", RENDERER);
        } catch (Throwable t) {
            LOG.warn("[TabHook] 解析渲染器失败: {}", t.toString());
        }
    }

    private static void draw(ScaledResolution res) {
        if (busy || res == null) {
            return;
        }
        resolveRenderer();
        if (renderMethod == null) {
            return;
        }
        busy = true;
        try {
            renderMethod.invoke(renderInstance, res);
        } catch (Throwable t) {
            // 渲染每帧都跑，失败时限流打日志，别把 log 刷爆
            long now = System.currentTimeMillis();
            if (now - lastWarnAt > 5000L) {
                lastWarnAt = now;
                LOG.warn("[TabHook] render 失败: {}", t.toString());
            }
        } finally {
            busy = false;
        }
    }

    /**
     * 主路径：SU 的 mixin 真的 post 了这个事件。
     *
     * <p>{@code receiveCanceled = true} 很关键：FML 的 EventBus 默认**不会**把已被取消的事件
     * 派发给后面的监听器。而这类"接管玩家列表"的 mod（例如 BetterPingDisplay）正是先
     * {@code setCanceled(true)} 再自己画一份原版样式 —— 结果 SU 的 mixin 看到事件被取消就直接
     * return，页眉页脚永远出不来，我们挂在 LOWEST 的监听器也收不到这个事件。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onPlayerListPre(RenderGameOverlayEvent.Pre event) {
        if (event.type != RenderGameOverlayEvent.ElementType.PLAYER_LIST) {
            return;
        }
        lastPlayerListPre = System.currentTimeMillis();
        preHits++;
        if (preHits % 200 == 1) {
            LOG.info("[TabHook] PLAYER_LIST pre #{}, canceled={}, holding={}", preHits, event.isCanceled(),
                    holdingTab());
        }
        if (!event.isCanceled() || !holdingTab()) {
            // 事件没被抢：SU 的 mixin 自己会调用渲染器，我们不要重复画
            return;
        }
        // 事件被别人抢了：夺回渲染权，画 SU 的现代样式，再重新取消掉别人后续的绘制
        event.setCanceled(false);
        draw(event.resolution);
        event.setCanceled(true);
    }

    /** 兜底：PLAYER_LIST 事件没来（200ms 内没出现过），就在 CHAT 之后画。 */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onChatPre(RenderGameOverlayEvent.Pre event) {
        if (event.type != RenderGameOverlayEvent.ElementType.CHAT) {
            return;
        }
        if (!holdingTab()) {
            return;
        }
        if (System.currentTimeMillis() - lastPlayerListPre < 200L) {
            return;
        }
        fallbackDraws++;
        if (fallbackDraws % 200 == 1) {
            LOG.info("[TabHook] fallback draw #{} (PLAYER_LIST pre 未触发)", fallbackDraws);
        }
        draw(event.resolution);
    }
}
