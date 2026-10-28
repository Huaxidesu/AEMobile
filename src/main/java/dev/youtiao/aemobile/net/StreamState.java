package dev.youtiao.aemobile.net;

import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 单条 SSE 连接的增量状态。
 *
 * <p>增量帧的前提是「服务端记得上一帧发了什么」。每条连接（订阅）一份状态，
 * 客户端断线重连时带上 {@code sid} 就能接着续，不用重新拉全量。
 *
 * <p>状态按 {@code sid} 存在静态表里，10 分钟没人用就回收——
 * 客户端拿着过期 sid 回来会拿到全量帧，功能不受影响。
 */
public final class StreamState {

    private static final Map<String, StreamState> REGISTRY = new ConcurrentHashMap<>();
    private static final long TTL_MS = 10 * 60 * 1000L;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    /** 连接标识，12 位 hex */
    public final String sid;
    /** 帧序号，单调递增 */
    public int seq;
    /** 是否已经发过全量帧 */
    public boolean fullSent;

    /** getItems：key = name|meta → [count, craftable] */
    public Map<String, long[]> items = new HashMap<>();
    /** getCraftingCpuInfo：idx → "remaining|item|meta" */
    public Map<Integer, String> cpus = new HashMap<>();
    /** getCraftingDetails：key = name|meta → [sent, remaining] */
    public Map<String, long[]> tasks = new HashMap<>();

    private long touchedAt = System.currentTimeMillis();

    private StreamState(String sid) {
        this.sid = sid;
    }

    /**
     * 取一条连接的状态：带 sid 且未过期 → 续用；否则新建。
     */
    public static StreamState acquire(String sid) {
        evictExpired();
        if (sid != null && !sid.isEmpty()) {
            StreamState old = REGISTRY.remove(sid);
            if (old != null) {
                old.touchedAt = System.currentTimeMillis();
                REGISTRY.put(old.sid, old);
                return old;
            }
        }
        StreamState fresh = new StreamState(newSid());
        REGISTRY.put(fresh.sid, fresh);
        return fresh;
    }

    /** 每帧调用，刷新 TTL */
    public void touch() {
        this.touchedAt = System.currentTimeMillis();
    }

    public int nextSeq() {
        return ++seq;
    }

    private static String newSid() {
        byte[] raw = new byte[6];
        RANDOM.nextBytes(raw);
        char[] out = new char[12];
        for (int i = 0; i < 6; i++) {
            out[i * 2] = HEX[(raw[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[raw[i] & 0xF];
        }
        return new String(out);
    }

    private static void evictExpired() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, StreamState>> it = REGISTRY.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, StreamState> e = it.next();
            if (now - e.getValue().touchedAt > TTL_MS) it.remove();
        }
    }

    /** 流体滴在物品列表里的统一 name（AE2FC 的约定），多条不同流体共用它 */
    public static final String FLUID_DROP = "ae2fc:fluid_drop";
    /** 源质（神秘时代）在物品列表里的统一 name，多条不同要素共用它 */
    public static final String ESSENTIA = "thaumicenergistics:essentia";

    /** 条目 key：物品名 + meta */
    public static String key(String name, int meta) {
        return name + "|" + meta;
    }

    /**
     * 条目 key（带 NBT）。只有流体滴需要带 NBT —— 所有流体共用 {@code ae2fc:fluid_drop:0}
     * 这一个 name+meta，不带 NBT 的话它们会在「当前状态」里互相覆盖，增量帧也会算错。
     * 其它物品（附魔装备、蜜蜂等）故意不带 NBT：否则几千个附魔剑会变成几千行。
     */
    public static String key(String name, int meta, String nbt) {
        if (nbt != null && nbt.length() > 0 && (FLUID_DROP.equals(name) || ESSENTIA.equals(name))) {
            return name + "|" + meta + "|" + nbt;
        }
        return key(name, meta);
    }
}
