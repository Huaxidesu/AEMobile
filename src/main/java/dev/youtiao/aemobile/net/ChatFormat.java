package dev.youtiao.aemobile.net;

import net.minecraft.util.ChatComponentText;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minecraft 颜色/格式码的安全解析。
 *
 * <p>支持 {@code §}/{@code &} + {@code 0-9a-f}（颜色）和 {@code l/o/n/m/r}（样式）。
 * {@code k}（乱码/obfuscated）直接丢掉码但保留文字；其余控制字符、换行、双向覆盖符照旧清理。
 *
 * <p>只生成 {@link ChatComponentText} 字面量，不碰 jsonToComponent / clickEvent / hoverEvent。
 */
final class ChatFormat {

    private static final Pattern MENTION = Pattern.compile("@([A-Za-z0-9_\\u4e00-\\u9fa5]{1,16})");

    private ChatFormat() {
    }

    static final class Run {
        final String text;
        final EnumChatFormatting color;
        final boolean bold;
        final boolean italic;
        final boolean underline;
        final boolean strike;

        Run(String text, EnumChatFormatting color, boolean bold, boolean italic, boolean underline, boolean strike) {
            this.text = text;
            this.color = color;
            this.bold = bold;
            this.italic = italic;
            this.underline = underline;
            this.strike = strike;
        }
    }

    /** 名字：不允许颜色码，去掉 [ ] */
    static String sanitizeName(String raw, int max) {
        return sanitize(raw, max, true, false);
    }

    /** 消息：保留合法颜色/样式码（§ 和 & 都转成 §），去掉 §k */
    static String sanitizeColored(String raw, int max, boolean isName) {
        return sanitize(raw, max, isName, true);
    }

    private static String sanitize(String raw, int max, boolean isName, boolean allowCodes) {
        if (raw == null) return "";
        StringBuilder b = new StringBuilder(Math.min(raw.length(), max + 16));
        int count = 0;
        for (int i = 0; i < raw.length(); ) {
            int cp = raw.codePointAt(i);
            i += Character.charCount(cp);
            if (allowCodes && (cp == 0xA7 || cp == '&')) {
                if (i >= raw.length()) continue;
                int codeCp = raw.codePointAt(i);
                char code = Character.toLowerCase((char) codeCp);
                if (isColorOrStyle(code)) {
                    i += Character.charCount(codeCp);
                    if (code == 'k') continue; // 乱码码：去掉码，文字留着
                    b.append('\u00a7').append(code);
                    continue;
                }
                if (cp == 0xA7) continue; // 孤立的 § 丢弃
            } else if (!allowCodes && (cp == 0xA7 || cp == '&') && i < raw.length()) {
                // 纯文本：颜色码连同码字一起丢掉
                int codeCp = raw.codePointAt(i);
                char code = Character.toLowerCase((char) codeCp);
                if (isColorOrStyle(code)) {
                    i += Character.charCount(codeCp);
                    continue;
                }
                if (cp == 0xA7) continue;
            }
            if (cp == '\n' || cp == '\r' || cp == '\t') {
                b.append(' ');
                if (++count >= max) break;
                continue;
            }
            if (cp < 0x20 || (cp >= 0x7F && cp <= 0x9F)) continue;
            if (cp == 0xFEFF || (cp >= 0x200B && cp <= 0x200F) || (cp >= 0x202A && cp <= 0x202E)
                    || (cp >= 0x2066 && cp <= 0x2069)) continue;
            if (isName && (cp == '[' || cp == ']')) continue;
            b.appendCodePoint(cp);
            if (++count >= max) break;
        }
        return b.toString().trim();
    }

    /** 去掉所有颜色码，给过滤/去重/日志用 */
    static String plain(String raw) {
        return sanitize(raw, Integer.MAX_VALUE, false, false);
    }

    private static boolean isColorOrStyle(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                || c == 'l' || c == 'o' || c == 'n' || c == 'm' || c == 'r' || c == 'k';
    }

