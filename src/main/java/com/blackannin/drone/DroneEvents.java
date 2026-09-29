package com.blackannin.drone;

import com.blackannin.drone.entity.DroneEntity;
import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

public class DroneEvents {
    @SubscribeEvent
    public static void onEntityInteract(PlayerInteractEvent.EntityInteract event) {
        if (!(event.getTarget() instanceof DroneEntity drone)) {
            return;
        }
        Player player = event.getEntity();
        if (!player.isShiftKeyDown()) {
            return;
        }
        if (drone.tryRetrieve(player)) {
            event.setCanceled(true);
            event.setCancellationResult(InteractionResult.SUCCESS);
        }
    }

    /** /dronespawn：在玩家前方 3 格生成一台已认主的无人机（管理员/快速测试用） */
    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("dronespawn")
                .requires(source -> source.hasPermission(2))
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    DroneEntity drone = DroneEntity.spawnFor(player);
                    if (drone == null) {
                        context.getSource().sendFailure(Component.literal("生成失败：已拥有无人机"));
                        return 0;
                    }
                    context.getSource().sendSuccess(
                            () -> Component.literal("无人机已生成（实体 #" + drone.getId() + "）"), true);
                    return Command.SINGLE_SUCCESS;
                }));
    }
}
