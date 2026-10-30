package dev.youtiao.aemobile.net;

import dev.youtiao.aemobile.AEMobile;
import dev.youtiao.aemobile.blocks.TileAEMonitor;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监控器注册表 / token 表的持久化。
 *
 * <p><b>为什么需要这个类</b>：3.0.0 之前，token 只在 {@code TileAEMonitor.readFromNBT} 里登记，
 * 而那个方法只有在**监控器所在区块被加载**时才会跑。结果：服务器重启后，只要没人走到监控器附近，
 * 服务端手上的 token 表就是空的 —— App 拿着正确密码也会被回 {@code AUTH_FAILED}，
 * 界面上显示「密码错误」，非常误导（2026-10-27 在真服务器上实际踩到）。
 *
 * <p>解决办法：把「token」和「监控器坐标」都写进<b>玩家的持久化 NBT</b>
 * （{@code PlayerPersisted}，跟区块加载无关），启动时扫一遍 {@code world/playerdata/*.dat}
 * 把两张表重建出来。区块本身仍然按需加载（见 {@code AeService.tile}）。
 */
public final class MonitorRegistry {

    /** 玩家数据里放 Forge 持久化数据的那一层（Entity.getEntityData() 的来源） */
    public static final String FORGE_DATA_TAG = "ForgeData";
    /** 玩家持久化 NBT 里的 token 键（2.1.0 起就在用） */
    public static final String TOKEN_KEY = "aemobile_token";
    /** 玩家持久化 NBT 里的监控器坐标列表键，每项格式 "dimid,x,y,z" */
    public static final String MONITORS_KEY = "aemobile_monitors";

    /**
     * owner UUID → 当前 token。
     *
     * <p>重置绑定码后，未加载区块里的 tile 仍会从自己的 NBT 读旧 token；靠这张表在
     * {@code TileAEMonitor.readFromNBT} 里覆盖，旧码才不会「复活」。
     */
    private static final Map<UUID, String> BY_OWNER = new ConcurrentHashMap<UUID, String>();

    private MonitorRegistry() {
    }

    public static String posKey(int dimid, int x, int y, int z) {
        return dimid + "," + x + "," + y + "," + z;
    }

    // ------------------------------------------------------------ 写入

    /**
     * 把监控器坐标记进玩家持久化 NBT（去重）。放置方块、以及 tile 加载时顺手迁移老存档都会调。
     */
    public static void rememberMonitor(EntityPlayer player, int dimid, int x, int y, int z) {
        if (player == null) return;
        NBTTagCompound data = player.getEntityData();
        NBTTagCompound persisted = data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        NBTTagList list = persisted.getTagList(MONITORS_KEY, 8);
        String key = posKey(dimid, x, y, z);
        for (int i = 0; i < list.tagCount(); i++) {
            if (key.equals(list.getStringTagAt(i))) return;
        }
        list.appendTag(new NBTTagString(key));
        persisted.setTag(MONITORS_KEY, list);
        data.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
    }

    // ------------------------------------------------------------ 重置绑定码

    /** 重置结果：旧码、新码、搬过去的监控器数量 */
    public static final class Reset {
        public final String oldToken;
        public final String newToken;
        public final int monitors;

        Reset(String oldToken, String newToken, int monitors) {
            this.oldToken = oldToken;
            this.newToken = newToken;
            this.monitors = monitors;
        }
    }

    /** 玩家当前的 token（未登记过返回 null） */
    public static String currentToken(UUID owner) {
        return owner == null ? null : BY_OWNER.get(owner);
    }

    /** 停服/切世界时清空，避免不同存档的 owner 表串档 */
    public static void clear() {
        BY_OWNER.clear();
    }

    /**
     * 重置玩家的绑定码。
     *
     * <p>只改内存与玩家 NBT，不加载区块：已加载的监控器顺手改掉，没加载的等以后
     * {@code readFromNBT} 时用 {@link #currentToken} 解析成新码。调用方负责踢掉旧连接
     * （{@code TcpServer.closeByToken(reset.oldToken)}）。
     *
     * <p>必须在主线程调。
     */
    public static Reset reset(EntityPlayer player) {
        if (player == null) return null;
        NBTTagCompound data = player.getEntityData();
        NBTTagCompound persisted = data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        String raw = persisted.getString(TOKEN_KEY);
        String old = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);

        Set<TileAEMonitor.PosTuple> coords = ConcurrentHashMap.newKeySet();
        if (!old.isEmpty()) {
            Set<TileAEMonitor.PosTuple> known = TileAEMonitor.tilesInTheWorld.remove(old);
            if (known != null) coords.addAll(known);
        }
        // 老存档的兜底码（UUID 首段）也要一起废掉，否则它仍然能访问。
        UUID owner = player.getUniqueID();
        String fallback = owner == null ? "" : owner.toString().split("-")[0].toUpperCase(Locale.ROOT);
        if (!fallback.isEmpty() && !fallback.equals(old)) {
            AuthTokens.unregister(fallback);
            Set<TileAEMonitor.PosTuple> stale = TileAEMonitor.tilesInTheWorld.remove(fallback);
            if (stale != null) coords.addAll(stale);
        }

        // 玩家 NBT 里记录的坐标才是权威：内存表可能因为刚重启还没恢复全
        NBTTagList list = persisted.getTagList(MONITORS_KEY, 8);
        for (int i = 0; i < list.tagCount(); i++) {
            TileAEMonitor.PosTuple p = parsePos(list.getStringTagAt(i));
            if (p != null) coords.add(p);
        }

        // 旧 tile 的 NBT / 崩溃残留可能给同一批坐标登记过别的 token，一并注销，
        // 否则那些 token 仍能鉴权（TCP 连接自检也会兜底，但这里先把表清干净）。
        List<String> staleTokens = new ArrayList<String>();
        for (Map.Entry<String, Set<TileAEMonitor.PosTuple>> e : TileAEMonitor.tilesInTheWorld.entrySet()) {
            if (e.getKey().equals(old) || e.getKey().equals(fallback)) continue;
            if (e.getValue().removeAll(coords)) staleTokens.add(e.getKey());
        }
        for (String stale : staleTokens) {
            AuthTokens.unregister(stale);
            TileAEMonitor.tilesInTheWorld.remove(stale);
        }

        String next = AuthTokens.generate();
        persisted.setString(TOKEN_KEY, next);
        data.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);

        AuthTokens.register(next);
        if (!old.isEmpty()) AuthTokens.unregister(old);
        TileAEMonitor.tilesInTheWorld.put(next, coords);
        if (owner != null) BY_OWNER.put(owner, next);
        restamp(coords, next);
        return new Reset(old, next, coords.size());
    }

    /** 已加载的 tile 立刻换码；没加载的留给 readFromNBT */
    private static void restamp(Set<TileAEMonitor.PosTuple> coords, String token) {
        for (TileAEMonitor.PosTuple p : coords) {
            try {
                World world = DimensionManager.getWorld(p.getDimid());
                if (world == null) continue;
                if (!world.getChunkProvider().chunkExists(p.getX() >> 4, p.getZ() >> 4)) continue;
                TileEntity te = world.getTileEntity(p.getX(), p.getY(), p.getZ());
                if (te instanceof TileAEMonitor) {
                    TileAEMonitor tile = (TileAEMonitor) te;
                    tile.setToken(token);
                    tile.markDirty();
                }
            } catch (Throwable ignored) {
                // 单个坐标异常不影响其余监控器
            }
        }
    }

    /** 从 playerdata/<uuid>.dat 的文件名取玩家 UUID */
    private static UUID ownerOf(File file) {
        try {
            String name = file.getName();
            return UUID.fromString(name.substring(0, name.length() - 4));
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------ 启动恢复

    /**
     * 扫 playerdata，重建 token 表 + 监控器坐标表。**不加载区块**（区块留给第一次查询时按需加载）。
     * 必须在主线程调（{@code CommonProxy.serverStarted}）。
     */
    public static void loadFromPlayerData() {
        try {
            MinecraftServer server = MinecraftServer.getServer();
            if (server == null || server.getEntityWorld() == null) return;
            File worldDir = server.getEntityWorld().getSaveHandler().getWorldDirectory();
            File dir = new File(worldDir, "playerdata");
            File[] files = dir.listFiles();
            if (files == null) {
                AEMobile.warn("[AEMobile] 找不到 playerdata 目录，token 表只能等区块加载后再建");
                return;
            }

            int tokens = 0;
            int monitors = 0;
            for (File file : files) {
                if (!file.getName().endsWith(".dat")) continue;
                NBTTagCompound root;
                try {
                    FileInputStream in = new FileInputStream(file);
                    try {
                        root = CompressedStreamTools.readCompressed(in);
                    } finally {
                        in.close();
                    }
                } catch (Exception e) {
                    continue; // 单个玩家数据坏掉不影响其他人
                }
                if (root == null) continue;
                // 注意层级：持久化 NBT 不在根上，而是 root.ForgeData.PlayerPersisted。
                // （Entity.getEntityData() 读的是 "ForgeData"，PERSISTED_NBT_TAG 是它下面的键。
                //   2026-10-28 在真存档上踩过：直接读根永远拿不到。）
                NBTTagCompound forgeData = root.getCompoundTag(FORGE_DATA_TAG);
                if (!forgeData.hasKey(EntityPlayer.PERSISTED_NBT_TAG)) continue;

                NBTTagCompound persisted = forgeData.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
                String token = persisted.getString(TOKEN_KEY);
                if (token == null || token.isEmpty()) continue;
                token = token.toUpperCase(Locale.ROOT);
                AuthTokens.register(token);
                UUID owner = ownerOf(file);
                if (owner != null) BY_OWNER.put(owner, token);
                tokens++;

                NBTTagList list = persisted.getTagList(MONITORS_KEY, 8);
                for (int i = 0; i < list.tagCount(); i++) {
                    if (addMonitor(token, list.getStringTagAt(i))) monitors++;
                }
            }
            AEMobile.info("[AEMobile] playerdata 恢复完成：" + tokens + " 个 token / " + monitors + " 个监控器坐标");
        } catch (Throwable t) {
            AEMobile.error("[AEMobile] 从 playerdata 恢复 token 失败：" + t);
        }
    }

    /** 解析 "dimid,x,y,z" 并登记到 tilesInTheWorld；格式不对返回 false */
    private static boolean addMonitor(String token, String pos) {
        TileAEMonitor.PosTuple p = parsePos(pos);
        if (p == null) return false;
        TileAEMonitor.tilesInTheWorld
                .computeIfAbsent(token, k -> java.util.concurrent.ConcurrentHashMap.newKeySet())
                .add(p);
        return true;
    }

    /** "dimid,x,y,z" → PosTuple；格式不对返回 null */
    private static TileAEMonitor.PosTuple parsePos(String pos) {
        if (pos == null) return null;
        String[] parts = pos.split(",");
        if (parts.length != 4) return null;
        int[] v = new int[4];
        try {
            for (int i = 0; i < 4; i++) v[i] = Integer.parseInt(parts[i].trim());
        } catch (NumberFormatException e) {
            return null;
        }
        return new TileAEMonitor.PosTuple(v[0], v[1], v[2], v[3]);
    }
}
