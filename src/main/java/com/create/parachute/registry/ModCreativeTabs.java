package com.create.parachute.registry;

import com.create.parachute.ParachuteMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class ModCreativeTabs {
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS =
            DeferredRegister.create(Registries.CREATIVE_MODE_TAB, ParachuteMod.MOD_ID);

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> PARACHUTE_TAB =
            CREATIVE_MODE_TABS.register("parachute", () -> CreativeModeTab.builder()
                    .title(Component.translatable("itemGroup.create_parachute.parachute"))
                    .icon(() -> ModBlocks.PARACHUTE_BLOCK_ITEM.get().getDefaultInstance())
                    .displayItems((parameters, output) -> {
                        output.accept(ModBlocks.PARACHUTE_BLOCK_ITEM.get());
                        output.accept(ModBlocks.AIR_BLOCK_ITEM.get());
                        // 原版烈焰棒：坐垫模式伞包在装了 Synaxis 时，手持它右键才能打开控制椅配置界面，
                        // 放在本模组的创造栏里方便直接用
                        output.accept(net.minecraft.world.item.Items.BLAZE_ROD);
                    })
                    .build());

    private ModCreativeTabs() {
    }
}
