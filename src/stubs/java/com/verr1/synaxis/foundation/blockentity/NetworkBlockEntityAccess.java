package com.verr1.synaxis.foundation.blockentity;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 编译期桩：Synaxis 的 {@code NetworkBlockEntityAccess}。
 *
 * <p>只列出我们真正实现的抽象方法（其余在原接口里都是 default 方法），
 * 外加 UI mixin 会调用的 {@code createModularUI}。签名与 Synaxis 1.5.0 一致，
 * 因此编译出来的 mixin 字节码在运行时能对上真接口。</p>
 */
public interface NetworkBlockEntityAccess {
    NetworkBlockEntitySupport networkSupport();

    BlockPos getBlockPos();

    Level getLevel();

    BlockState getBlockState();

    boolean isRemoved();

    void setChanged();

    boolean canPlayerUse(Player player);

    default ModularUI createModularUI(Player player) {
        return null;
    }
}
