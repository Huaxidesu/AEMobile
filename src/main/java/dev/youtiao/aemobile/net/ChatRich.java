package dev.youtiao.aemobile.net;

import net.minecraft.event.ClickEvent;
import net.minecraft.event.HoverEvent;
import net.minecraft.util.ChatStyle;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把聊天组件树摊平成「带 {@code §} 颜色码的文本 + 结构化附件」。
 *
 * <p>手机端拿到的只能是字符串，而 GTNHChat 和 NEI 把两类重要信息藏在组件里：
 * <ul>
 *   <li><b>物品链接</b>：{@code [物品名]} 这段文本带 {@code /nei_bookmark <id>} 的点击动作，
 *       真正的名字和悬停提示都在组件上，只取文本会退化成一对方括号；</li>
 *   <li><b>任务分享</b>：BetterQuesting 把任务编码成
 *       {@code betterquesting.msg.sharequest:<ID>} 混在正文里，原样送到手机上是一串乱码。</li>
 * </ul>
 *
 * <p>这两类都从正文里摘出来，改走 {@code att} 字段的结构化附件，App 才能画成卡片。
 *
 * <p>只读组件，不改原对象；不解析 JSON，也不执行任何点击动作 —— 点击值只用来识别类型。
 */
final class ChatRich {

    /** BetterQuesting 的任务分享标记，与 GTNHChat 的 {@code ChatFormat.QUEST_MARKER} 一致 */
    private static final String QUEST_MARKER = "betterquesting.msg.sharequest:";
    private static final Pattern QUEST_ID =
            Pattern.compile("betterquesting\\.msg\\.sharequest:([A-Za-z0-9_\\-:.]{4,64})");

    /** NEI 物品链接的点击命令前缀 */
    private static final String NEI_CLICK = "/nei_bookmark";

    /** 一条消息最多带几个附件，防止有人刷一屏物品把帧撑大 */
    private static final int MAX_ATT = 8;

    private ChatRich() {}

    /** 一条附件。{@code type} 目前是 {@code item} / {@code quest}。 */
    static final class Att {
        final String type;
        final String name;
        final String hover;
        final String id;

        Att(String type, String name, String hover, String id) {
            this.type = type;
            this.name = name;
            this.hover = hover;
            this.id = id;
        }
    }

    static final class Result {
        /** 带 {@code §} 码的正文；物品链接的文本已被摘掉 */
        final String text;
        /** JSON 数组字符串；没有附件时是空串 */
        final String att;

        Result(String text, String att) {
            this.text = text;
            this.att = att;
        }
    }

    static Result parse(IChatComponent root) {
        if (root == null) return new Result("", "");
        Ctx ctx = new Ctx();
        walk(root, ctx);
        return new Result(ctx.text.toString(), json(ctx.atts));
    }

    // ------------------------------------------------------------------ 遍历

    /**
     * 节点的有效样式。
     *
     * <p>1.7.10 的 {@code ChatStyle} 自带 parentStyle 链（{@code appendSibling} 时挂上），
     * getter 会沿链解析，所以这里直接读就是继承后的结果，不需要再合并父样式。
     */
    private static final class Style {
        EnumChatFormatting color;
        boolean bold;
        boolean italic;
        boolean underline;
        boolean strike;
        ClickEvent click;
        HoverEvent hover;
    }

    private static Style of(IChatComponent node) {
        Style s = new Style();
        ChatStyle cs = node.getChatStyle();
        if (cs == null) return s;
        s.color = cs.getColor();
        s.bold = cs.getBold();
        s.italic = cs.getItalic();
        s.underline = cs.getUnderlined();
        s.strike = cs.getStrikethrough();
        s.click = cs.getChatClickEvent();
        s.hover = cs.getChatHoverEvent();
        return s;
    }

    private static final class Ctx {
        final StringBuilder text = new StringBuilder();
        final List<Att> atts = new ArrayList<Att>();
        /** 已经写进 text 的样式，用来决定要不要补码 */
        EnumChatFormatting color;
        boolean bold;
        boolean italic;
        boolean underline;
        boolean strike;
    }

    private static void walk(IChatComponent node, Ctx ctx) {
        if (node == null) return;
        Style style = of(node);

        // 只取本节点自身的文本：getUnformattedText() 会把 siblings 一起拼进来，
        // 递归时就会每段重复一遍。getUnformattedTextForChat() 不含 siblings。
        String own = node.getUnformattedTextForChat();
        if (own != null && !own.isEmpty()) {
            String left = own;
            if (isNeiItem(style)) {
                // 只发英文名和 tooltip：服务端是 en_US，翻译交给 App 本地做
                addAtt(ctx, new Att("item", stripBrackets(own), hoverText(style.hover), null));
                left = null;
            } else if (left.contains(QUEST_MARKER)) {
                Matcher m = QUEST_ID.matcher(left);
                if (m.find()) {
                    // 只发任务 ID，中文名由 App 用汉化包的任务表查
                    addAtt(ctx, new Att("quest", "", "", m.group(1)));
                    left = left.replace(m.group(0), "[任务]");
                }
            }
            if (left != null && !left.isEmpty()) {
                apply(ctx, style);
                ctx.text.append(left);
            }
        }

        List<IChatComponent> siblings = node.getSiblings();
        if (siblings != null) {
            for (IChatComponent s : siblings) walk(s, ctx);
        }
    }

