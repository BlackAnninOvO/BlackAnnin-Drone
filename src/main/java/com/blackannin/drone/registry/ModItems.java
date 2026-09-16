package com.blackannin.drone.registry;

import com.blackannin.drone.BlackAnninsDrone;
import com.blackannin.drone.item.DroneItem;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BlackAnninsDrone.MODID);

    public static final DeferredItem<Item> AERIAL_DRONE = ITEMS.register("aerial_drone", 
            () -> new DroneItem(new Item.Properties().stacksTo(1)));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
