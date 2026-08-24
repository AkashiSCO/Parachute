package com.create.parachute.registry;

import com.create.parachute.ExampleMod;
import com.create.parachute.parachute.ParachuteMenu;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.inventory.MenuType;
import net.neoforged.neoforge.common.extensions.IMenuTypeExtension;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModMenus {
    public static final DeferredRegister<MenuType<?>> MENU_TYPES = DeferredRegister.create(Registries.MENU, ExampleMod.MOD_ID);

    public static final DeferredHolder<MenuType<?>, MenuType<ParachuteMenu>> PARACHUTE_MENU =
            MENU_TYPES.register("parachute_menu",
                    () -> IMenuTypeExtension.create((windowId, inventory, data) ->
                            new ParachuteMenu(windowId, inventory, data.readBlockPos())));

    private ModMenus() {
    }
}