    private static boolean isNeiItem(Style style) {
        if (style.click == null) return false;
        if (style.click.getAction() != ClickEvent.Action.RUN_COMMAND) return false;
        String v = style.click.getValue();
        return v != null && v.startsWith(NEI_CLICK);
    }

    private static String stripBrackets(String s) {
        String t = s.trim();
        if (t.length() >= 2 && t.charAt(0) == '[' && t.charAt(t.length() - 1) == ']') {
            return t.substring(1, t.length() - 1);
        }
        return t;
    }

    private static String hoverText(HoverEvent hover) {
        if (hover == null || hover.getValue() == null) return "";
        String s = hover.getValue().getUnformattedText();
        return s == null ? "" : s.replace('\u00a7', ' ').trim();
    }

    private static void addAtt(Ctx ctx, Att att) {
        if (ctx.atts.size() >= MAX_ATT) return;
        if ("item".equals(att.type) && (att.name == null || att.name.isEmpty())) return;
        if ("quest".equals(att.type) && (att.id == null || att.id.isEmpty())) return;
        ctx.atts.add(att);
    }

    // ------------------------------------------------------------------ 样式码

    /**
     * 把样式差异写成 {@code §} 码。
     *
     * <p>样式只能加不能减：要关掉粗体只能 {@code §r} 重置后重新铺一遍当前样式，
     * 这也是游戏里 {@code getFormattedText} 的做法。
     */
    private static void apply(Ctx ctx, Style style) {
        boolean reset = (ctx.bold && !style.bold)
                || (ctx.italic && !style.italic)
                || (ctx.underline && !style.underline)
                || (ctx.strike && !style.strike);
        if (reset) {
            ctx.text.append('\u00a7').append('r');
            ctx.color = null;
            ctx.bold = ctx.italic = ctx.underline = ctx.strike = false;
        }
        if (style.color != null && style.color != ctx.color) {
            char code = colorCode(style.color);
            if (code != 0) {
                ctx.text.append('\u00a7').append(code);
                ctx.color = style.color;
            }
        }
        if (style.bold && !ctx.bold) {
            ctx.text.append('\u00a7').append('l');
            ctx.bold = true;
        }
        if (style.italic && !ctx.italic) {
            ctx.text.append('\u00a7').append('o');
            ctx.italic = true;
        }
        if (style.underline && !ctx.underline) {
            ctx.text.append('\u00a7').append('n');
            ctx.underline = true;
        }
        if (style.strike && !ctx.strike) {
            ctx.text.append('\u00a7').append('m');
            ctx.strike = true;
        }
    }

    private static char colorCode(EnumChatFormatting color) {
        if (color == EnumChatFormatting.BLACK) return '0';
        if (color == EnumChatFormatting.DARK_BLUE) return '1';
        if (color == EnumChatFormatting.DARK_GREEN) return '2';
        if (color == EnumChatFormatting.DARK_AQUA) return '3';
        if (color == EnumChatFormatting.DARK_RED) return '4';
        if (color == EnumChatFormatting.DARK_PURPLE) return '5';
        if (color == EnumChatFormatting.GOLD) return '6';
        if (color == EnumChatFormatting.GRAY) return '7';
        if (color == EnumChatFormatting.DARK_GRAY) return '8';
        if (color == EnumChatFormatting.BLUE) return '9';
        if (color == EnumChatFormatting.GREEN) return 'a';
        if (color == EnumChatFormatting.AQUA) return 'b';
        if (color == EnumChatFormatting.RED) return 'c';
        if (color == EnumChatFormatting.LIGHT_PURPLE) return 'd';
        if (color == EnumChatFormatting.YELLOW) return 'e';
        if (color == EnumChatFormatting.WHITE) return 'f';
        return 0;
    }

    // ------------------------------------------------------------------ 序列化

    /** 附件用 App 侧好读的短键：t=类型 n=名字 h=悬停 i=任务 ID */
    private static String json(List<Att> atts) {
        if (atts.isEmpty()) return "";
        List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>(atts.size());
        for (Att a : atts) {
            Map<String, Object> m = new LinkedHashMap<String, Object>();
            m.put("t", a.type);
            if (a.name != null && !a.name.isEmpty()) m.put("n", a.name);
            if (a.hover != null && !a.hover.isEmpty()) m.put("h", a.hover);
            if (a.id != null && !a.id.isEmpty()) m.put("i", a.id);
            rows.add(m);
        }
        try {
            return Json.write(rows);
        } catch (Throwable t) {
            return "";
        }
    }
}
