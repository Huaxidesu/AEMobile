package dev.youtiao.aemobile.net;

import com.fasterxml.jackson.databind.JsonNode;
import dev.youtiao.aemobile.AEMobile;
import dev.youtiao.aemobile.Config;
import dev.youtiao.aemobile.Tags;
import dev.youtiao.aemobile.compat.GtnChatCompat;

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
    /**
     * 游客连接的 token 占位符。
     *
     * <p>游客没有密码，但下游（请求/订阅/推送）到处都在判 {@code token == null}，
     * 所以给它一个**永远不会和真 token 撞车**的哨兵值，再单独用 {@link #guest} 拦权限。
     * 真 token 是 26 位随机字符，不可能出现 NUL 字符。
     */
    private static final String GUEST_TOKEN_PREFIX = "\0guest:";
    /** 游客 token 的序号段：token 是聊天限流的键，每条连接要不一样，否则一个刷屏的游客会占用所有人的配额 */
    private static final AtomicInteger GUEST_SEQ = new AtomicInteger();
    /** 当前在线游客数（总连接上限 32，游客要单独配额，不能挤占正式用户的额度） */
    private static final AtomicInteger GUEST_CONNS = new AtomicInteger();

    private final TcpServer server;
    private final Socket socket;
    private final String ip;
    private final DataInputStream in;
    private final DataOutputStream out;
    private final Object writeLock = new Object();
    private final Object closeLock = new Object();
    private final ArrayBlockingQueue<byte[]> outbox = new ArrayBlockingQueue<byte[]>(OUTBOX_CAPACITY);
    private final Map<Integer, Sub> subs = new ConcurrentHashMap<Integer, Sub>();
    private final AtomicInteger streamSeq = new AtomicInteger();

    private volatile boolean closed;
    /** 这条连接是游客：免绑定码、只能收聊天、不能发也不能调请求 */
    private volatile boolean guest;
    private volatile String token;
    private volatile String nonce;
    private volatile long lastSeen = System.currentTimeMillis();
    private int authFails;

    /** 一条订阅 */
    static final class Sub {
        final int id;
        final String op;
        final JsonNode params;
        /** 只有 items/cpus/details/tps 用；chat 是纯追加流，不需要增量状态 */
        final StreamState state;
        final long intervalMs;
        volatile long due;
        /** chat 专用：已经发到哪一条（服务端消息 seq） */
        volatile long cursor;
        /** chat 专用：是否要玩家加入/退出事件（旧版 App 不带这个参数） */
        volatile boolean events;

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
                // 未知帧类型：忽略，不中断连接（方便以后加帧）
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
        // 本服在群组服里的显示名（GTNHChat 的 Config.serverDisplay）。
        // 手机端拿它区分「本服」和「其它服务器」—— 本服消息的来源标签显示成灰色，跨服的才用配置色。
        // 未安装 GTNHChat 时是空串，手机端一视同仁。
        m.put("srv", GtnChatCompat.serverDisplay());
        // 这台服实际能提供的可选能力（聊天格式化 / 队伍 / 世界名 / 跨服）。
        // App 据此决定要不要显示队伍·服务器·世界标签、要不要给「标签常驻」这个样式 ——
        // 未安装的服务器上那些 UI 直接不出现，而不是保留点击后无反应的入口。旧版 App 忽略这个字段。
        m.put("caps", GtnChatCompat.caps());
        // 本服是否启用「游客只读聊天」。App 的首次配置页据此决定要不要给「仅使用聊天功能」这个入口
        // （旧服务端没有这个字段，App 按「不支持」处理）
        m.put("guest", Config.chatGuest);
        // 游客能不能发言（[chat] guestSay）。旧服务端没有这个字段，App 按「只读游客」处理
        m.put("guestsay", Config.chatGuestSay);
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

        // 游客：没有 kid/ts/sig，只声明 {guest:true}。服务端没开就明确拒绝，
        // 开了也只给「读聊天」这一项权限（见 onRequest / onSubscribe）。
        if (Json.boolOf(n, "guest", false)) {
            if (!Config.chatGuest) {
                sendAuthFail("GUEST_DISABLED", System.currentTimeMillis() / 1000L);
                return;
            }
            if (GUEST_CONNS.get() >= Config.chatGuestMax) {
                sendAuthFail("GUEST_FULL", System.currentTimeMillis() / 1000L);
                return;
            }
            GUEST_CONNS.incrementAndGet();
            guest = true;
            token = GUEST_TOKEN_PREFIX + ip + ":" + GUEST_SEQ.incrementAndGet();
            sendFrame(Protocol.T_AUTH_OK, null);
            AEMobile.info("[AEMobile] 游客连接（只读聊天）：" + ip);
            return;
        }

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
            // 没这个 kid：多半是密码输入错误/少一位（前 8 位对不上）
            AEMobile.warn("[AEMobile] 鉴权失败：kid=" + kid + " 不存在（" + ip + "）");
        }
        for (String candidate : candidates) {
            if (AuthTokens.matches(AuthTokens.sign(candidate, canonical), sig)
                    && AuthTokens.isRegistered(candidate)) {
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
        if (guest && !("chatSend".equals(op) && Config.chatGuestSay)) {
            // 游客只能聊公屏（guestSay 启用时还可发言）：查物品、点歌、TPS 全部拦截在这里
            sendRes(id, false, null, "GUEST_READONLY", false);
            return;
        }
        // 游客的 token 是哨兵值、不在注册表里，不能按「被吊销」处理
        if (!guest && !AuthTokens.isRegistered(token)) {
            close("token-revoked");
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
            long t0 = System.nanoTime();
            Map<String, Object> result = AeService.once(token, op, p);
            Metrics.scan("req:" + op, (System.nanoTime() - t0) / 1000000L, Boolean.TRUE.equals(result.get("ok")));
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
        if (guest && !"chat".equals(op)) {
            sendRes(id, false, null, "GUEST_READONLY", false);
            return;
        }
        if (!guest && !AuthTokens.isRegistered(token)) {
            close("token-revoked");
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

        // 聊天是纯追加流：不需要 StreamState / sid，游标（since）由客户端带上来，
        // 重连时按 since 续传。boot 对不上说明服务端重启过（seq 归零），从头开始。
        if ("chat".equals(op)) {
            long since = Json.longOf(p, "since", 0L);
            if (Json.longOf(p, "boot", 0L) != ChatHub.boot()) since = 0L;
            int chatStreamId = streamSeq.incrementAndGet();
            Sub chatSub = new Sub(chatStreamId, op, p, null, AeService.intervalOf(op));
            chatSub.cursor = since;
            chatSub.events = Json.boolOf(p, "events", false);
            chatSub.due = 0L;
            subs.put(chatStreamId, chatSub);
            ChatHub.addSubscriber();
            Map<String, Object> cm = new LinkedHashMap<String, Object>();
            cm.put("id", id);
            cm.put("s", chatStreamId);
            cm.put("sid", "");
            sendFrame(Protocol.T_SUB_OK, cm);
            return;
        }

        // sid 由客户端带上来：断线重连时服务端能接着发增量，不用重建索引。
        // 先把「同 sid 的旧订阅」从其它连接上移除：旧连接可能只是半开（服务端还没超时），
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

    /** 把指定 sid 的订阅从本连接移除（新连接接管断线续传时用） */
    void detachStream(String sid) {
        if (sid == null) return;
        for (Sub sub : subs.values()) {
            if (sub.state != null && sid.equals(sub.state.sid)) subs.remove(sub.id);
        }
    }

    private void onUnsubscribe(Protocol.Frame frame) {
        JsonNode root = Json.read(frame.payload);
        Sub sub = subs.remove(Json.intOf(root, "s", -1));
        if (sub != null && "chat".equals(sub.op)) ChatHub.removeSubscriber();
    }

    // ------------------------------------------------------------ 推送

    /** 由定时器调用：把到期的订阅推出去 */
    void pushDue() {
        if (closed || token == null || subs.isEmpty()) return;
        if (!guest && !AuthTokens.isRegistered(token)) {
            close("token-revoked");
            return;
        }
        long now = System.currentTimeMillis();
        for (Sub sub : subs.values()) {
            if (sub.due > now) continue;
            sub.due = now + sub.intervalMs;

            // 聊天：按游标取增量，没有新消息就**不发帧**（空闲零流量）
            if ("chat".equals(sub.op)) {
                ChatHub.Batch batch = ChatHub.drain(sub.cursor, 40, sub.events);
                if (batch.frame == null) {
                    // 可能只是跳过了被过滤的进出事件，游标也要前进
                    sub.cursor = batch.next;
                    continue;
                }
                sub.cursor = batch.next;
                Map<String, Object> chatEnv = new LinkedHashMap<String, Object>();
                chatEnv.put("s", sub.id);
                chatEnv.put("f", batch.frame);
                int chatBytes = sendFrame(Protocol.T_PUSH, chatEnv);
                Metrics.frame("chat", "c", chatBytes);
                continue;
            }

            long t0 = System.nanoTime();
            try {
                Map<String, Object> frame = AeService.pushFrame(token, sub.op, sub.params, sub.state);
                long ms = (System.nanoTime() - t0) / 1000000L;
                Map<String, Object> env = new LinkedHashMap<String, Object>();
                env.put("s", sub.id);
                env.put("f", frame);
                int bytes = sendFrame(Protocol.T_PUSH, env);
                Object type = frame.get("t");
                Metrics.scan(sub.op, ms, !"e".equals(type));
                Metrics.frame(sub.op, String.valueOf(type), bytes);
            } catch (Exception e) {
                // 主线程超时（区块未加载、服务器失去响应）：跳过这一帧，下一轮再试
                Metrics.scan(sub.op, (System.nanoTime() - t0) / 1000000L, false);
                AEMobile.debug("[AEMobile] 推送失败 " + sub.op + ": " + e);
            }
        }
    }

    long idleMs() {
        return System.currentTimeMillis() - lastSeen;
    }

    /** 当前订阅数（统计用） */
    int subCount() {
        return subs.size();
    }

    /** 这条连接鉴权时用的 token（重置绑定码时按它强制断开连接） */
    String token() {
        return token;
    }

    // ------------------------------------------------------------ 发送

    /** 发一帧；返回 payload 字节数（0 表示连接已关），给统计用 */
    private int sendFrame(int type, Object payload) {
        if (closed) return 0;
        byte[] body = payload == null ? new byte[0] : Protocol.utf8(Json.write(payload));
        ByteArrayOutputStream bos = new ByteArrayOutputStream(body.length + 8);
        try {
            Protocol.write(new DataOutputStream(bos), type, body);
        } catch (IOException e) {
            close("encode");
            return 0;
        }
        if (!outbox.offer(bos.toByteArray())) {
            // 客户端不读数据（挂起的手机）：断开，避免队列无限增长
            close("outbox-full");
        }
        return body.length;
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
        synchronized (closeLock) {
            if (closed) return;
            closed = true;
            if (!"EOF".equals(reason)) {
                AEMobile.info("[AEMobile] TCP 断开 " + ip + " (" + reason + ")");
            }
            try {
                socket.close();
            } catch (IOException ignored) {
            }
            for (Sub sub : subs.values()) {
                if ("chat".equals(sub.op)) ChatHub.removeSubscriber();
            }
            subs.clear();
            if (guest) {
                guest = false;
                GUEST_CONNS.decrementAndGet();
            }
            server.remove(this);
            AEMobile.debug("[AEMobile] TCP 断开 " + ip + " (" + reason + ")");
        }
    }
}
