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
        registrar.playToServer(RemoteControlPayload.TYPE, RemoteControlPayload.STREAM_CODEC, ModNetworking::handleRemoteInput);
        registrar.playToServer(RetrieveDronePayload.TYPE, RetrieveDronePayload.STREAM_CODEC, ModNetworking::handleRetrieve);
    }

    private static void handleRemoteInput(RemoteControlPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> {
            Player player = context.player();
            DroneEntity drone = DroneEntity.findOwned(player);
            if (drone == null) {
                return;
            }
            if ((payload.flags() & RemoteControlPayload.FLAG_FOLLOW) != 0) {
                drone.stopManualControl();
                // 恢复跟随同时初始化视角，避免手动模式的朝向遗留到跟随视角
                drone.resetView(player);
                return;
            }
            if ((payload.flags() & RemoteControlPayload.FLAG_RESET_VIEW) != 0) {
                drone.resetView(player);
            }
            if ((payload.flags() & RemoteControlPayload.FLAG_CINEMA) != 0) {
                // 切换自动运镜：运镜报文为专用包，处理后不再应用本包的输入
                drone.toggleCinematic(player);
                return;
            }
            if ((payload.flags() & RemoteControlPayload.FLAG_TAKEOVER) != 0) {
                drone.enterManualControl();
            }
            drone.receiveRemoteInput(payload.forward(), payload.strafe(), payload.up(),
                    payload.yawDelta(), payload.pitchDelta());
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
