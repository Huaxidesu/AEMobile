package dev.youtiao.aemobile.net;

import dev.youtiao.aemobile.AEMobile;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 运行时仪表：只读计数，不改变任何行为。
 *
 * <p>为什么要它：服务端负载是硬约束，而「25 个监控器 × 每 5 秒扫一次」这种量级
 * 靠猜没有意义。这里把每次 AE 扫描的耗时、每帧的字节数、推送调度的占用记下来，
 * 定期写到 {@code dumps/aemobile/stats.json}，跑一天就能看出到底该优化哪一段。
 *
 * <p>开销：每条记录是几次 {@code AtomicLong} 累加，相对一次 AE 扫描（几十毫秒）可以忽略。
 */
public final class Metrics {

    /** 单个 op 的累计统计 */
    public static final class OpStat {
        public final AtomicLong scans = new AtomicLong();
        public final AtomicLong scanMs = new AtomicLong();
        public final AtomicLong scanMaxMs = new AtomicLong();
        public final AtomicLong errors = new AtomicLong();
        public final AtomicLong fullFrames = new AtomicLong();
        public final AtomicLong deltaFrames = new AtomicLong();
        public final AtomicLong heartbeats = new AtomicLong();
        public final AtomicLong bytes = new AtomicLong();
        public final AtomicLong bytesMax = new AtomicLong();
    }

    private static final Map<String, OpStat> OPS = new ConcurrentHashMap<String, OpStat>();
    /** 推送调度：每轮 tick 花在 pushDue 上的时间 */
    private static final AtomicLong TICK_COUNT = new AtomicLong();
    private static final AtomicLong TICK_MS = new AtomicLong();
    private static final AtomicLong TICK_MAX_MS = new AtomicLong();
    /** 当前在线连接数 / 订阅数（每次 tick 覆盖写） */
    private static volatile int connections;
    private static volatile int subscriptions;
    private static final long STARTED_AT = System.currentTimeMillis();
    private static volatile long lastDumpAt = System.currentTimeMillis();

    private Metrics() {
    }

    private static OpStat stat(String op) {
        OpStat s = OPS.get(op);
        if (s == null) {
            OPS.putIfAbsent(op, new OpStat());
            s = OPS.get(op);
        }
        return s;
    }

    /** 一次 AE 扫描（tile 查询）的耗时 */
    public static void scan(String op, long ms, boolean ok) {
        OpStat s = stat(op);
        s.scans.incrementAndGet();
        s.scanMs.addAndGet(ms);
        if (ms > s.scanMaxMs.get()) s.scanMaxMs.set(ms);
        if (!ok) s.errors.incrementAndGet();
    }

    /** 一帧推送出去的体积 */
    public static void frame(String op, String type, int bytes) {
        OpStat s = stat(op);
        if ("f".equals(type)) s.fullFrames.incrementAndGet();
        else if ("d".equals(type)) s.deltaFrames.incrementAndGet();
        else if ("k".equals(type)) s.heartbeats.incrementAndGet();
        s.bytes.addAndGet(bytes);
        if (bytes > s.bytesMax.get()) s.bytesMax.set(bytes);
    }

    /** 一轮推送调度 */
    public static void tick(long ms, int conns, int subs) {
        TICK_COUNT.incrementAndGet();
        TICK_MS.addAndGet(ms);
        if (ms > TICK_MAX_MS.get()) TICK_MAX_MS.set(ms);
        connections = conns;
        subscriptions = subs;
    }

    public static void reset() {
        OPS.clear();
        TICK_COUNT.set(0);
        TICK_MS.set(0);
        TICK_MAX_MS.set(0);
    }

    /** 统计快照（JSON 文本，也是 /aemobile stats 打印的内容） */
    public static String json() {
        Map<String, Object> root = new LinkedHashMap<String, Object>();
        long up = (System.currentTimeMillis() - STARTED_AT) / 1000L;
        root.put("uptimeSec", up);
        root.put("connections", connections);
        root.put("subscriptions", subscriptions);
        Map<String, Object> tick = new LinkedHashMap<String, Object>();
        long ticks = TICK_COUNT.get();
        tick.put("count", ticks);
        tick.put("avgMs", ticks == 0 ? 0 : round(TICK_MS.get() / (double) ticks));
        tick.put("maxMs", TICK_MAX_MS.get());
        root.put("pushTick", tick);
        Map<String, Object> ops = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, OpStat> e : OPS.entrySet()) {
            OpStat s = e.getValue();
            long n = s.scans.get();
            Map<String, Object> o = new LinkedHashMap<String, Object>();
            o.put("scans", n);
            o.put("scanAvgMs", n == 0 ? 0 : round(s.scanMs.get() / (double) n));
            o.put("scanMaxMs", s.scanMaxMs.get());
            o.put("errors", s.errors.get());
            o.put("fullFrames", s.fullFrames.get());
            o.put("deltaFrames", s.deltaFrames.get());
            o.put("heartbeats", s.heartbeats.get());
            o.put("bytesTotal", s.bytes.get());
            o.put("bytesMax", s.bytesMax.get());
            ops.put(e.getKey(), o);
        }
        root.put("ops", ops);
        try {
            return Json.write(root);
        } catch (Exception ex) {
            return "{\"error\":\"serialize failed\"}";
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    /**
     * 写一份快照到 {@code <dir>/stats.json}。
     * 调用方负责控制频率（现在由 TcpServer 的 500ms tick 里按间隔触发）。
     */
    public static synchronized void dump(File dir, long minIntervalMs) {
        long now = System.currentTimeMillis();
        if (minIntervalMs > 0 && now - lastDumpAt < minIntervalMs) return;
        lastDumpAt = now;
        if (dir == null) return;
        try {
            if (!dir.exists() && !dir.mkdirs()) return;
            File target = new File(dir, "stats.json");
            Writer w = new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8);
            try {
                w.write(json());
            } finally {
                w.close();
            }
        } catch (Exception e) {
            // 写统计失败绝不能影响服务端
            AEMobile.debug("[AEMobile] stats 写入失败: " + e);
        }
    }
}
