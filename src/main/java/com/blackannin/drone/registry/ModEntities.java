package com.blackannin.drone.registry;

import com.blackannin.drone.BlackAnninsDrone;
import com.blackannin.drone.entity.DroneEntity;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.DeferredHolder;

public class ModEntities {
    public static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, BlackAnninsDrone.MODID);

    public static final DeferredHolder<EntityType<?>, EntityType<DroneEntity>> AERIAL_DRONE = ENTITIES.register("aerial_drone",
            () -> EntityType.Builder.of(DroneEntity::new, MobCategory.MISC)
                    .sized(0.6F, 0.35F)
                    .eyeHeight(0.2F)
                    .clientTrackingRange(16)
                    .updateInterval(1)
                    .build("aerial_drone"));

    public static void register(IEventBus eventBus) {
        ENTITIES.register(eventBus);
    }
}
