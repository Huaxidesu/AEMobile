package dev.youtiao.aemobile.net;

import dev.youtiao.aemobile.AEMobile;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * TCP 监听器（协议 3.0.0 的唯一入口，默认 44444）。
 *
 * <p>2026-10-27 起 mod 不再内嵌 Tomcat/Spring：HTTP 那套被阿里云备案拦截
 * （按 Host 头判断，非 80/443 也拦），而裸 TCP 不受影响。
 *
 * <p>一个进程只允许一个实例；服务器启停时由 CommonProxy 调用 {@link #start(int)} / {@link #stop()}。
 */
public final class TcpServer {

    /** 并发连接上限（自己人用，几个人足够；防止被当端口扫描器打） */
    private static final int MAX_CONNECTIONS = 32;
    /** 超过这个时间没收到任何帧就断开（客户端每 20s 发 PING） */
    private static final long IDLE_TIMEOUT_MS = 180000L;
    /** 推送调度粒度：500ms，让 5s 的流抖动不超过半秒 */
    private static final long TICK_MS = 500L;

    private static TcpServer instance;

    private final ServerSocket serverSocket;
    private final Set<TcpConnection> connections = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService ticker;

    private TcpServer(int port) throws IOException {
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(port));
        this.ticker = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "AEMobile-tcp-ticker");
                t.setDaemon(true);
                return t;
            }
        });
    }

    /** 启动；端口被占用等失败只打日志，不影响服务器本身 */
    public static synchronized void start(int port) {
        if (instance != null) return;
        try {
            instance = new TcpServer(port);
            instance.begin();
            AEMobile.info("[AEMobile] TCP 服务已启动，端口 " + port);
        } catch (IOException e) {
            instance = null;
            AEMobile.error("[AEMobile] TCP 端口 " + port + " 启动失败：" + e.getMessage());
        }
    }

    public static synchronized void stop() {
        if (instance == null) return;
        instance.shutdown();
        instance = null;
        AEMobile.info("[AEMobile] TCP 服务已停止");
    }

    private void begin() {
        Thread accept = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "AEMobile-tcp-accept");
        accept.setDaemon(true);
        accept.start();

        ticker.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                tick();
            }
        }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS);
    }

    private void acceptLoop() {
        while (!serverSocket.isClosed()) {
            Socket socket;
            try {
                socket = serverSocket.accept();
            } catch (IOException e) {
                break; // 关闭时 accept 抛 SocketException，正常退出
            }
            if (connections.size() >= MAX_CONNECTIONS) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
                continue;
            }
            try {
                TcpConnection conn = new TcpConnection(this, socket);
                connections.add(conn);
                conn.start();
            } catch (IOException e) {
                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private void tick() {
        long now = System.currentTimeMillis();
        for (TcpConnection conn : connections) {
            if (conn.isClosed()) {
                connections.remove(conn);
                continue;
            }
            if (conn.idleMs() > IDLE_TIMEOUT_MS) {
                conn.close("idle");
                continue;
            }
            try {
                conn.pushDue();
            } catch (Throwable t) {
                // 单个连接出问题不能影响其它人
                AEMobile.debug("[AEMobile] tick 异常：" + t);
            }
        }
    }

    void remove(TcpConnection conn) {
        connections.remove(conn);
    }

    /** 同一 sid 只允许一条活连接持有：新连接续传时把旧的摘掉 */
    void detachSid(String sid, TcpConnection except) {
        for (TcpConnection conn : connections) {
            if (conn != except) conn.detachStream(sid);
        }
    }

    private void shutdown() {
        try {
            serverSocket.close();
        } catch (IOException ignored) {
        }
        for (TcpConnection conn : connections) {
            conn.close("shutdown");
        }
        connections.clear();
        ticker.shutdownNow();
    }
}
