package com.blackannin.drone.client;

import com.blackannin.drone.entity.DroneEntity;
import com.blackannin.drone.network.RetrieveDronePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.EntityHitResult;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

public class ClientTickHandler {
    private static boolean useWasDown;

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        while (ModKeyMappings.OPEN_DRONE_GUI.consumeClick()) {
            mc.setScreen(new DroneControlScreen());
        }

        // 潜行时原版会跳过实体交互，所以在准星对着无人机时额外发包收回
        boolean useDown = mc.options.keyUse.isDown();
        if (mc.screen == null && mc.player != null && mc.player.isShiftKeyDown() && useDown && !useWasDown
                && mc.hitResult instanceof EntityHitResult hit
                && hit.getEntity() instanceof DroneEntity drone
                && mc.player.getUUID().equals(drone.getOwnerUUID())) {
            PacketDistributor.sendToServer(new RetrieveDronePayload(drone.getId()));
        }
        useWasDown = useDown;
    }
}
