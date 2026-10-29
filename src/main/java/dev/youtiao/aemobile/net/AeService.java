package dev.youtiao.aemobile.net;

import appeng.crafting.v2.CraftingJobV2;
import com.fasterxml.jackson.databind.JsonNode;
import dev.youtiao.aemobile.AEMobile;
import dev.youtiao.aemobile.blocks.TileAEMonitor;
import dev.youtiao.aemobile.metrics.TickStats;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * TCP 协议的业务层：把 op 名字映射到 {@link TileAEMonitor} 的调用。
 *
 * <p>对应关系（旧的 HTTP 端点 → 新的 op）：
 * <pre>
 * GET  /AE2/getNetworks               → req  "networks"
 * GET  /AE2/getItems                  → sub  "items"
 * GET  /AE2/getItemsNoSSE             → req  "itemsOnce"
 * GET  /AE2/getCraftingCpuInfo        → sub  "cpus"
 * GET  /AE2/getCraftingCpuInfoNoSSE   → req  "cpusOnce"
 * GET  /AE2/getCraftingDetails        → sub  "details"
 * GET  /AE2/getCraftingDetailsNoSSE   → req  "detailsOnce"
 * POST /AE2/generateCraftingPlan      → req  "plan"（多条 RES，more=true 表示进度）
 * POST /AE2/startCraftingJob          → req  "start"
 * POST /AE2/cancelTask                → req  "cancel"
 * </pre>
 *
 * <p><b>线程模型</b>：这些方法都在网络线程（TCP 读线程 / 推送定时器线程）里调用，
 * <b>不要</b>搬到主线程 —— {@code TileAEMonitor} 的查询内部会往它自己的 {@code tasks}
 * 队列塞任务并 {@code FutureTask.get()} 等 {@code updateEntity()}（主线程 tick）执行，
 * 在主线程调就是自己等自己，会把服务器卡死（2026-10-28 真服务器踩过，看门狗 90s 杀服）。
 * 真正需要主线程的活儿由 tile 的队列自己处理。
 */
public final class AeService {

    /** 推送间隔（毫秒） */
    private static final long INTERVAL_ITEMS = 600000L;
    private static final long INTERVAL_CPUS = 5000L;
    private static final long INTERVAL_TASKS = 5000L;
    /** TPS 固定 5 秒一推（App 侧不给选项：窗口内会带峰值，粒度再细只是更耗电） */
    private static final long INTERVAL_TPS = 5000L;
    /**
     * 聊天跟 TcpServer 的 500ms 调度节拍走。**没有新消息就不发帧**，
     * 所以这个间隔只是「最多 500ms 延迟」，不代表每 500ms 都有流量。
     */
    private static final long INTERVAL_CHAT = 500L;

    /** 计划模拟最多跑这么多轮（每轮 1500 个节点），防止病态配方把线程钉死 */
    private static final int PLAN_MAX_ROUNDS = 2000;

    /**
     * plan 是唯一会长时间占主线程的 op（一次几十到几百毫秒）。
     * 30 人服务器上必须限住：全局最多同时算 PLAN_SLOTS 个，单个 token 也不能连点。
     */
    private static final java.util.concurrent.Semaphore PLAN_SLOTS =
            new java.util.concurrent.Semaphore(3);
    /** token → [上次请求时间, 本分钟已用次数, 窗口起始时间] */
    private static final java.util.Map<String, long[]> PLAN_RATE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PLAN_MIN_INTERVAL_MS = 800L;
    private static final int PLAN_PER_MINUTE = 30;

    private AeService() {
    }

    // ------------------------------------------------------------ 工具

    static long intervalOf(String op) {
        if ("items".equals(op)) return INTERVAL_ITEMS;
        if ("cpus".equals(op)) return INTERVAL_CPUS;
        if ("details".equals(op)) return INTERVAL_TASKS;
        if ("tps".equals(op)) return INTERVAL_TPS;
        if ("chat".equals(op)) return INTERVAL_CHAT;
        return 0;
    }

    static boolean isStreamOp(String op) {
        return "items".equals(op) || "cpus".equals(op) || "details".equals(op)
                || "tps".equals(op) || "chat".equals(op);
    }

