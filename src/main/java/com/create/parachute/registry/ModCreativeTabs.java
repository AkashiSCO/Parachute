package com.create.parachute.registry;

import com.create.parachute.ExampleMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ExampleMod.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> PARACHUTE_TAB =
            CREATIVE_MODE_TABS.register("parachute", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.create_parachute.parachute"))
                    .icon(() -> ModBlocks.PARACHUTE_BLOCK_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModBlocks.PARACHUTE_BLOCK_ITEM.get());
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
