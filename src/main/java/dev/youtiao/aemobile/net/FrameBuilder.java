package dev.youtiao.aemobile.net;

import dev.youtiao.aemobile.blocks.TileAEMonitor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 三条推送流（items / cpus / tasks）的帧构造。
 *
 * <p>从旧的 AEPanelController 里原样抽出来，帧格式<b>一个字节都没改</b>——
 * App 的 frames.ts 累加器可以继续用。信封：
 *
 * <ul>
 *   <li>{@code {"t":"f","sid":..,"seq":..,"d":[...],"n":{..}}} 全量</li>
 *   <li>{@code {"t":"d","sid":..,"seq":..,"c":[],"a":[],"x":[]}} 增量</li>
 *   <li>{@code {"t":"k","seq":..}} 心跳（内容没变）</li>
 * </ul>
 *
 * <p><b>在网络线程调用</b>（不要在主线程）：tile 的查询内部会等主线程 tick 执行它自己的任务队列，
 * 在主线程调会自己等自己。详见 {@code AeService} 的线程模型说明。
 */
public final class FrameBuilder {

    private FrameBuilder() {
    }

    /** 错误帧 */
    public static Map<String, Object> error(String message) {
        Map<String, Object> f = new LinkedHashMap<String, Object>();
        f.put("t", "e");
        f.put("m", message);
        return f;
    }

    // ------------------------------------------------------------ items

    public static Map<String, Object> items(TileAEMonitor tile, boolean craftableOnly, StreamState st) {
        TileAEMonitor.Response<List<TileAEMonitor.ItemStackResponse>> resp = tile.getAllStoredItems(craftableOnly);
        if (!resp.isSucceed()) return error(resp.getMessage());

        List<TileAEMonitor.ItemStackResponse> list = resp.getBody();
        Map<String, long[]> current = new LinkedHashMap<String, long[]>();
        for (TileAEMonitor.ItemStackResponse it : list) {
            // 流体滴要带 NBT 才算得出 key（多条流体共用 ae2fc:fluid_drop:0）
            current.put(StreamState.key(it.getItem_name(), it.getMeta(), it.getNbt()),
                    new long[]{it.getCount(), it.isCraftable() ? 1 : 0});
        }

        Map<String, Object> f = new LinkedHashMap<String, Object>();
        if (!st.fullSent) {
            f.put("t", "f");
            f.put("sid", st.sid);
            f.put("seq", st.nextSeq());
            List<Object[]> d = new ArrayList<Object[]>(list.size());
            Map<String, String> nbt = new LinkedHashMap<String, String>();
            int i = 0;
            for (TileAEMonitor.ItemStackResponse it : list) {
                d.add(new Object[]{it.getItem_name(), it.getMeta(), it.getCount(), it.isCraftable()});
                if (it.getNbt() != null) nbt.put(String.valueOf(i), it.getNbt());
                i++;
            }
            f.put("d", d);
            if (!nbt.isEmpty()) f.put("n", nbt);
            st.fullSent = true;
        } else {
            List<Object[]> changed = new ArrayList<Object[]>();
            List<Object[]> added = new ArrayList<Object[]>();
            for (TileAEMonitor.ItemStackResponse it : list) {
                String k = StreamState.key(it.getItem_name(), it.getMeta(), it.getNbt());
                long[] prev = st.items.get(k);
                long craft = it.isCraftable() ? 1 : 0;
                // 流体滴 / 源质：同一个 name+meta 下有很多种，行里必须带 NBT
                boolean needsNbt = StreamState.needsNbt(it.getItem_name());
                if (prev == null) {
                    added.add(new Object[]{it.getItem_name(), it.getMeta(), it.getCount(), it.isCraftable(), it.getNbt()});
                } else if (prev[0] != it.getCount() || prev[1] != craft) {
                    changed.add(needsNbt
                            ? new Object[]{it.getItem_name(), it.getMeta(), it.getCount(), it.isCraftable(), it.getNbt()}
                            : new Object[]{it.getItem_name(), it.getMeta(), it.getCount(), it.isCraftable()});
                }
            }
            List<Object> removed = new ArrayList<Object>();
            for (String k : st.items.keySet()) {
                if (!current.containsKey(k)) {
                    // 直接发 key 字符串：流体滴的 key 带 NBT，拆不出 name/meta
                    removed.add(k);
                }
            }
            if (changed.isEmpty() && added.isEmpty() && removed.isEmpty()) {
                f.put("t", "k");
                f.put("seq", st.nextSeq());
            } else {
                f.put("t", "d");
                f.put("sid", st.sid);
                f.put("seq", st.nextSeq());
                if (!changed.isEmpty()) f.put("c", changed);
                if (!added.isEmpty()) f.put("a", added);
                if (!removed.isEmpty()) f.put("x", removed);
            }
        }
        st.items = current;
        st.touch();
        return f;
    }

    // ------------------------------------------------------------- cpus

