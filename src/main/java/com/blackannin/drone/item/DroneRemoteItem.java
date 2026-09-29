package com.blackannin.drone.item;

import com.blackannin.drone.DistClientHooks;
import com.blackannin.drone.entity.DroneEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * 无人机遥控器：右键打开无人机操控台（遥测面板 + 键鼠连续操控）。
 * 物品模型预留：assets/blackannin_drone/models/item/drone_remote.json + textures/item/drone_remote.png
 */
public class DroneRemoteItem extends Item {
    public DroneRemoteItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand usedHand) {
        ItemStack stack = player.getItemInHand(usedHand);
        if (DroneEntity.findOwned(player) == null) {
            if (!level.isClientSide) {
                player.displayClientMessage(
                        Component.translatable("gui.blackannin_drone.drone_control.not_found"), true);
            }
            return InteractionResultHolder.fail(stack);
        }
        // 仅客户端打开操控台；服务端只确认使用成功
        if (level.isClientSide) {
            DistClientHooks.openDroneConsole();
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }
}
