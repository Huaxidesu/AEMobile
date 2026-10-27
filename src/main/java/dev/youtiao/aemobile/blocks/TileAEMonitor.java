package dev.youtiao.aemobile.blocks;

import appeng.api.config.Actionable;
import appeng.api.config.CraftingMode;
import appeng.api.networking.crafting.CraftingItemList;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.security.PlayerSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.crafting.v2.CraftingJobV2;
import appeng.me.GridAccessException;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.me.helpers.IGridProxyable;
import appeng.util.item.AEFluidStack;
import appeng.util.item.AEItemStack;
import appeng.util.item.IAEStackList;
import appeng.util.item.ItemList;
import com.google.common.collect.ImmutableList;
import com.mojang.authlib.GameProfile;
import dev.youtiao.aemobile.util.FakePlayerGetCraftFailure;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.util.ForgeDirection;
import org.apache.commons.lang3.tuple.Pair;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.stream.Collectors;

public class TileAEMonitor extends TileEntity {

    private static final GameProfile FAKEUUID = new GameProfile( UUID.randomUUID(), "NIGGER");
    /**
     * token → 该玩家的所有监控器坐标。
     *
     * <p>必须并发安全：TCP 连接线程会读（getNetworks），主线程会写（放/拆方块、区块加载）。
     * 之前是普通 HashMap + HashSet，HTTP 时代靠"读得少"侥幸没炸。
     */
    public static Map<String, Set<PosTuple>> tilesInTheWorld = new java.util.concurrent.ConcurrentHashMap<>();

    private PosTuple posTuple;
    private ForgeDirection directionAETile;

    private UUID ownerUUID;

    /**
     * 访问令牌（26 字符 Base32，大写）。玩家放下第一个监控器时自动生成，
     * 之后同玩家的监控器复用同一个；App 粘贴一次即可访问全部。
     * 见 doc/传输协议与安全.md §2。
     */
    private String token;

    private ArrayDeque<FutureTask<?>> tasks = new ArrayDeque<>();
    String getIcon(ItemStack stack) {
        String name = Item.itemRegistry.getNameForObject(stack.getItem()) + "_" + stack.getItemDamage() +  "_tag_" + TileAEMonitor.writeNBTAsBase64(stack.getTagCompound());
        return String.format("icons/%s.png", name.replace("/","_").replace(":","_"));
    }
    public static class Response<T> {
        private T body;
        private boolean succeed;
        private String message;
        public static <T> Response<T> ofSuccess(T body) {
            Response<T> response = new Response<>();
            response.body = body;
            response.succeed = true;
            return response;
        }
        public static Response ofError(String message) {
            Response response = new Response();
            response.succeed = false;
            response.message = message;
            return response;
        }

        public T getBody() {
            return body;
        }

        public boolean isSucceed() {
            return succeed;
        }

        public String getMessage() {
            return message;
        }
    }

    public class CraftPlan {
        private long bytesUsed;

        public Collection<CraftTaskItem> getPlan() {
            return plan;
        }

        private Collection<CraftTaskItem> plan;

        public long getBytesUsed() {
            return bytesUsed;
        }
    }

    public class ItemStackResponse {
        private String item_name;   //Item.itemRegistry.getNameForObject(stack.getItem())
        private String displayname;
        private int meta;
        private String icon;
        private String nbt;
        private long count;
        private boolean craftable;

        public String getItem_name() {
            return item_name;
        }

        public long getCount() {
            return count;
        }

        public boolean isCraftable() {
            return craftable;
        }

        public int getMeta() {
            return meta;
        }

        public String getNbt() {
            return nbt;
        }

        public String getIcon() {
            return icon;
        }

        public void setIcon(String icon) {
            this.icon = icon;
        }

        public String getDisplayname() {
            return displayname;
        }

        public void setDisplayname(String displayname) {
            this.displayname = displayname;
        }
    }

