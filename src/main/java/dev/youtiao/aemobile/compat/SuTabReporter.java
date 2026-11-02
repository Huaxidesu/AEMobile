package dev.youtiao.aemobile.compat;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.PlayerEvent;
import dev.youtiao.aemobile.AEMobile;
import dev.youtiao.aemobile.Config;
import dev.youtiao.aemobile.net.ProxyBridge;

/**
 * 把本服 ServerUtilities 的 TAB 页眉页脚上报给代理。
 *
 * <p>背景：SU 的文案存在服务端 {@code serverutilities/serverutilities.cfg} 的
 * {@code tab.headerText/footerText}，但代理是独立进程，读不到这份配置；SU 也没有给代理
 * 留「读配置」的接口，只留了 {@code SU|TabHF}「写覆盖值」的信道。所以在服务端这边读出来、
 * 通过 AEMobileBridge 的 {@code tab_source} 上报，代理再按子服缓存下发 —— 文案的唯一来源
 * 就回到了 SU 配置本身，插件里不必再复制一份。
 *
 * <p>反射读取是为了不引入对 ServerUtilities 的硬依赖：没装 SU 时整个类直接跳过。
 * 字段路径已对 2.4.22 反编译确认：{@code ServerUtilitiesConfig.tab.headerText/footerText}
 * （public 实例字段，{@code tab} 是 public static）。
 */
public final class SuTabReporter {

    private static final String CONFIG_CLASS = "serverutils.ServerUtilitiesConfig";

    private static volatile String lastHeader;
    private static volatile String lastFooter;
    private static volatile boolean missingLogged;
    private static volatile boolean failureLogged;

    private SuTabReporter() {
    }

    /** 挂在 FML 总线上的玩家事件。 */
    public static final class Listener {
        @SubscribeEvent
        public void onJoin(PlayerEvent.PlayerLoggedInEvent event) {
            reportAsync();
        }
    }

    /** 后台线程上报，不要在主线程里等待 socket。 */
    public static void reportAsync() {
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                reportOnce();
            }
        }, "AEMobile-tab-source");
        t.setDaemon(true);
        t.start();
    }

    private static void reportOnce() {
        String[] tab = readSuTab();
        if (tab == null) return;
        String h = tab[0] == null ? "" : tab[0];
        String f = tab[1] == null ? "" : tab[1];
        // 内容没变就不重复发：玩家每次登录都会触发，但 Su 配置通常一整天都不改
        if (h.equals(lastHeader) && f.equals(lastFooter)) return;

        int port = serverPort();
        boolean ok = ProxyBridge.pushTabSource(Config.bridgeServerName, port, h, f);
        if (ok) {
            lastHeader = h;
            lastFooter = f;
            AEMobile.info("[AEMobile] 已把 ServerUtilities 的 TAB 文案上报给代理（server="
                    + (Config.bridgeServerName.isEmpty() ? "自动/端口 " + port : Config.bridgeServerName)
                    + ", header " + h.length() + " 字符, footer " + f.length() + " 字符）");
        } else if (!failureLogged) {
            failureLogged = true;
            AEMobile.warn("[AEMobile] TAB 文案上报失败（代理没开 tab_source 或版本低于 1.1.1），"
                    + "代理会退回 config.yml 里的文案");
        }
    }

    /**
     * 本服监听端口，给代理反查 BC 子服名用。
     *
     * <p>有了它，每台后端都不用在 {@code AEMobile.cfg} 里手填子服名：代理拿端口去
     * {@code servers:} 里对号即可。取不到就返回 0，代理会退回通配 key（对所有子服生效）。
     */
    private static int serverPort() {
        try {
            net.minecraft.server.MinecraftServer srv = net.minecraft.server.MinecraftServer.getServer();
            return srv == null ? 0 : srv.getServerPort();
        } catch (Throwable t) {
            return 0;
        }
    }

    /** @return {header, footer}；没装 SU 返回 null */
    private static String[] readSuTab() {
        try {
            Class<?> cfg = Class.forName(CONFIG_CLASS);
            Object tab = cfg.getField("tab").get(null);
            if (tab == null) return null;
            String h = (String) tab.getClass().getField("headerText").get(tab);
            String f = (String) tab.getClass().getField("footerText").get(tab);
            return new String[]{h, f};
        } catch (ClassNotFoundException e) {
            if (!missingLogged) {
                missingLogged = true;
                AEMobile.info("[AEMobile] 没装 ServerUtilities，跳过 TAB 文案上报");
            }
            return null;
        } catch (Throwable t) {
            if (!failureLogged) {
                failureLogged = true;
                AEMobile.warn("[AEMobile] 读取 ServerUtilities 的 TAB 文案失败：" + t);
            }
            return null;
        }
    }
}
