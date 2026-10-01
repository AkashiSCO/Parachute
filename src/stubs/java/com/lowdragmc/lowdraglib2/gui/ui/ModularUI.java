package com.lowdragmc.lowdraglib2.gui.ui;

import net.minecraft.world.entity.player.Player;

/** 编译期桩：ldlib2 的 {@code ModularUI}（返回类型 + {@code of(UI, Player)} 工厂）。 */
public class ModularUI {
    public static ModularUI of(UI ui, Player player) {
        return new ModularUI();
    }
}
