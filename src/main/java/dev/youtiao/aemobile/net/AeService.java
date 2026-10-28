package dev.youtiao.aemobile.net;

import appeng.crafting.v2.CraftingJobV2;
import com.fasterxml.jackson.databind.JsonNode;
import dev.youtiao.aemobile.AEMobile;
import dev.youtiao.aemobile.blocks.TileAEMonitor;
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

    /** 计划模拟最多跑这么多轮（每轮 1500 个节点），防止病态配方把线程钉死 */
    private static final int PLAN_MAX_ROUNDS = 2000;

    private AeService() {
    }

    // ------------------------------------------------------------ 工具

    static long intervalOf(String op) {
        if ("items".equals(op)) return INTERVAL_ITEMS;
        if ("cpus".equals(op)) return INTERVAL_CPUS;
        if ("details".equals(op)) return INTERVAL_TASKS;
        return 0;
    }

    static boolean isStreamOp(String op) {
        return "items".equals(op) || "cpus".equals(op) || "details".equals(op);
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
            // 监控插件未接入：App 用这个错误走「服务器监控插件未启用」空态
            return fail("NOT_SUPPORTED");
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
        TileAEMonitor tile = tile(token, p);
        if (tile == null) return FrameBuilder.error(noTileMessage(p));
        if ("items".equals(op)) return FrameBuilder.items(tile, Json.boolOf(p, "craftableOnly", true), st);
        if ("cpus".equals(op)) return FrameBuilder.cpus(tile, st);
        if ("details".equals(op)) return FrameBuilder.tasks(tile, Json.intOf(p, "cpuid", -1), st);
        return FrameBuilder.error("UNKNOWN_STREAM:" + op);
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
        }
    }
}
