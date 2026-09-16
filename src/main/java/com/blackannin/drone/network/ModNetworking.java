package com.blackannin.drone.network;

import com.blackannin.drone.entity.DroneEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

public class ModNetworking {

    @SubscribeEvent
    public static void register(final RegisterPayloadHandlersEvent event) {
        final PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(DroneControlPayload.TYPE, DroneControlPayload.STREAM_CODEC, ModNetworking::handleControl);
        registrar.playToServer(RetrieveDronePayload.TYPE, RetrieveDronePayload.STREAM_CODEC, ModNetworking::handleRetrieve);
    }

    private static void handleControl(DroneControlPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            DroneEntity drone = DroneEntity.findOwned(player);
            if (drone == null) {
                return;
            }
            if (payload.action() == DroneControlPayload.FOLLOW) {
                drone.stopManualControl();
            } else {
                drone.nudgeFromOwner(player, payload.action());
            }
        });
    }

    private static void handleRetrieve(RetrieveDronePayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            Entity entity = player.level().getEntity(payload.entityId());
            if (entity instanceof DroneEntity drone) {
                drone.tryRetrieve(player);
            }
        });
    }
}