    public static class CraftRequest {
        private String item_name;   //Item.itemRegistry.getNameForObject(stack.getItem())

        private int meta; //

        private long count;

        private int cpuId;

        private boolean allowMissing;

        private String nbt;

        public CraftRequest(String item_name, int meta, long count, int cpuId, String nbt, boolean allowMissing) {
            this.item_name = item_name;
            this.meta = meta;
            this.count = count;
            this.cpuId = cpuId;
            this.allowMissing = allowMissing;
            this.nbt = nbt;
        }

        public String getItem_name() {
            return item_name;
        }

        public long getCount() {
            return count;
        }

        public int getMeta() {
            return meta;
        }

        public String getNbt() {
            return nbt;
        }

        public boolean isAllowMissing() {
            return allowMissing;
        }
    }

    public static class PosTuple {
        private int dimid;
        private int x;
        private int y;
        private int z;

        public int getX() {
            return x;
        }

        public int getY() {
            return y;
        }

        public int getZ() {
            return z;
        }

        public int getDimid() {
            return dimid;
        }

        public PosTuple(int dimid, int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.dimid = dimid;
        }
        @Override
        public boolean equals(Object o) {
            if(o instanceof PosTuple) {
                if(o == this) {
                    return true;
                }
                return dimid == ((PosTuple) o).dimid && x == ((PosTuple) o).x && y == ((PosTuple) o).y && ((PosTuple) o).z == z;
            }
            return false;
        }
        @Override
        public int hashCode() {
            return (x * 100 + y) * 10 + z;
        }
    }

    public class CraftingCPUInfo {
        private int idx;
        private String cpuName;
        private int meta;
        private String item;
        private String displayname;
        private String icon;
        private String nbt;
        private long storage;
        private long parallelism;
        private long remainingCount;

        public String getCpuName() {
            return cpuName;
        }

        public String getItem() {
            return item;
        }

        public long getStorage() {
            return storage;
        }

        public long getParallelism() {
            return parallelism;
        }

        public long getRemainingCount() {
            return remainingCount;
        }

        public int getMeta() {
            return meta;
        }

        public int getIdx() {
            return idx;
        }

        public void setIdx(int idx) {
            this.idx = idx;
        }

        public String getNbt() {
            return nbt;
        }

        public String getIcon() {
            return icon;
        }

        public void setIcon(String icon) {
            this.icon = icon;
        }

        public String getDisplayname() {
            return displayname;
        }

        public void setDisplayname(String displayname) {
            this.displayname = displayname;
        }
    }

    public class CraftTaskItem {
        private String name;
        private String displayname;
        private int meta;
        private String icon;
        private String nbt;

        private boolean isCrafting;

        private long numberPresent;
        private long numberSent;
        private long numberRemainingToCraft;

        private long missing;

        public String getName() {
            return name;
        }

        public boolean isCrafting() {
            return isCrafting;
        }

        public long getNumberPresent() {
            return numberPresent;
        }

        public long getNumberSent() {
            return numberSent;
        }

        public long getNumberRemainingToCraft() {
            return numberRemainingToCraft;
        }

        public int getMeta() {
            return meta;
        }
        @Override
        public boolean equals(Object o) {
            if(o instanceof CraftTaskItem) {
                return Objects.equals(meta, ((CraftTaskItem) o).meta) && Objects.equals(name, ((CraftTaskItem) o).name);
            } else {
                return false;
            }
        }
        @Override
        public int hashCode() {
            return 100 * meta + name.hashCode();
        }

        public long getMissing() {
            return missing;
        }

        public void setMissing(long missing) {
            this.missing = missing;
        }

        public String getNbt() {
            return nbt;
        }

        public String getIcon() {
            return icon;
        }

        public void setIcon(String icon) {
            this.icon = icon;
        }

        public String getDisplayname() {
            return displayname;
        }

        public void setDisplayname(String displayname) {
            this.displayname = displayname;
        }
    }