    /** 找到并校验目标监控器；找不到返回 null（必须在主线程调用） */
    private static TileAEMonitor tile(String token, JsonNode p) {
        int dimid = Json.intOf(p, "dimid", 0);
        int x = Json.intOf(p, "x", 0);
        int y = Json.intOf(p, "y", 0);
        int z = Json.intOf(p, "z", 0);
        Set<TileAEMonitor.PosTuple> tuples = TileAEMonitor.tilesInTheWorld.get(token);
        TileAEMonitor.PosTuple target = new TileAEMonitor.PosTuple(dimid, x, y, z);
        if (tuples == null || !tuples.contains(target)) return null;

        // ⚠️ 绝对不要在这里 initDimension / getChunkFromChunkCoords。
        // 2026-10-28 实测：从 ServerTickEvent 里调 initDimension(180) 会把主线程**死锁**
        // （区块加载要等主线程 tick，而主线程正卡在这个任务里），Spigot 看门狗 90 秒后直接把服务器杀了。
        // 所以只查「已经加载好」的维度/区块，没加载就如实报错，让玩家进一次该维度或加区块加载器。
        World world = DimensionManager.getWorld(dimid);
        if (world == null) return null;

        TileEntity te = world.getTileEntity(x, y, z);
        if (!(te instanceof TileAEMonitor)) {
            // 区块是加载着的但方块不在 → 记录确实过期，摘掉（区块没加载时不动，避免偶发失败误删）
            if (world.blockExists(x, y, z)) {
                tuples.remove(target);
            }
            return null;
        }
        return (TileAEMonitor) te;
    }

    private static String noTileMessage(JsonNode p) {
        return String.format("监控器未就绪：Dim %d (%d, %d, %d) —— 方块不存在，或该维度/区块还没加载"
                        + "（需要有人进一次这个维度，或在基地放区块加载器）",
                Json.intOf(p, "dimid", 0), Json.intOf(p, "x", 0), Json.intOf(p, "y", 0), Json.intOf(p, "z", 0));
    }

