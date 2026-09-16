package com.blackannin.drone.registry;

import com.blackannin.drone.BlackAnninsDrone;
import net.minecraft.core.UUIDUtil;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.attachment.AttachmentType;
import net.neoforged.neoforge.registries.DeferredRegister;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

public class ModAttachments {
    public static final DeferredRegister<AttachmentType<?>> ATTACHMENT_TYPES =
            DeferredRegister.create(NeoForgeRegistries.Keys.ATTACHMENT_TYPES, BlackAnninsDrone.MODID);

    public static final Supplier<AttachmentType<Optional<UUID>>> ACTIVE_DRONE = ATTACHMENT_TYPES.register(
            "active_drone",
            () -> AttachmentType.builder(() -> Optional.<UUID>empty())
                    .serialize(UUIDUtil.CODEC.optionalFieldOf("uuid").codec())
                    .copyOnDeath()
                    .build()
    );

    public static void register(IEventBus eventBus) {
        ATTACHMENT_TYPES.register(eventBus);
    }
}