    @Override
    public void readFromNBT(NBTTagCompound p_145839_1_) {
        super.readFromNBT(p_145839_1_);
        String uuid = p_145839_1_.getString("ownerUUID");
        if(uuid == null) return;
        ownerUUID = UUID.fromString(uuid);
        // 新存档直接有 token；老存档没有，用 UUID 首段兜底（下次放置方块时会换成随机 token）
        String stored = p_145839_1_.hasKey("token") ? p_145839_1_.getString("token") : null;
        token = (stored == null || stored.isEmpty())
                ? uuid.split("-")[0].toUpperCase(java.util.Locale.ROOT)
                : stored.toUpperCase(java.util.Locale.ROOT);
        dev.youtiao.aemobile.net.AuthTokens.register(token);
        int[] tuple = p_145839_1_.getIntArray("posTuple");
        posTuple = new PosTuple(tuple[0], tuple[1], tuple[2], tuple[3]);
        String direction = p_145839_1_.getString("direction");
        if(direction != null) directionAETile = ForgeDirection.valueOf(p_145839_1_.getString("direction"));
        TileAEMonitor.tilesInTheWorld.computeIfAbsent(token, (e) -> {
            return java.util.concurrent.ConcurrentHashMap.newKeySet();
        });
        TileAEMonitor.tilesInTheWorld.get(token).add(posTuple);
        // 老存档（3.0.0 之前）的监控器没有坐标记录。玩家在线时顺手补进他的持久化 NBT，
        // 下次启动扫 playerdata 就能恢复注册表，不再依赖「区块被加载过」。
        if (ownerUUID != null && worldObj != null && worldObj.playerEntities != null) {
            for (Object o : worldObj.playerEntities) {
                if (!(o instanceof net.minecraft.entity.player.EntityPlayer)) continue;
                net.minecraft.entity.player.EntityPlayer owner = (net.minecraft.entity.player.EntityPlayer) o;
                if (ownerUUID.equals(owner.getUniqueID())) {
                    dev.youtiao.aemobile.net.MonitorRegistry.rememberMonitor(
                            owner, posTuple.dimid, posTuple.x, posTuple.y, posTuple.z);
                    break;
                }
            }
        }
    }

    @Override
    public void writeToNBT(NBTTagCompound p_145841_1_) {
        super.writeToNBT(p_145841_1_);
        if(ownerUUID == null) return;
        p_145841_1_.setString("ownerUUID", ownerUUID.toString());
        if(token != null) p_145841_1_.setString("token", token);
        p_145841_1_.setIntArray("posTuple", new int[]{posTuple.dimid, posTuple.x, posTuple.y, posTuple.z});
        if(directionAETile != null) {
            p_145841_1_.setString("direction", directionAETile.name());
        }
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if(worldObj == null || worldObj.isRemote) return;
        if(token == null || posTuple == null) return;
        // 区块卸载也会走 invalidate()，不能当成「方块被拆」——
        // 否则 App 一离开监控器附近，注册表里就没这个监控器了（3.0.0 之前的坑）。
        // 只有方块真的不在了，才从注册表摘掉。
        net.minecraft.block.Block block = worldObj.getBlock(xCoord, yCoord, zCoord);
        if (block instanceof dev.youtiao.aemobile.blocks.BlockAEMonitor) return;
        Set<PosTuple> set = tilesInTheWorld.get(token);
        if (set != null) set.remove(posTuple);
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token == null ? null : token.toUpperCase(java.util.Locale.ROOT);
    }

    public ForgeDirection getDirectionAETile() {
        return directionAETile;
    }

    public void setDirectionAETile(ForgeDirection directionAETile) {
        this.directionAETile = directionAETile;
    }

    public void setOwnerUUID(UUID ownerUUID) {
        this.ownerUUID = ownerUUID;
    }

    public PosTuple getPosTuple() {
        return posTuple;
    }

    public void setPosTuple(PosTuple posTuple) {
        this.posTuple = posTuple;
    }