    public static Map<String, Object> cpus(TileAEMonitor tile, StreamState st) {
        TileAEMonitor.Response<List<TileAEMonitor.CraftingCPUInfo>> resp;
        try {
            resp = tile.getCraftingCPUInfo();
        } catch (Exception e) {
            return error("Internal error");
        }
        if (!resp.isSucceed()) return error(resp.getMessage());

        List<TileAEMonitor.CraftingCPUInfo> list = resp.getBody();
        Map<Integer, String> current = new LinkedHashMap<Integer, String>();
        for (TileAEMonitor.CraftingCPUInfo c : list) {
            current.put(c.getIdx(), cpuSignature(c));
        }

        Map<String, Object> f = new LinkedHashMap<String, Object>();
        if (!st.fullSent) {
            f.put("t", "f");
            f.put("sid", st.sid);
            f.put("seq", st.nextSeq());
            List<Object[]> d = new ArrayList<Object[]>(list.size());
            Map<String, String> nbt = new LinkedHashMap<String, String>();
            int i = 0;
            for (TileAEMonitor.CraftingCPUInfo c : list) {
                d.add(new Object[]{c.getIdx(), c.getCpuName(), c.getStorage(), c.getParallelism(),
                        c.getItem(), c.getMeta(), c.getRemainingCount()});
                if (c.getNbt() != null) nbt.put(String.valueOf(i), c.getNbt());
                i++;
            }
            f.put("d", d);
            if (!nbt.isEmpty()) f.put("n", nbt);
            st.fullSent = true;
        } else {
            List<Object[]> changed = new ArrayList<Object[]>();
            for (TileAEMonitor.CraftingCPUInfo c : list) {
                if (!cpuSignature(c).equals(st.cpus.get(c.getIdx()))) {
                    changed.add(new Object[]{c.getIdx(), c.getRemainingCount(), c.getItem(), c.getMeta()});
                }
            }
            List<Integer> removed = new ArrayList<Integer>();
            for (Integer idx : st.cpus.keySet()) {
                if (!current.containsKey(idx)) removed.add(idx);
            }
            if (changed.isEmpty() && removed.isEmpty()) {
                f.put("t", "k");
                f.put("seq", st.nextSeq());
            } else {
                f.put("t", "d");
                f.put("sid", st.sid);
                f.put("seq", st.nextSeq());
                if (!changed.isEmpty()) f.put("c", changed);
                if (!removed.isEmpty()) f.put("x", removed);
            }
        }
        st.cpus = current;
        st.touch();
        return f;
    }

    /** 用于判定的轻量签名（不含 cpuName/storage 这些不变字段） */
    private static String cpuSignature(TileAEMonitor.CraftingCPUInfo c) {
        return c.getRemainingCount() + "|" + c.getItem() + "|" + c.getMeta();
    }

    // ------------------------------------------------------------ tasks

    public static Map<String, Object> tasks(TileAEMonitor tile, int cpuid, StreamState st) {
        TileAEMonitor.Response<Collection<TileAEMonitor.CraftTaskItem>> resp;
        try {
            resp = tile.getCraftingStatus(cpuid);
        } catch (Exception e) {
            return error("Internal error");
        }
        if (!resp.isSucceed()) return error(resp.getMessage());

        Collection<TileAEMonitor.CraftTaskItem> list = resp.getBody();
        Map<String, long[]> current = new LinkedHashMap<String, long[]>();
        Map<String, TileAEMonitor.CraftTaskItem> byKey = new LinkedHashMap<String, TileAEMonitor.CraftTaskItem>();
        for (TileAEMonitor.CraftTaskItem t : list) {
            String k = StreamState.key(t.getName(), t.getMeta());
            current.put(k, new long[]{t.getNumberSent(), t.getNumberRemainingToCraft()});
            byKey.put(k, t);
        }

        Map<String, Object> f = new LinkedHashMap<String, Object>();
        if (!st.fullSent) {
            f.put("t", "f");
            f.put("sid", st.sid);
            f.put("seq", st.nextSeq());
            List<Object[]> d = new ArrayList<Object[]>(list.size());
            Map<String, String> nbt = new LinkedHashMap<String, String>();
            int i = 0;
            for (TileAEMonitor.CraftTaskItem t : list) {
                d.add(new Object[]{t.getName(), t.getMeta(), t.getNumberPresent(), t.getNumberSent(),
                        t.getNumberRemainingToCraft(), t.isCrafting(), t.getMissing()});
                if (t.getNbt() != null) nbt.put(String.valueOf(i), t.getNbt());
                i++;
            }
            f.put("d", d);
            if (!nbt.isEmpty()) f.put("n", nbt);
            st.fullSent = true;
        } else {
            List<Object[]> changed = new ArrayList<Object[]>();
            for (Map.Entry<String, long[]> e : current.entrySet()) {
                long[] prev = st.tasks.get(e.getKey());
                long[] now = e.getValue();
                if (prev == null || prev[0] != now[0] || prev[1] != now[1]) {
                    TileAEMonitor.CraftTaskItem t = byKey.get(e.getKey());
                    changed.add(new Object[]{t.getName(), t.getMeta(), now[0], now[1]});
                }
            }
            List<Object[]> removed = new ArrayList<Object[]>();
            for (String k : st.tasks.keySet()) {
                if (!current.containsKey(k)) {
                    int bar = k.lastIndexOf('|');
                    removed.add(new Object[]{k.substring(0, bar), Integer.parseInt(k.substring(bar + 1))});
                }
            }
            if (changed.isEmpty() && removed.isEmpty()) {
                f.put("t", "k");
                f.put("seq", st.nextSeq());
            } else {
                f.put("t", "d");
                f.put("sid", st.sid);
                f.put("seq", st.nextSeq());
                if (!changed.isEmpty()) f.put("c", changed);
                if (!removed.isEmpty()) f.put("x", removed);
            }
        }
        st.tasks = current;
        st.touch();
        return f;
    }
}