    private static EnumChatFormatting colorOf(char c) {
        switch (c) {
            case '0': return EnumChatFormatting.BLACK;
            case '1': return EnumChatFormatting.DARK_BLUE;
            case '2': return EnumChatFormatting.DARK_GREEN;
            case '3': return EnumChatFormatting.DARK_AQUA;
            case '4': return EnumChatFormatting.DARK_RED;
            case '5': return EnumChatFormatting.DARK_PURPLE;
            case '6': return EnumChatFormatting.GOLD;
            case '7': return EnumChatFormatting.GRAY;
            case '8': return EnumChatFormatting.DARK_GRAY;
            case '9': return EnumChatFormatting.BLUE;
            case 'a': return EnumChatFormatting.GREEN;
            case 'b': return EnumChatFormatting.AQUA;
            case 'c': return EnumChatFormatting.RED;
            case 'd': return EnumChatFormatting.LIGHT_PURPLE;
            case 'e': return EnumChatFormatting.YELLOW;
            default: return EnumChatFormatting.WHITE;
        }
    }

    /** 解析成带样式的片段（颜色码已经过 sanitizeColored） */
    static List<Run> parse(String text) {
        List<Run> out = new ArrayList<Run>();
        if (text == null || text.isEmpty()) return out;
        EnumChatFormatting color = EnumChatFormatting.WHITE;
        boolean bold = false, italic = false, underline = false, strike = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < text.length(); ) {
            char cp = text.charAt(i);
            if (cp == '\u00a7' && i + 1 < text.length()) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                if (isColorOrStyle(code)) {
                    if (cur.length() > 0) {
                        out.add(new Run(cur.toString(), color, bold, italic, underline, strike));
                        cur.setLength(0);
                    }
                    i += 2;
                    if (code == 'k') continue;
                    if (code == 'r') {
                        color = EnumChatFormatting.WHITE;
                        bold = italic = underline = strike = false;
                    } else if (code >= '0' && code <= '9' || code >= 'a' && code <= 'f') {
                        color = colorOf(code);
                    } else if (code == 'l') bold = true;
                    else if (code == 'o') italic = true;
                    else if (code == 'n') underline = true;
                    else if (code == 'm') strike = true;
                    continue;
                }
            }
            cur.append(cp);
            i++;
        }
        if (cur.length() > 0) out.add(new Run(cur.toString(), color, bold, italic, underline, strike));
        return out;
    }

    /**
     * 把带颜色码的文本拼成组件追加到 parent。
     * {@code @玩家} 用 mentionColor（默认金色）覆盖颜色，样式（粗体/斜体）保留。
     */
    static void append(IChatComponent parent, String text, EnumChatFormatting mentionColor) {
        for (Run run : parse(text)) {
            Matcher m = MENTION.matcher(run.text);
            int last = 0;
            while (m.find()) {
                if (m.start() > last) {
                    appendRun(parent, run.text.substring(last, m.start()), run, run.color);
                }
                appendRun(parent, m.group(), run, mentionColor);
                last = m.end();
            }
            if (last < run.text.length()) {
                appendRun(parent, run.text.substring(last), run, run.color);
            }
        }
    }

    private static void appendRun(IChatComponent parent, String text, Run style, EnumChatFormatting color) {
        if (text == null || text.isEmpty()) return;
        ChatComponentText c = new ChatComponentText(text);
        c.getChatStyle().setColor(color == null ? EnumChatFormatting.WHITE : color);
        if (style.bold) c.getChatStyle().setBold(Boolean.TRUE);
        if (style.italic) c.getChatStyle().setItalic(Boolean.TRUE);
        if (style.underline) c.getChatStyle().setUnderlined(Boolean.TRUE);
        if (style.strike) c.getChatStyle().setStrikethrough(Boolean.TRUE);
        parent.appendSibling(c);
    }
}