    public static String writeNBTAsBase64(NBTTagCompound nbt) {
        if(nbt == null) {
            return null;
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try(DataOutputStream dos = new DataOutputStream(bos)) {
            CompressedStreamTools.write(nbt, dos);
            return Base64.getEncoder().encodeToString(bos.toByteArray());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private NBTTagCompound readNBTFromBase64(String s) {
        if(s == null) {
            return null;
        }
        ByteArrayInputStream bis = new ByteArrayInputStream(Base64.getDecoder().decode(s));
        try(DataInputStream dis = new DataInputStream(bis)) {
            return CompressedStreamTools.read(dis);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void updateEntity() {
        while (!tasks.isEmpty())
            tasks.poll().run();
    }


    public Response<List<CraftingCPUInfo>> getCraftingCPUInfo() throws ExecutionException, InterruptedException {
        FutureTask<Response> ft = new FutureTask<>(() -> {
            TileEntity tileEntity = worldObj.getTileEntity(xCoord + directionAETile.offsetX, yCoord + directionAETile.offsetY, zCoord + directionAETile.offsetZ);
            if (tileEntity instanceof IGridProxyable) {
                try {
                    int[] i = {0};
                    List<CraftingCPUInfo> list = ((IGridProxyable) tileEntity).getProxy().getCrafting().getCpus().stream().map(e -> {
                        CraftingCPUInfo craftingCPUInfo = new CraftingCPUInfo();
                        craftingCPUInfo.setIdx(i[0]);
                        craftingCPUInfo.cpuName = e.getName();
                        IAEItemStack finalOutput = e.getFinalOutput();
                        if (finalOutput != null && ((CraftingCPUCluster) e).getRemainingOperations() > 0) {
                            craftingCPUInfo.item = Item.itemRegistry.getNameForObject(finalOutput.getItem());
                            craftingCPUInfo.icon = getIcon(finalOutput.getItemStack());
                            craftingCPUInfo.meta = finalOutput.getItemDamage();
                            craftingCPUInfo.remainingCount = e.getRemainingItemCount();
                            craftingCPUInfo.displayname = finalOutput.getItemStack().getDisplayName();
                            craftingCPUInfo.nbt = writeNBTAsBase64(finalOutput.getItemStack().getTagCompound());
                        }
                        craftingCPUInfo.parallelism = e.getCoProcessors();
                        craftingCPUInfo.storage = e.getAvailableStorage();
                        i[0]++;
                        return craftingCPUInfo;
                    }).collect(Collectors.toList());
                    return Response.ofSuccess(list);
                } catch (GridAccessException e) {
                    return Response.ofError(e.getMessage());
                }
            } else {
                return Response.ofError("No ME interface connected to this block. Cannot attach to AE grid.");
            }
        });
        tasks.add(ft);
        return ft.get();
    }

    public Response<CraftPlan> populatePlan(CraftingJobV2 iCraftingJob) {
        // 必须用 IAEStackList（物品+流体混合列表），不能用 ItemList（物品专用）：
        // CraftingJobV2.populatePlan 会遍历所有已解析任务，把任务里「从网络提取的输入」
        // 塞进这个 sink —— 流体配方的任务（ExtractItemResolver 等）会塞 AEFluidStack，
        // ItemList.add 的参数类型是 IAEItemStack，调用边界上强转直接 ClassCastException
        // （ItemList.java:32，2026-10-28 堆栈定位）。
        IAEStackList plan = new IAEStackList();
        iCraftingJob.populatePlan(plan);
        List<CraftTaskItem> itemList = new ArrayList<>();
        TileEntity tileEntity = worldObj.getTileEntity(xCoord + directionAETile.offsetX, yCoord + directionAETile.offsetY, zCoord + directionAETile.offsetZ);
        IGridProxyable gridProxyable = (IGridProxyable) tileEntity;
        final IStorageGrid sg;
        try {
            sg = gridProxyable.getProxy().getGrid().getCache(IStorageGrid.class);
            final IMEInventory<IAEItemStack> items = sg.getItemInventory();
            // 注意：计划里可能有流体（AEFluidStack），不能直接当 IAEItemStack 用 ——
            // 2026-10-28 之前这里会 ClassCastException，整个计划直接失败（比如带酸洗的配方）。
            for (Object entry : plan) {
                if (!(entry instanceof IAEStack)) continue;
                IAEStack stack = (IAEStack) entry;
                CraftTaskItem craftTaskItem = new CraftTaskItem();

                if (stack instanceof IAEItemStack) {
                    IAEItemStack iaeItemStack = (IAEItemStack) stack;
                    craftTaskItem.name = Item.itemRegistry.getNameForObject(iaeItemStack.getItem());
                    craftTaskItem.meta = iaeItemStack.getItemDamage();
                    craftTaskItem.numberRemainingToCraft = iaeItemStack.getCountRequestable();
                    craftTaskItem.nbt = writeNBTAsBase64(iaeItemStack.getItemStack().getTagCompound());
                    craftTaskItem.icon = getIcon(iaeItemStack.getItemStack());
                    craftTaskItem.displayname = iaeItemStack.getItemStack().getDisplayName();
                    IAEItemStack missing = iaeItemStack.copy();
                    IAEItemStack toExtract = items.extractItems(iaeItemStack, Actionable.SIMULATE, new MachineSource((IActionHost) gridProxyable.getProxy().getMachine()));
                    if (toExtract == null) {
                        toExtract = missing.copy();
                        toExtract.setStackSize(0);
                    }
                    craftTaskItem.numberPresent = toExtract.getStackSize();
                    craftTaskItem.setMissing(missing.getStackSize() - toExtract.getStackSize());
                } else if (stack instanceof AEFluidStack) {
                    // 流体统一按 ae2fc 的「流体滴」表示：name=ae2fc:fluid_drop，NBT 里放 Fluid 名，
                    // 和 getAllStoredItems 里流体滴的格式一致，App 端能照常解析。
                    AEFluidStack fluid = (AEFluidStack) stack;
                    craftTaskItem.name = "ae2fc:fluid_drop";
                    craftTaskItem.meta = 0;
                    craftTaskItem.numberRemainingToCraft = fluid.getStackSize();
                    NBTTagCompound tag = new NBTTagCompound();
                    if (fluid.getFluidStack() != null && fluid.getFluidStack().getFluid() != null) {
                        tag.setString("Fluid", fluid.getFluidStack().getFluid().getName());
                    }
                    craftTaskItem.nbt = writeNBTAsBase64(tag);
                    // 流体不走 item 库存模拟，直接当作「需要补齐」
                    craftTaskItem.numberPresent = 0;
                    craftTaskItem.setMissing(fluid.getStackSize());
                } else {
                    continue;
                }
                itemList.add(craftTaskItem);
            }
            CraftPlan craftPlan = new CraftPlan();
            craftPlan.bytesUsed = iCraftingJob.getByteTotal();
            craftPlan.plan = itemList;
            return Response.ofSuccess(craftPlan);
        } catch (GridAccessException e) {
            throw new RuntimeException(e);
        }
    }
    public CraftingJobV2 generateCraftingPlan(String item, int meta, String nbt, long count) {
        TileEntity tileEntity = worldObj.getTileEntity(xCoord + directionAETile.offsetX, yCoord + directionAETile.offsetY, zCoord + directionAETile.offsetZ);
        IGridProxyable gridProxyable = (IGridProxyable) tileEntity;
        try {
            ItemStack itemStack = new ItemStack((Item) Item.itemRegistry.getObject(item), 1, meta);
            itemStack.setTagCompound(readNBTFromBase64(nbt));
            AEItemStack aeItemStack = AEItemStack.create(itemStack);
            aeItemStack.setStackSize(count);
            return new CraftingJobV2(worldObj, gridProxyable.getProxy().getGrid(),  new MachineSource((IActionHost) gridProxyable.getProxy().getMachine()), aeItemStack, CraftingMode.STANDARD, null);
        } catch (GridAccessException e) {
            throw new RuntimeException(e);
        }
    }

    public Response<Collection<CraftTaskItem>> getCraftingStatus(int cpuIndex) {
        TileEntity tileEntity = worldObj.getTileEntity(xCoord + directionAETile.offsetX, yCoord + directionAETile.offsetY, zCoord + directionAETile.offsetZ);
        if(tileEntity instanceof IGridProxyable) {
            try {
                Map<Pair<String, Pair<NBTTagCompound,Integer>>, CraftTaskItem> items = new HashMap<>();
                int i = 0;
                ICraftingCPU selected = null;
                for (ICraftingCPU cpu : ((IGridProxyable) tileEntity).getProxy().getCrafting().getCpus()) {
                    if(i == cpuIndex) {
                        selected = cpu;
                        break;
                    }
                    i++;
                }
                if(selected == null) {
                    return Response.ofSuccess(ImmutableList.of());
                }
                CraftingCPUCluster cpu = (CraftingCPUCluster) selected;
                if(cpu.getRemainingItemCount() <= 0) {
                    return Response.ofSuccess(ImmutableList.of());
                }
                ItemList pending = new ItemList();
                ItemList active = new ItemList();
                ItemList storage = new ItemList();
                cpu.getListOfItem(pending, CraftingItemList.PENDING);
                cpu.getListOfItem(active, CraftingItemList.ACTIVE);
                cpu.getListOfItem(storage, CraftingItemList.STORAGE);
                for (IAEItemStack iaeItemStack : pending) {
                    CraftTaskItem craftTaskItem = new CraftTaskItem();
                    craftTaskItem.name = Item.itemRegistry.getNameForObject(iaeItemStack.getItem());
                    craftTaskItem.meta = iaeItemStack.getItemDamage();
                    craftTaskItem.nbt = writeNBTAsBase64(iaeItemStack.getItemStack().getTagCompound());
                    craftTaskItem.isCrafting = false;
                    craftTaskItem.numberRemainingToCraft = iaeItemStack.getStackSize();
                    craftTaskItem.icon = getIcon(iaeItemStack.getItemStack());
                    craftTaskItem.displayname = iaeItemStack.getItemStack().getDisplayName();
                    items.put(Pair.of(craftTaskItem.name, Pair.of(iaeItemStack.getItemStack().getTagCompound(), craftTaskItem.meta)), craftTaskItem);
                }
                for (IAEItemStack iaeItemStack : active) {
                    CraftTaskItem craftTaskItem;
                    String n = Item.itemRegistry.getNameForObject(iaeItemStack.getItem());
                    int d = iaeItemStack.getItemDamage();
                    Pair<String, Pair<NBTTagCompound, Integer>> key = Pair.of(n, Pair.of(iaeItemStack.getItemStack().getTagCompound(), iaeItemStack.getItemDamage()));
                    if(items.containsKey(key)) {
                        craftTaskItem = items.get(key);
                    } else {
                        craftTaskItem = new CraftTaskItem();
                        craftTaskItem.name = n;
                        craftTaskItem.meta = d;
                        craftTaskItem.icon = getIcon(iaeItemStack.getItemStack());
                        craftTaskItem.displayname = iaeItemStack.getItemStack().getDisplayName();
                        craftTaskItem.nbt = writeNBTAsBase64(iaeItemStack.getItemStack().getTagCompound());
                        items.put(Pair.of(craftTaskItem.name, Pair.of(iaeItemStack.getItemStack().getTagCompound(), craftTaskItem.meta)), craftTaskItem);
                    }
                    craftTaskItem.isCrafting = true;
                    craftTaskItem.numberSent += iaeItemStack.getStackSize();
                }
                for (IAEItemStack iaeItemStack : storage) {
                    CraftTaskItem craftTaskItem;
                    String n = Item.itemRegistry.getNameForObject(iaeItemStack.getItem());
                    int d = iaeItemStack.getItemDamage();
                    Pair<String, Pair<NBTTagCompound, Integer>> key = Pair.of(n, Pair.of(iaeItemStack.getItemStack().getTagCompound(), iaeItemStack.getItemDamage()));
                    if(items.containsKey(key)) {
                        craftTaskItem = items.get(key);
                    } else {
                        craftTaskItem = new CraftTaskItem();
                        craftTaskItem.name = n;
                        craftTaskItem.meta = d;
                        craftTaskItem.icon = getIcon(iaeItemStack.getItemStack());
                        craftTaskItem.displayname = iaeItemStack.getItemStack().getDisplayName();
                        craftTaskItem.nbt = writeNBTAsBase64(iaeItemStack.getItemStack().getTagCompound());
                        items.put(Pair.of(craftTaskItem.name, Pair.of(iaeItemStack.getItemStack().getTagCompound(), craftTaskItem.meta)), craftTaskItem);
                    }
                    craftTaskItem.numberPresent += iaeItemStack.getStackSize();
                }
                Iterator<Map.Entry<Pair<String, Pair<NBTTagCompound, Integer>>, CraftTaskItem>> iterator = items.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Pair<String,  Pair<NBTTagCompound, Integer>>, CraftTaskItem> next = iterator.next();
                    if(next.getValue().numberPresent == 0 && next.getValue().numberRemainingToCraft == 0 && next.getValue().numberSent == 0) {
                        iterator.remove();
                    }
                }
                return Response.ofSuccess(items.values());
            } catch (GridAccessException e) {
                e.printStackTrace();
                return Response.ofError(e.getMessage());
            }
        } else {
            return Response.ofError("No ME interface connected to this block. Cannot attach to AE grid.");
        }
    }

    public Response<String> submitCraftJob(CraftRequest request) {
        TileEntity tileEntity = worldObj.getTileEntity(xCoord + directionAETile.offsetX, yCoord + directionAETile.offsetY, zCoord + directionAETile.offsetZ);
        if(tileEntity instanceof IGridProxyable) {
            try {
                IGridProxyable gridProxyable = (IGridProxyable) tileEntity;
                ICraftingGrid cg = null;
                cg = gridProxyable.getProxy().getGrid().getCache(ICraftingGrid.class);
                ItemStack itemStack = new ItemStack((Item) Item.itemRegistry.getObject(request.item_name), 1, request.meta);
                itemStack.setTagCompound(readNBTFromBase64(request.getNbt()));
                AEItemStack aeItemStack = AEItemStack.create(itemStack);
                aeItemStack.setStackSize(request.count);
                ICraftingGrid finalCg = cg;
                Future<ICraftingJob> jobFuture;
                if (finalCg instanceof CraftingGridCache) {

                    jobFuture = ((CraftingGridCache) finalCg).beginCraftingJob(
                            worldObj,
                            gridProxyable.getProxy().getGrid(),
                            new MachineSource((IActionHost) gridProxyable.getProxy().getMachine()),
                            aeItemStack,
                            request.allowMissing ? CraftingMode.IGNORE_MISSING : CraftingMode.STANDARD,
                            false, // liteMode=false: 走完整 CraftingJobV2 计算
                            null);
                } else {
                    jobFuture = finalCg.beginCraftingJob(
                            worldObj,
                            gridProxyable.getProxy().getGrid(),
                            new MachineSource((IActionHost) gridProxyable.getProxy().getMachine()),
                            aeItemStack,
                            null);
                }
                int i = 0;
                CraftingCPUCluster selected = null;
                for (ICraftingCPU cpu : ((IGridProxyable) tileEntity).getProxy().getCrafting().getCpus()) {
                    if (i == request.cpuId - 1) {
                        selected = (CraftingCPUCluster) cpu;
                        break;
                    }
                    i++;
                }
                ICraftingJob iCraftingJob = jobFuture.get();
                final CraftingCPUCluster finalSelected = selected;
                FakePlayerGetCraftFailure errorRetriever = new FakePlayerGetCraftFailure((WorldServer) worldObj, FAKEUUID);
                FutureTask<Response> r = new FutureTask<>(() -> {
                    ICraftingLink g = finalCg.submitJob(
                            iCraftingJob,
                            null,
                            finalSelected,
                            true,
                            new PlayerSource(errorRetriever, (IActionHost) gridProxyable.getProxy().getMachine()));
                    if (g != null) {
                        return Response.ofSuccess("Successfully submitted craft job");
                    } else {
                        return Response.ofError(errorRetriever.getErrorMessage());
                    }
                });
                tasks.add(r);
                return r.get();
            } catch (GridAccessException e) {
                return Response.ofError("An exception throwed when accessing AE grid, please try again later.");
            } catch (ExecutionException | InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else {
            return Response.ofError("No ME interface connected to this block. Cannot attach to AE grid.");
        }
    }
    public Response<List<ItemStackResponse>> getAllStoredItems(boolean canCraftOnly) {
        TileEntity tileEntity = worldObj.getTileEntity(xCoord + directionAETile.offsetX, yCoord + directionAETile.offsetY, zCoord + directionAETile.offsetZ);
        if(tileEntity instanceof IGridProxyable) {
            try {
                FutureTask<IMEMonitor<IAEItemStack>> ft = new FutureTask<>(() -> ((IGridProxyable) tileEntity).getProxy().getStorage().getItemInventory());
                tasks.add(ft);
                IMEMonitor<IAEItemStack> itemInventory = ft.get();
                List<ItemStackResponse> list = new ArrayList<>();
                for (IAEItemStack iaeItemStack : itemInventory.getStorageList()) {
                    if(canCraftOnly && !iaeItemStack.isCraftable()) {
                        continue;
                    }
                    ItemStackResponse itemStackResponse = new ItemStackResponse();
                    itemStackResponse.item_name = Item.itemRegistry.getNameForObject(iaeItemStack.getItem());
                    itemStackResponse.meta = iaeItemStack.getItemDamage();
                    itemStackResponse.nbt = writeNBTAsBase64(iaeItemStack.getItemStack().getTagCompound());
                    itemStackResponse.count = iaeItemStack.getStackSize();
                    itemStackResponse.craftable = iaeItemStack.isCraftable();
                    itemStackResponse.icon = getIcon(iaeItemStack.getItemStack());
                    itemStackResponse.displayname = iaeItemStack.getItemStack().getDisplayName();
                    list.add(itemStackResponse);
                }
                return Response.ofSuccess(list);
            }  catch (ExecutionException e) {
                throw new RuntimeException(e);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        } else {
            return Response.ofError("No ME interface connected to this block. Cannot attach to AE grid.");
        }
    }

    public void cancelTask(int cpuId) throws ExecutionException, InterruptedException {
        FutureTask<Void> voidFutureTask = new FutureTask<>(() -> {
            IGridProxyable tileEntity = (IGridProxyable) worldObj.getTileEntity(xCoord + directionAETile.offsetX, yCoord + directionAETile.offsetY, zCoord + directionAETile.offsetZ);
            List<Pair<Integer, ICraftingCPU>> cpulist = new ArrayList<>();
            int i = 0;
            try {
                for (ICraftingCPU cpus : tileEntity.getProxy().getCrafting().getCpus()) {
                    if (i == cpuId) {
                        ((CraftingCPUCluster) cpus).cancel();
                    }
                    i++;
                }
            } catch (GridAccessException e) {
                throw new RuntimeException(e);
            }
            return null;
        });
        tasks.add(voidFutureTask);
        voidFutureTask.get();
    }
}
