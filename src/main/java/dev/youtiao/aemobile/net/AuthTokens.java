package dev.youtiao.aemobile.net;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 访问令牌与请求签名。
 *
 * <p>设计目标：<b>零配置</b>。玩家放下监控器时自动生成一个 token 打在聊天栏，
 * 手机 App 粘贴一次即可。token 本身<b>不出现在任何请求里</b>（只传 kid + HMAC 签名），
 * 所以抓包拿不到凭据，也无法伪造或重放请求。
 *
 * <p>协议细节见 doc/传输协议与安全.md §2。
 */
public final class AuthTokens {

    /** RFC 4648 Base32 字母表（无填充），26 字符的 token 全部由它构成 */
    private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    /** kid(前 8 字符) → 完整 token 列表。理论上可能撞 kid，所以存列表逐个试 */
    private static final Map<String, List<String>> BY_KID = new ConcurrentHashMap<>();
    /** 所有已注册的 token（大写），用于快速判重 */
    private static final Set<String> ALL = ConcurrentHashMap.newKeySet();

    /** kid|nonce → 过期时间戳（毫秒），防重放 */
    private static final Map<String, Long> NONCES = new ConcurrentHashMap<>();
    private static final long NONCE_TTL_MS = 20 * 60 * 1000L;

    /** IP → 失败计数窗口 */
    private static final Map<String, FailWindow> FAILS = new ConcurrentHashMap<>();
    private static final int FAIL_LIMIT = 20;
    private static final long FAIL_WINDOW_MS = 60 * 1000L;
    private static final long BAN_MS = 60 * 1000L;

    private AuthTokens() {
    }

    // ---------------------------------------------------------------- token

    /** 生成 128 bit 随机 token，Base32 编码为 26 字符（大写） */
    public static String generate() {
        byte[] raw = new byte[16];
        RANDOM.nextBytes(raw);
        return base32(raw);
    }

    /** 注册一个 token（从 tile NBT 恢复时也要调，用于重建 kid 索引） */
    public static void register(String token) {
        if (token == null || token.isEmpty()) return;
        String t = token.toUpperCase(Locale.ROOT);
        if (!ALL.add(t)) return;
        BY_KID.computeIfAbsent(kidOf(t), k -> Collections.synchronizedList(new ArrayList<>())).add(t);
    }

    /** token 的前 8 字符，作为请求头里的定位标识 */
    public static String kidOf(String token) {
        String t = token.toUpperCase(Locale.ROOT);
        return t.length() <= 8 ? t : t.substring(0, 8);
    }

    /** 按 kid 找出候选 token（一般只有 1 个） */
    static List<String> candidates(String kid) {
        List<String> list = BY_KID.get(kid == null ? "" : kid.toUpperCase(Locale.ROOT));
        if (list == null) return Collections.emptyList();
        synchronized (list) {
            return new ArrayList<>(list);
        }
    }

    // ------------------------------------------------------------ signature

    /**
     * 规范串（6 行，\n 分隔，末行不带换行）：
     * <pre>
     * AE1
     * METHOD
     * PATH
     * QUERY
     * ts
     * nonce
     * bodyHash
     * </pre>
     * 客户端与服务端必须逐字节一致，否则签名对不上。
     * bodyHash 是请求体原始字节的 SHA-256（小写 hex），无请求体时为空串——
     * 这样即使有人做主动中间人改 POST 参数，签名也会失效。
     */
    public static String canonical(String method, String path, String query, String ts, String nonce, String bodyHash) {
        return "AE1\n"
                + (method == null ? "" : method.toUpperCase(Locale.ROOT)) + "\n"
                + (path == null ? "" : path) + "\n"
                + (query == null ? "" : query) + "\n"
                + (ts == null ? "" : ts) + "\n"
                + (nonce == null ? "" : nonce) + "\n"
                + (bodyHash == null ? "" : bodyHash);
    }

    /** 请求体的 SHA-256（小写 hex）；空数组返回空串 */
    public static String bodyHash(byte[] body) {
        if (body == null || body.length == 0) return "";
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(body);
            StringBuilder sb = new StringBuilder(d.length * 2);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16));
                sb.append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    /** HMAC-SHA256 → base64url（无填充） */
    public static String sign(String token, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] out = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    /** 常量时间比较，避免按字节比较泄露时序 */
    public static boolean matches(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    // ---------------------------------------------------------------- nonce

    /** 记录 nonce；返回 false 表示这个 nonce 已经用过（重放） */
    public static boolean useNonce(String kid, String nonce) {
        long now = System.currentTimeMillis();
        evictNonces(now);
        String key = kid + "|" + nonce;
        Long prev = NONCES.putIfAbsent(key, now + NONCE_TTL_MS);
        return prev == null;
    }

    private static void evictNonces(long now) {
        if (NONCES.size() < 512) return;
        Iterator<Map.Entry<String, Long>> it = NONCES.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue() < now) it.remove();
        }
    }

    // ----------------------------------------------------------- rate limit

    /** 记录一次鉴权失败；返回 true 表示该 IP 已被临时封禁 */
    public static boolean recordFailure(String ip) {
        long now = System.currentTimeMillis();
        FailWindow w = FAILS.computeIfAbsent(ip == null ? "?" : ip, k -> new FailWindow());
        synchronized (w) {
            if (now - w.windowStart > FAIL_WINDOW_MS) {
                w.windowStart = now;
                w.count = 0;
                w.bannedUntil = 0;
            }
            w.count++;
            if (w.count >= FAIL_LIMIT) {
                w.bannedUntil = now + BAN_MS;
                w.count = 0;
                w.windowStart = now;
            }
            return w.bannedUntil > now;
        }
    }

    /** 该 IP 是否处于封禁期 */
    public static boolean isBanned(String ip) {
        FailWindow w = FAILS.get(ip == null ? "?" : ip);
        if (w == null) return false;
        synchronized (w) {
            return w.bannedUntil > System.currentTimeMillis();
        }
    }

    /** 鉴权成功后清掉失败计数，避免正常用户被误伤 */
    public static void clearFailures(String ip) {
        FAILS.remove(ip == null ? "?" : ip);
    }

    private static final class FailWindow {
        long windowStart = System.currentTimeMillis();
        long bannedUntil = 0;
        int count = 0;
    }

    // ---------------------------------------------------------------- base32

    static String base32(byte[] data) {
        StringBuilder sb = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                sb.append(ALPHABET[(buffer >> (bitsLeft - 5)) & 31]);
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            sb.append(ALPHABET[(buffer << (5 - bitsLeft)) & 31]);
        }
        return sb.toString();
    }

    /** 生成 nonce：8 字节随机 → base64url（11 字符） */
    public static String newNonce() {
        byte[] raw = new byte[8];
        RANDOM.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
