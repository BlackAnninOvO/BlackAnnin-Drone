package com.blackannin.drone.registry;

import com.blackannin.drone.BlackAnninsDrone;
import com.blackannin.drone.item.DroneItem;
import com.blackannin.drone.item.DroneRemoteItem;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public class ModItems {
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BlackAnninsDrone.MODID);

    public static final DeferredItem<Item> AERIAL_DRONE = ITEMS.register("aerial_drone", 
            () -> new DroneItem(new Item.Properties().stacksTo(1)));

    public static final DeferredItem<Item> DRONE_REMOTE = ITEMS.register("drone_remote",
            () -> new DroneRemoteItem(new Item.Properties().stacksTo(1).rarity(Rarity.EPIC)));

    public static void register(IEventBus eventBus) {
        ITEMS.register(eventBus);
    }
}
