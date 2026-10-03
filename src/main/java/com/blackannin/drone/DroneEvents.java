package com.blackannin.drone;

import com.blackannin.drone.Config;
import com.blackannin.drone.entity.DroneEntity;
import com.mojang.brigadier.Command;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;
import java.util.Set;

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

    /**
     * 玩家跨维度完成时立即传送遗留的无人机。
     *
     * <p>玩家 changeDimension 的过程中，旧维度的 {@code getOwner()} 会暂时返回 null，
     * tick 轮询存在时序窗口（个别时候无人机没跟上，被留在旧维度）。此事件在玩家
     * 于新维度落地后触发，此时传送目标区块必然已加载，传送可靠。</p>
     */
    @SubscribeEvent
    public static void onPlayerChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        DroneEntity drone = DroneEntity.findOwned(player);
        if (drone != null) {
            // 已在玩家所在维度（或 tick 轮询刚传送过来）：仅当维度不一致时补一次传送
            if (drone.level().dimension() != player.level().dimension()) {
                drone.teleportTo(player.serverLevel(),
                        player.getX(), player.getY() + Config.DRONE_FOLLOW_HEIGHT.get(), player.getZ(),
                        Set.of(), drone.getYRot(), drone.getXRot());
            }
            return;
        }
        // 新维度找不到：无人机可能被时序窗口遗留在了旧维度（登录后首次跨维度的典型场景，
        // findOwned 只搜索玩家当前维度）。到旧维度找回并传送到玩家身边。
        var fromLevel = player.serverLevel().getServer().getLevel(event.getFrom());
        if (fromLevel == null) {
            return;
        }
        AABB searchBox = player.getBoundingBox().inflate(64);
        List<DroneEntity> orphans = fromLevel.getEntitiesOfClass(DroneEntity.class, searchBox,
                d -> player.getUUID().equals(d.getOwnerUUID()));
        for (DroneEntity orphan : orphans) {
            orphan.teleportTo(player.serverLevel(),
                    player.getX(), player.getY() + Config.DRONE_FOLLOW_HEIGHT.get(), player.getZ(),
                    Set.of(), orphan.getYRot(), orphan.getXRot());
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