    private static Map<String, Object> ok(Object body) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", true);
        m.put("b", body);
        return m;
    }

    private static Map<String, Object> fail(String message) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("ok", false);
        m.put("m", message);
        return m;
    }

    // ------------------------------------------------------- 一次性请求

    /**
     * 一次性请求。返回的 Map 是 RES 的业务字段（ok / b / m），
     * id 和 more 由 {@link TcpConnection} 补。
     */
    static Map<String, Object> once(String token, String op, JsonNode p) throws Exception {
        if ("networks".equals(op)) {
            Set<TileAEMonitor.PosTuple> set = TileAEMonitor.tilesInTheWorld.get(token);
            List<TileAEMonitor.PosTuple> list = set == null
                    ? new ArrayList<TileAEMonitor.PosTuple>()
                    : new ArrayList<TileAEMonitor.PosTuple>(set);
            return ok(list);
        }

        if ("mspt".equals(op)) {
            // 旧版 App 还会探这个 op：现在改由 "tps" 提供，这里保留一句明确的提示
            return fail("NOT_SUPPORTED:use_tps");
        }

        if ("tps".equals(op)) {
            Map<String, Object> body = tpsBody(p, true);
            return body == null ? fail("TPS_NOT_READY") : ok(body);
        }

        if ("chatSend".equals(op)) {
            // 聊天是全局的，不依赖监控器坐标（玩家离线、维度没加载也能说话）。
            // 这里只做过滤 + 限流 + 入环，真正的广播由主线程 tick 执行（见 ChatHub.TickHandler）。
            try {
                long seq = ChatHub.send(token, Json.strOf(p, "name", ""), Json.strOf(p, "text", ""));
                Map<String, Object> body = new LinkedHashMap<String, Object>();
                body.put("seq", seq);
                body.put("boot", ChatHub.boot());
                return ok(body);
            } catch (ChatHub.ChatException e) {
                return fail(e.getMessage());
            }
        }

        TileAEMonitor tile = tile(token, p);
        if (tile == null) return fail(noTileMessage(p));

        if ("itemsOnce".equals(op)) {
            TileAEMonitor.Response<List<TileAEMonitor.ItemStackResponse>> r =
                    tile.getAllStoredItems(Json.boolOf(p, "craftableOnly", true));
            return r.isSucceed() ? ok(r.getBody()) : fail(r.getMessage());
        }
        if ("cpusOnce".equals(op)) {
            TileAEMonitor.Response<List<TileAEMonitor.CraftingCPUInfo>> r = tile.getCraftingCPUInfo();
            return r.isSucceed() ? ok(r.getBody()) : fail(r.getMessage());
        }
        if ("detailsOnce".equals(op)) {
            TileAEMonitor.Response<Collection<TileAEMonitor.CraftTaskItem>> r =
                    tile.getCraftingStatus(Json.intOf(p, "cpuid", -1));
            return r.isSucceed() ? ok(r.getBody()) : fail(r.getMessage());
        }
        if ("cancel".equals(op)) {
            tile.cancelTask(Json.intOf(p, "cpuid", -1));
            return ok("Success");
        }
        if ("start".equals(op)) {
            TileAEMonitor.Response<String> r = tile.submitCraftJob(new TileAEMonitor.CraftRequest(
                    Json.strOf(p, "item", ""),
                    Json.intOf(p, "meta", 0),
                    Json.longOf(p, "count", 0),
                    Json.intOf(p, "cpuId", -1),
                    Json.strOf(p, "nbt", ""),
                    Json.boolOf(p, "allowMissing", false)));
            return r.isSucceed() ? ok(r.getBody()) : fail(r.getMessage());
        }
        return fail("UNKNOWN_OP:" + op);
    }

    // ---------------------------------------------------------- 推送帧

    /**
     * 构造一条推送帧（全量 / 增量 / 心跳）。
     *
     * <p><b>必须在网络线程调，不能在主线程调</b>：{@code TileAEMonitor} 的这些查询内部会
     * 往它自己的 {@code tasks} 队列塞一个 FutureTask，然后 {@code ft.get()} 等
     * {@code updateEntity()}（主线程 tick）执行 —— 在主线程调就是自己等自己，直接死锁
     * （2026-10-28 真服务器上把整个服卡死过，看门狗 90s 杀服）。
     */
    static Map<String, Object> pushFrame(String token, String op, JsonNode p, StreamState st) throws Exception {
        // TPS 是全局数据：不依赖监控器坐标，也不碰 AE2 —— 必须放在 tile() 前面，
        // 否则玩家离线/维度没加载时监控页会跟着一起「不可用」。
        if ("tps".equals(op)) {
            Map<String, Object> body = tpsBody(p, false);
            if (body == null) return FrameBuilder.error("TPS 尚未开始采集，请等一两秒再试");
            Map<String, Object> frame = new LinkedHashMap<String, Object>();
            frame.put("t", "f");
            frame.put("sid", st == null ? "" : st.sid);
            frame.put("seq", st == null ? 0 : st.nextSeq());
            frame.putAll(body);
            return frame;
        }
        TileAEMonitor tile = tile(token, p);
        if (tile == null) return FrameBuilder.error(noTileMessage(p));
        if ("items".equals(op)) return FrameBuilder.items(tile, Json.boolOf(p, "craftableOnly", true), st);
        if ("cpus".equals(op)) return FrameBuilder.cpus(tile, st);
        if ("details".equals(op)) return FrameBuilder.tasks(tile, Json.intOf(p, "cpuid", -1), st);
        return FrameBuilder.error("UNKNOWN_STREAM:" + op);
    }

    // ------------------------------------------------------------ TPS 帧

    /**
     * 组装 TPS 数据体 {@code {ts, srv, w}}。
     *
     * <p>纯读 {@link TickStats} 发布的不可变快照，<b>不碰 World、不碰 AE2</b>，
     * 所以可以在推送线程直接调；一次 90 个维度大约几十微秒。
     *
     * <p>两种口径（这是本服 91 个维度逼出来的设计）：
     * <ul>
     *   <li>{@code full=true}（一次性请求 {@code tps}）：所有维度的名字/人数/实体/区块 + tick 数据，
     *       约 7KB。<b>一个页面只拉一次</b>，用来画世界列表。</li>
     *   <li>{@code full=false}（推送）：只有 {@code p.dims} 里那些维度的 tick 数据，每项 4 个数字，
     *       5 秒一帧约 200B。若没给 dims 就退化成全部。</li>
     * </ul>
     * 之所以不在每帧里带全量清单：91 个维度 × 5 秒 = 4MB/小时，手机上纯浪费流量。
     */
    static Map<String, Object> tpsBody(JsonNode p, boolean full) {
        TickStats.Snapshot snap = TickStats.get().snapshot();
        if (snap == null || snap.at == 0) return null;

        int[] want = dimsOf(p);

        Map<String, Object> srv = new LinkedHashMap<String, Object>();
        srv.put("mspt", round2(snap.srvMspt));
        srv.put("max", round2(snap.srvMax));
        srv.put("tps", round2(snap.srvTps));
        srv.put("ticks", snap.srvTicks);
        srv.put("players", snap.players);

        List<Object> worlds = new ArrayList<Object>(snap.worlds.length);
        for (TickStats.WorldSnap w : snap.worlds) {
            boolean tracked = want == null || contains(want, w.dim);
            if (!full && !tracked) continue;

            Map<String, Object> o = new LinkedHashMap<String, Object>();
            o.put("id", w.dim);
            if (full) {
                o.put("d", w.name);
                o.put("n", w.rawName);
                o.put("p", w.players);
                o.put("e", w.entities);
                o.put("c", w.chunks);
                o.put("pm", w.personal ? 1 : 0);
                o.put("on", w.loaded ? 1 : 0);
            }
            // full 也要按 dims 过滤 tick 数据：清单是清单，检测是检测，
            // 「点开才检测」的语义在一次性请求里同样成立
            if (tracked && w.ticks > 0) {
                o.put("mspt", round2(w.mspt));
                o.put("max", round2(w.max));
                o.put("tps", round2(w.tps));
                o.put("ticks", w.ticks);
            }
            worlds.add(o);
        }

        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("ts", snap.at);
        body.put("srv", srv);
        body.put("w", worlds);
        return body;
    }

    /** 解析 {@code p.dims}；没给或空数组返回 null 表示「全部」 */
    private static int[] dimsOf(JsonNode p) {
        if (p == null) return null;
        JsonNode arr = p.get("dims");
        if (arr == null || !arr.isArray() || arr.size() == 0) return null;
        int[] out = new int[arr.size()];
        for (int i = 0; i < out.length; i++) out[i] = arr.get(i).asInt();
        return out;
    }

    private static boolean contains(int[] arr, int v) {
        for (int i : arr) if (i == v) return true;
        return false;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    // ------------------------------------------------------- 生成合成计划

    /** 计划生成过程中的回调（在调用线程上串行触发） */
    interface PlanSink {
        void progress(int resolved);

        void done(Object body);

        void fail(String message);
    }

    /**
     * 生成合成计划。模拟过程分片跑在主线程上（每片 1500 个节点），
     * 片与片之间回一条进度帧 —— 这样既不会长时间卡 tick，也不会在非主线程碰 AE2。
     */
    static void plan(String token, JsonNode p, PlanSink sink) {
        String limited = planRateCheck(token);
        if (limited != null) {
            sink.fail(limited);
            return;
        }
        if (!PLAN_SLOTS.tryAcquire()) {
            sink.fail("服务器正在同时算别的合成计划，请过几秒再试");
            return;
        }
        try {
            TileAEMonitor tile = tile(token, p);
            if (tile == null) {
                sink.fail(noTileMessage(p));
                return;
            }
            String item = Json.strOf(p, "item", "");
            int meta = Json.intOf(p, "meta", 0);
            long count = Json.longOf(p, "count", 0);
            String nbt = Json.strOf(p, "nbt", "");

            CraftingJobV2 job = tile.generateCraftingPlan(item, meta, nbt, count);
            if (job == null) {
                sink.fail("PLAN_FAILED");
                return;
            }

            // 分片模拟：每片 1500 个节点，片之间回一条进度。
            // 每一片都必须回主线程跑（tile.simulatePlanShard）：AE2 的 ItemList / 合成上下文
            // 不是线程安全的，在 TCP 线程碰会和 tick 里的机器抢同一批集合（2026-10-28 崩服事故）。
            for (int round = 0; round < PLAN_MAX_ROUNDS; round++) {
                TileAEMonitor.PlanShard shard = tile.simulatePlanShard(job, 1500);
                if (shard.done) {
                    TileAEMonitor.Response<TileAEMonitor.CraftPlan> r = tile.populatePlan(job);
                    if (r.isSucceed()) {
                        sink.done(r.getBody());
                    } else {
                        sink.fail(r.getMessage());
                    }
                    return;
                }
                sink.progress(shard.resolved);
            }
            sink.fail("PLAN_TIMEOUT");
        } catch (Exception e) {
            // 打完整栈：计划失败的根因经常在 AE2 内部，只有 message 看不出是哪一层
            AEMobile.error("[AEMobile] 生成合成计划失败: " + e, e);
            sink.fail(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        } finally {
            PLAN_SLOTS.release();
        }
    }

    /**
     * 单个 token 的计划请求限速：两次之间至少 {@link #PLAN_MIN_INTERVAL_MS}，
     * 每分钟最多 {@link #PLAN_PER_MINUTE} 次。返回 null 表示放行，否则是要回给 App 的提示。
     */
    private static String planRateCheck(String token) {
        long now = System.currentTimeMillis();
        long[] st = PLAN_RATE.get(token);
        if (st == null) {
            st = new long[]{0L, 0L, now};
            PLAN_RATE.put(token, st);
        }
        synchronized (st) {
            if (now - st[2] >= 60_000L) { // 新窗口
                st[2] = now;
                st[1] = 0;
            }
            if (st[1] >= PLAN_PER_MINUTE) {
                return "请求太频繁了，合成计划每分钟最多 " + PLAN_PER_MINUTE + " 次";
            }
            if (now - st[0] < PLAN_MIN_INTERVAL_MS) {
                return "请求太快了，请稍等一下再试";
            }
            st[0] = now;
            st[1]++;
        }
        return null;
    }
}
