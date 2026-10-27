package dev.youtiao.aemobile.net;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JSON 工具。
 *
 * <p>之前靠 Spring 的 Jackson 自动序列化 DTO；现在没有 Spring 了，但 DTO 的 getter
 * 形态（{@code item_name} / {@code count} / {@code craftable} …）是 App 的既有契约，
 * 所以仍然用 jackson-databind 按 getter 序列化，不手写 JSON。
 */
public final class Json {

    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }

    public static String write(Object o) {
        try {
            return MAPPER.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException("JSON serialize failed", e);
        }
    }

    public static JsonNode read(byte[] payload) {
        try {
            return MAPPER.readTree(payload);
        } catch (Exception e) {
            return null;
        }
    }

    public static JsonNode read(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- JsonNode 取值小工具（参数缺失时给默认值，避免 NPE 打到连接上） ----

    public static int intOf(JsonNode p, String key, int def) {
        JsonNode n = p == null ? null : p.get(key);
        return n == null || !n.canConvertToInt() ? def : n.asInt(def);
    }

    public static long longOf(JsonNode p, String key, long def) {
        JsonNode n = p == null ? null : p.get(key);
        return n == null || !n.canConvertToLong() ? def : n.asLong(def);
    }

    public static boolean boolOf(JsonNode p, String key, boolean def) {
        JsonNode n = p == null ? null : p.get(key);
        return n == null ? def : n.asBoolean(def);
    }

    public static String strOf(JsonNode p, String key, String def) {
        JsonNode n = p == null ? null : p.get(key);
        return n == null || n.isNull() ? def : n.asText(def);
    }
}
