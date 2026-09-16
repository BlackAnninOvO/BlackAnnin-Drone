package com.blackannin.drone;

import org.slf4j.Logger;
import com.mojang.logging.LogUtils;
import com.blackannin.drone.entity.DroneEntity;
import com.blackannin.drone.registry.ModEntities;
import com.blackannin.drone.registry.ModItems;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import com.blackannin.drone.network.ModNetworking;
import com.blackannin.drone.client.ClientModEvents;
import com.blackannin.drone.client.ModKeyMappings;
import com.blackannin.drone.client.ClientTickHandler;
import com.blackannin.drone.client.DroneCameraRenderer;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

// The value here should match an entry in the META-INF/neoforge.mods.toml file
@Mod(BlackAnninsDrone.MODID)
public class BlackAnninsDrone {
    // Define mod id in a common place for everything to reference
    public static final String MODID = "blackannin_drone";
    // Directly reference a slf4j logger
    public static final Logger LOGGER = LogUtils.getLogger();
    
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> DRONE_TAB = CREATIVE_MODE_TABS.register("drone_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.blackannin_drone"))
            .withTabsBefore(CreativeModeTabs.COMBAT)
            .icon(() -> ModItems.AERIAL_DRONE.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(ModItems.AERIAL_DRONE.get());
            }).build());

    public BlackAnninsDrone(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        modEventBus.addListener(this::registerAttributes);

        CREATIVE_MODE_TABS.register(modEventBus);
        com.blackannin.drone.registry.ModAttachments.register(modEventBus);

        ModItems.register(modEventBus);
        ModEntities.register(modEventBus);
        
        // 显式注册事件
        modEventBus.register(ModNetworking.class);
        
        if (FMLEnvironment.dist == Dist.CLIENT) {
            modEventBus.addListener(this::clientSetup);
            modEventBus.register(ClientModEvents.class);
            modEventBus.register(ModKeyMappings.class);
            // ClientTickHandler 监听游戏总线；DroneCameraRenderer 在每帧上屏后渲染无人机视角并推流
            NeoForge.EVENT_BUS.register(ClientTickHandler.class);
            NeoForge.EVENT_BUS.register(DroneCameraRenderer.class);
            
            modContainer.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
        }

        NeoForge.EVENT_BUS.register(this);
        NeoForge.EVENT_BUS.register(DroneEvents.class);
        modContainer.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        modContainer.registerConfig(ModConfig.Type.CLIENT, ClientConfig.SPEC);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("BlackAnnin's Drone Common Setup");
    }

    private void clientSetup(FMLClientSetupEvent event) {
        LOGGER.info("BlackAnnin's Drone Client Setup");
    }

    private void registerAttributes(EntityAttributeCreationEvent event) {
        event.put(ModEntities.AERIAL_DRONE.get(), DroneEntity.createAttributes().build());
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("BlackAnnin's Drone Server Starting");
    }
}
