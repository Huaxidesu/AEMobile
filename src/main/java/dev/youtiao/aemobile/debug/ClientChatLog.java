package dev.youtiao.aemobile.debug;

import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraft.util.IChatComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * 客户端收到的聊天记录（只留最近 {@value #MAX} 条，不落盘）。
 *
 * <p>用途是让自动化测试能验证「客户端到底收到了什么」：GTNHChat 渲染后的颜色、
 * 悬停、点击都在组件里，纯文本看不出来，所以每条都留一份 JSON。
 *
 * <p>只在客户端加载（{@code ClientProxy} 引用），服务端不会碰这个类。
 */
public final class ClientChatLog {

    private static final int MAX = 120;

    /** 一条聊天记录 */
    public static final class Entry {
        public final String text;
        public final String json;
        public final long at;

        Entry(String text, String json, long at) {
            this.text = text;
            this.json = json;
            this.at = at;
        }
    }

    private static final ArrayDeque<Entry> RING = new ArrayDeque<Entry>();

    @SubscribeEvent
    public void onChat(ClientChatReceivedEvent event) {
        IChatComponent c = event.message;
        if (c == null) return;
        Entry e = new Entry(textOf(c), jsonOf(c), System.currentTimeMillis());
        synchronized (RING) {
            RING.addLast(e);
            while (RING.size() > MAX) RING.pollFirst();
        }
    }

    /** 最近 n 条，时间升序；n <= 0 时返回全部 */
    public static List<Entry> recent(int n) {
        List<Entry> all = new ArrayList<Entry>();
        synchronized (RING) {
            all.addAll(RING);
        }
        if (n <= 0 || n >= all.size()) return all;
        return all.subList(all.size() - n, all.size());
    }

    /**
     * 带 {@code §} 颜色码的文本。
     *
     * <p>不用 {@code getFormattedText()}：那个方法在 reobf 后运行时找不到
     * （{@code NoSuchMethodError: func_150254_d}），一调就把整条链路打断。
     * 这里用 {@code getUnformattedText()}，颜色交给 JSON 那条命令看。
     */
    private static String textOf(IChatComponent c) {
        try {
            String s = c.getUnformattedText();
            return s == null ? "" : s;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 组件 JSON，用来核对 hoverEvent / clickEvent */
    private static String jsonOf(IChatComponent c) {
        try {
            return IChatComponent.Serializer.func_150696_a(c);
        } catch (Throwable t) {
            return "";
        }
    }
}
