package com.blackannin.drone.item;

import com.blackannin.drone.entity.DroneEntity;
import com.blackannin.drone.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

public class DroneItem extends Item {
    public DroneItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }

        Player player = context.getPlayer();
        if (player == null) {
            return InteractionResult.FAIL;
        }

        if (DroneEntity.findOwned(player) != null) {
            player.displayClientMessage(Component.translatable("chat.blackannin_drone.already_has_drone"), true);
            return InteractionResult.FAIL;
        }

        BlockPos pos = context.getClickedPos().relative(context.getClickedFace());
        DroneEntity drone = ModEntities.AERIAL_DRONE.get().spawn(
                (ServerLevel) level, context.getItemInHand(), player, pos, MobSpawnType.SPAWN_EGG, true, true);

        if (drone != null) {
            drone.setOwner(player);
            if (!player.getAbilities().instabuild) {
                context.getItemInHand().shrink(1);
            }
            return InteractionResult.CONSUME;
        }

        return InteractionResult.FAIL;
    }
}
