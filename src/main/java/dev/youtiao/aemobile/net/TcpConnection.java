package dev.youtiao.aemobile.net;

import com.fasterxml.jackson.databind.JsonNode;
import dev.youtiao.aemobile.AEMobile;
import dev.youtiao.aemobile.Tags;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 一条 TCP 连接。
 *
 * <p>三个线程：
 * <ul>
 *   <li><b>读线程</b>（{@link #run()}）：解析帧、处理握手/鉴权/请求/订阅。请求是串行的，
 *       因为每个请求都要等 MC 主线程返回；App 同时只会有一个慢请求（生成计划）。</li>
 *   <li><b>写线程</b>（{@link #writeLoop()}）：从发送队列取字节写 socket。
 *       分出来是为了「推送调度线程」永远不会被某个慢客户端拖住。</li>
 *   <li><b>推送</b>（{@link #pushDue()}）：由 {@link TcpServer} 的定时器调用，不占本连接线程。</li>
 * </ul>
 *
 * <p>鉴权流程见 doc/传输协议与安全.md §3：HELLO 拿 nonce → AUTH 用 HMAC(token, "AE2TCP\n ts\n nonce")
 * 签名，token 本身不上网线；nonce 每连接一次，天然防重放。
 */
final class TcpConnection implements Runnable {

    /** 单连接最多同时订阅几条流（App 最多 3 条：items / cpus / details） */
    private static final int MAX_STREAMS = 8;
    /** 发送队列上限，满了说明客户端不读数据，直接断开 */
    private static final int OUTBOX_CAPACITY = 512;
    /** 鉴权失败几次后断开 */
    private static final int MAX_AUTH_FAILS = 5;

    private final TcpServer server;
    private final Socket socket;
    private final String ip;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Object writeLock = new Object();
    private final ArrayBlockingQueue<byte[]> outbox = new ArrayBlockingQueue<byte[]>(OUTBOX_CAPACITY);
    private final Map<Integer, Sub> subs = new ConcurrentHashMap<Integer, Sub>();
    private final AtomicInteger streamSeq = new AtomicInteger();

    private volatile boolean closed;
    private volatile String token;
    private volatile String nonce;
    private volatile long lastSeen = System.currentTimeMillis();
    private int authFails;

    /** 一条订阅 */
    static final class Sub {
        final int id;
        final String op;
        final JsonNode params;
        final StreamState state;
        final long intervalMs;
        volatile long due;

        Sub(int id, String op, JsonNode params, StreamState state, long intervalMs) {
            this.id = id;
            this.op = op;
            this.params = params;
            this.state = state;
            this.intervalMs = intervalMs;
        }
    }

    TcpConnection(TcpServer server, Socket socket) throws IOException {
        this.server = server;
        this.socket = socket;
        this.ip = socket.getInetAddress() == null ? "?" : socket.getInetAddress().getHostAddress();
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        this.in = new DataInputStream(new java.io.BufferedInputStream(socket.getInputStream(), 8192));
        this.out = new DataOutputStream(new java.io.BufferedOutputStream(socket.getOutputStream(), 8192));
    }

    String ip() {
        return ip;
    }

    boolean isClosed() {
        return closed;
    }

    void start() {
        Thread reader = new Thread(this, "AEMobile-tcp-read-" + ip);
        reader.setDaemon(true);
        reader.start();
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                writeLoop();
            }
        }, "AEMobile-tcp-write-" + ip);
        writer.setDaemon(true);
        writer.start();
    }

    // ------------------------------------------------------------ 读循环

    @Override
    public void run() {
        try {
            while (!closed) {
                Protocol.Frame frame = Protocol.read(in);
                if (frame == null) break;
                lastSeen = System.currentTimeMillis();
                handle(frame);
            }
        } catch (Exception e) {
            // 客户端断开、帧格式错误、socket 被关 —— 都按正常收尾处理
        } finally {
            close("EOF");
        }
    }

    private void handle(Protocol.Frame frame) {
        switch (frame.type) {
            case Protocol.T_HELLO:
                onHello(frame);
                break;
            case Protocol.T_AUTH:
                onAuth(frame);
                break;
            case Protocol.T_REQ:
                onRequest(frame);
                break;
            case Protocol.T_SUB:
                onSubscribe(frame);
                break;
            case Protocol.T_UNSUB:
                onUnsubscribe(frame);
                break;
            case Protocol.T_PING:
                sendFrame(Protocol.T_PONG, null);
                break;
            default:
                // 未知帧类型：忽略，别把连接搞死（方便以后加帧）
                break;
        }
    }

    // ------------------------------------------------------------ 握手

    private void onHello(Protocol.Frame frame) {
        JsonNode n = Json.read(frame.payload);
        int version = Json.intOf(n, "v", 0);
        if (version != Protocol.VERSION) {
            sendErr("VERSION_MISMATCH:server=" + Protocol.VERSION);
            close("version");
            return;
        }
        // nonce 每连接一个新值，AUTH 签名里带上它 → 抓到的 AUTH 帧换条连接就失效
        nonce = AuthTokens.newNonce();
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("v", Protocol.VERSION);
        m.put("name", Tags.MODNAME);
        m.put("ver", Tags.VERSION);
        m.put("time", System.currentTimeMillis() / 1000L);
        m.put("nonce", nonce);
        sendFrame(Protocol.T_HELLO_ACK, m);
    }

    private void onAuth(Protocol.Frame frame) {
        if (token != null) {
            sendFrame(Protocol.T_AUTH_OK, null);
            return;
        }
        if (nonce == null) {
            sendErr("HELLO_REQUIRED");
            return;
        }
        if (AuthTokens.isBanned(ip)) {
            sendAuthFail("BANNED", 0);
            close("banned");
            return;
        }

        JsonNode n = Json.read(frame.payload);
        String kid = Json.strOf(n, "kid", "");
        String sig = Json.strOf(n, "sig", "");
        long ts = Json.longOf(n, "ts", 0L);
        long now = System.currentTimeMillis() / 1000L;

        if (Math.abs(now - ts) > 600) {
            sendAuthFail("CLOCK", now);
            return;
        }

        String canonical = "AE2TCP\n" + ts + "\n" + nonce;
        java.util.List<String> candidates = AuthTokens.candidates(kid);
        if (candidates.isEmpty()) {
            // 没这个 kid：多半是密码抄错/少一位（前 8 位对不上）
            AEMobile.warn("[AEMobile] 鉴权失败：kid=" + kid + " 不存在（" + ip + "）");
        }
        for (String candidate : candidates) {
            if (AuthTokens.matches(AuthTokens.sign(candidate, canonical), sig)) {
                token = candidate;
                AuthTokens.clearFailures(ip);
                sendFrame(Protocol.T_AUTH_OK, null);
                AEMobile.info("[AEMobile] TCP 连接已鉴权：" + ip);
                return;
            }
        }

        if (!candidates.isEmpty()) {
            // kid 能对上但签名不对：密码里有个别字符错了。
            // 把 ts / nonce / 收到的 sig / 按候选 token 算出的 sig 全打出来，方便离线比对。
            String expected = AuthTokens.sign(candidates.get(0), canonical);
            AEMobile.warn("[AEMobile] 鉴权失败 kid=" + kid + " ts=" + ts + " nonce=" + nonce
                    + " recv=" + sig + " expect=" + expected + " 候选数=" + candidates.size() + " ip=" + ip);
        }
        AuthTokens.recordFailure(ip);
        authFails++;
        sendAuthFail("AUTH_FAILED", now);
        if (authFails >= MAX_AUTH_FAILS) close("auth-fails");
    }

    private void sendAuthFail(String code, long serverTime) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("code", code);
        if (serverTime > 0) m.put("time", serverTime);
        sendFrame(Protocol.T_AUTH_FAIL, m);
    }

    // ------------------------------------------------------------ 请求

    private void onRequest(Protocol.Frame frame) {
        JsonNode root = Json.read(frame.payload);
        final int id = Json.intOf(root, "id", 0);
        String op = Json.strOf(root, "op", "");
        JsonNode p = root == null ? null : root.get("p");

        if (token == null) {
            sendRes(id, false, null, "AUTH_REQUIRED", false);
            return;
        }

        if ("plan".equals(op)) {
            // 生成计划：进度帧 + 最终帧，都在同一个 id 上，more 区分
            AeService.plan(token, p, new AeService.PlanSink() {
                @Override
                public void progress(int resolved) {
                    sendRes(id, true, "SIMULATING - resolved: " + resolved, null, true);
                }

                @Override
                public void done(Object body) {
                    sendRes(id, true, body, null, false);
                }

                @Override
                public void fail(String message) {
                    sendRes(id, false, null, message, false);
                }
            });
            return;
        }

        try {
            Map<String, Object> result = AeService.once(token, op, p);
            Object ok = result.get("ok");
            sendRes(id, Boolean.TRUE.equals(ok), result.get("b"), (String) result.get("m"), false);
        } catch (Exception e) {
            sendRes(id, false, null, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(), false);
        }
    }

    private void sendRes(int id, boolean ok, Object body, String message, boolean more) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("ok", ok);
        m.put("b", body);
        if (message != null) m.put("m", message);
        if (more) m.put("more", true);
        sendFrame(Protocol.T_RES, m);
    }

    // ------------------------------------------------------------ 订阅

    private void onSubscribe(Protocol.Frame frame) {
        JsonNode root = Json.read(frame.payload);
        int id = Json.intOf(root, "id", 0);
        String op = Json.strOf(root, "op", "");
        JsonNode p = root == null ? null : root.get("p");

        if (token == null) {
            sendRes(id, false, null, "AUTH_REQUIRED", false);
            return;
        }
        if (!AeService.isStreamOp(op)) {
            sendRes(id, false, null, "UNKNOWN_STREAM:" + op, false);
            return;
        }
        if (subs.size() >= MAX_STREAMS) {
            sendRes(id, false, null, "TOO_MANY_STREAMS", false);
            return;
        }

        // sid 由客户端带上来：断线重连时服务端能接着发增量，不用重建索引。
        // 先把「同 sid 的旧订阅」从其它连接上摘掉：旧连接可能只是半开（服务端还没超时），
        // 两边共用一份 StreamState 会让新客户端漏掉旧连接推进过的那部分变化。
        String resumeSid = Json.strOf(root, "sid", null);
        if (resumeSid != null && !resumeSid.isEmpty()) server.detachSid(resumeSid, this);
        StreamState state = StreamState.acquire(resumeSid);
        int streamId = streamSeq.incrementAndGet();
        Sub sub = new Sub(streamId, op, p, state, AeService.intervalOf(op));
        sub.due = 0L; // 立刻发首帧
        subs.put(streamId, sub);

        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("id", id);
        m.put("s", streamId);
        m.put("sid", state.sid);
        sendFrame(Protocol.T_SUB_OK, m);
    }

    /** 把指定 sid 的订阅从本连接摘掉（新连接接管断线续传时用） */
    void detachStream(String sid) {
        if (sid == null) return;
        for (Sub sub : subs.values()) {
            if (sid.equals(sub.state.sid)) subs.remove(sub.id);
        }
    }

    private void onUnsubscribe(Protocol.Frame frame) {
        JsonNode root = Json.read(frame.payload);
        subs.remove(Json.intOf(root, "s", -1));
    }

    // ------------------------------------------------------------ 推送

    /** 由定时器调用：把到期的订阅推出去 */
    void pushDue() {
        if (closed || token == null || subs.isEmpty()) return;
        long now = System.currentTimeMillis();
        for (Sub sub : subs.values()) {
            if (sub.due > now) continue;
            sub.due = now + sub.intervalMs;
            try {
                Map<String, Object> frame = AeService.pushFrame(token, sub.op, sub.params, sub.state);
                Map<String, Object> env = new LinkedHashMap<String, Object>();
                env.put("s", sub.id);
                env.put("f", frame);
                sendFrame(Protocol.T_PUSH, env);
            } catch (Exception e) {
                // 主线程超时（区块没加载、服务器卡顿）：跳过这一帧，下一轮再试
                AEMobile.debug("[AEMobile] 推送失败 " + sub.op + ": " + e);
            }
        }
    }

    long idleMs() {
        return System.currentTimeMillis() - lastSeen;
    }

    // ------------------------------------------------------------ 发送

    private void sendFrame(int type, Object payload) {
        if (closed) return;
        byte[] body = payload == null ? new byte[0] : Protocol.utf8(Json.write(payload));
        ByteArrayOutputStream bos = new ByteArrayOutputStream(body.length + 8);
        try {
            Protocol.write(new DataOutputStream(bos), type, body);
        } catch (IOException e) {
            close("encode");
            return;
        }
        if (!outbox.offer(bos.toByteArray())) {
            // 客户端不读数据（挂死的手机）：断开，别让队列无限涨
            close("outbox-full");
        }
    }

    private void sendErr(String message) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("m", message);
        sendFrame(Protocol.T_ERR, m);
    }

    private void writeLoop() {
        try {
            while (!closed) {
                byte[] bytes = outbox.take();
                synchronized (writeLock) {
                    out.write(bytes);
                    out.flush();
                }
            }
        } catch (Exception e) {
            close("write");
        }
    }

    // ------------------------------------------------------------ 关闭

    void close(String reason) {
        if (closed) return;
        closed = true;
        if (!"EOF".equals(reason)) {
            AEMobile.info("[AEMobile] TCP 断开 " + ip + " (" + reason + ")");
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
        subs.clear();
        server.remove(this);
        AEMobile.debug("[AEMobile] TCP 断开 " + ip + " (" + reason + ")");
    }
}
