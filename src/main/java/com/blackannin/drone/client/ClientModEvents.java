package com.blackannin.drone.client;

import com.blackannin.drone.client.model.DroneModel;
import com.blackannin.drone.client.renderer.DroneRenderer;
import com.blackannin.drone.registry.ModEntities;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.Commands;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;
import net.neoforged.neoforge.client.event.RegisterClientCommandsEvent;

/**
 * 客户端事件注册：实体渲染器、模型层与客户端命令。
 */
public class ClientModEvents {
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.AERIAL_DRONE.get(), DroneRenderer::new);
    }

    @SubscribeEvent
    public static void registerLayerDefinitions(EntityRenderersEvent.RegisterLayerDefinitions event) {
        event.registerLayerDefinition(DroneModel.LAYER_LOCATION, DroneModel::createBodyLayer);
    }

    /** /droneui：不拿遥控器也能打开操控台（与 J 键同效），便于快速上手与排障（游戏总线） */
    public static class GameBus {
        @SubscribeEvent
        public static void registerClientCommands(RegisterClientCommandsEvent event) {
            event.getDispatcher().register(Commands.literal("droneui").executes(context -> {
                Minecraft.getInstance().setScreen(new DroneControlScreen());
                return 1;
            }));
        }
    }
}
