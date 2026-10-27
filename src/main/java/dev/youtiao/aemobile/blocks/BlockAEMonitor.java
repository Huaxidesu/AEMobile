package dev.youtiao.aemobile.blocks;

import appeng.me.helpers.IGridProxyable;
import com.mojang.authlib.GameProfile;
import dev.youtiao.aemobile.Tags;
import net.minecraft.block.Block;
import net.minecraft.block.ITileEntityProvider;
import net.minecraft.block.material.Material;
import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.IIcon;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import java.util.HashSet;

public class BlockAEMonitor extends Block implements ITileEntityProvider {
    public BlockAEMonitor(Material p_i45394_1_) {
        super(p_i45394_1_);
    }

    /*
     * 相邻AE网络方块被挖掉时，清除记录
     */
    @Override
    public void onNeighborBlockChange(World world, int x, int y, int z, Block block) {
        TileAEMonitor tileEntity = (TileAEMonitor) world.getTileEntity(x, y, z);
        if(!world.isRemote) {
            tileEntity.setDirectionAETile(null);
            for (ForgeDirection value : ForgeDirection.values()) {
                TileEntity te = world.getTileEntity(x + value.offsetX, y + value.offsetY, z + value.offsetZ);
                if (te instanceof IGridProxyable) {
                    tileEntity.setDirectionAETile(value);
                    break;
                }
            }
        }
    }

    /*
     * 玩家放下方块时，记录玩家uuid
     * 同时检测边上的ae网络方块位置并记录在TE里面
     *
     * 访问令牌（2026-10-27 起）：不再用 UUID 首段当密码——那个值可以由玩家名反查出来，
     * 等于密码公开。改成 128 bit 随机 token，存在玩家的持久化 NBT 里，
     * 同一玩家的所有监控器共用一个，聊天栏显示一次供 App 粘贴。
     */
    @Override
    public void onBlockPlacedBy(World world, int x, int y, int z, EntityLivingBase entityPlayerLike, ItemStack itemStack) {
        TileAEMonitor tileEntity = (TileAEMonitor) world.getTileEntity(x, y, z);
        if(!world.isRemote) {
            tileEntity.setPosTuple(new TileAEMonitor.PosTuple(world.provider.dimensionId, x, y, z));
            if (entityPlayerLike instanceof EntityPlayer) {
                EntityPlayer player = (EntityPlayer) entityPlayerLike;
                GameProfile gameProfile = player.getGameProfile();
                tileEntity.setOwnerUUID(gameProfile.getId());

                String token = tokenOf(player);
                tileEntity.setToken(token);
                dev.youtiao.aemobile.net.AuthTokens.register(token);
                TileAEMonitor.tilesInTheWorld.computeIfAbsent(token, (e) -> new HashSet<>());
                TileAEMonitor.tilesInTheWorld.get(token).add(tileEntity.getPosTuple());
                // 坐标也写进玩家持久化 NBT：服务端重启后不用等区块加载就能恢复注册表
                dev.youtiao.aemobile.net.MonitorRegistry.rememberMonitor(
                        player, world.provider.dimensionId, x, y, z);
                player.addChatMessage(new ChatComponentText("AEMobile 密码: " + token));
            }
            if (tileEntity.getDirectionAETile() == null) {
                for (ForgeDirection value : ForgeDirection.values()) {
                    TileEntity te = world.getTileEntity(x + value.offsetX, y + value.offsetY, z + value.offsetZ);
                    if (te instanceof IGridProxyable) {
                        tileEntity.setDirectionAETile(value);
                        break;
                    }
                }
            }
        }
    }

    /**
     * 取玩家的访问令牌，没有就生成一个。
     * 存在玩家的持久化 NBT 里（{@code PlayerPersisted}），跨存档区块加载、跨维度都有效，
     * 不依赖监控器方块是否已加载。
     */
    private static String tokenOf(EntityPlayer player) {
        net.minecraft.nbt.NBTTagCompound data = player.getEntityData();
        net.minecraft.nbt.NBTTagCompound persisted = data.getCompoundTag(EntityPlayer.PERSISTED_NBT_TAG);
        String token = persisted.getString("aemobile_token");
        if (token == null || token.isEmpty()) {
            token = dev.youtiao.aemobile.net.AuthTokens.generate();
            persisted.setString("aemobile_token", token);
            data.setTag(EntityPlayer.PERSISTED_NBT_TAG, persisted);
        }
        return token.toUpperCase(java.util.Locale.ROOT);
    }

    @Override
    public TileEntity createNewTileEntity(World world, int i) {
        return new TileAEMonitor();
    }

    @Override
    public void registerBlockIcons(IIconRegister p_149651_1_) {
        blockIcon = p_149651_1_.registerIcon(Tags.MODID+":"+getUnlocalizedName());
    }

    @Override
    public IIcon getIcon(IBlockAccess p_149673_1_, int p_149673_2_, int p_149673_3_, int p_149673_4_, int p_149673_5_) {
        return blockIcon;
    }
}
