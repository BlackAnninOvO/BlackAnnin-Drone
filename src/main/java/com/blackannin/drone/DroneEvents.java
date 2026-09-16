package com.blackannin.drone;

import com.blackannin.drone.entity.DroneEntity;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
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
}
