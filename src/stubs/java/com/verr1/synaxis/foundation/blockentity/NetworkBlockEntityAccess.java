package com.verr1.synaxis.foundation.blockentity;

import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.verr1.synaxis.foundation.network.BlockCommandPayload;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
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

    /**
     * Synaxis 的方块走的是这个重载（ldlib 会把 {@code BlockUIHolder} 交进来）——
     * 它比 {@code createModularUI(Player)} 多登记了 UI 会话/容器 id，
     * 服务端 {@code handleCommand} 靠那个校验"玩家确实开着这个界面"，
     * 所以我们的 mixin 必须调这个，否则界面里改的值会被静默丢掉。
     */
    default ModularUI createModularUI(BlockUIMenuType.BlockUIHolder holder) {
        return null;
    }

    default ModularUI createModularUI(Player player) {
        return null;
    }

    /**
     * 命令入口（真接口里是 default：{@code this.networkSupport().handleCommand(...)}）。
     *
     * <p>伞包 BE 会<b>覆盖</b>它：Synaxis 原版实现里那道 {@code canPlayerUse} 校验用的是
     * 幽灵椅子自己，而幽灵椅子不在 level 的 BE 表里（表里坐的是伞包），校验必然失败、
     * 命令会被丢掉。覆盖后校验交给伞包，命令执行仍走幽灵椅子的 {@code CommandRegistry}。</p>
     */
    default void handleCommand(BlockCommandPayload payload, ServerPlayer player) {
    }
}
