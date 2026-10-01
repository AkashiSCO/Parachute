package com.lowdragmc.lowdraglib2.gui.factory;

import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 编译期桩：ldlib2 的 {@code BlockUIMenuType}（含两个嵌套类型）。
 *
 * <p>真实类型里 {@code BlockUI} 还有 {@code createUIHolder}/{@code stillValid}/{@code getUIDisplayName}
 * 三个 default 方法，我们既不复写也不调用，所以桩里不需要；{@code BlockUIHolder} 的
 * 公有字段名/类型与真实类一致（我们的 mixin 直接读 {@code holder.player}/{@code holder.pos}）。</p>
 */
public class BlockUIMenuType {

    public interface BlockUI {
        ModularUI createUI(BlockUIHolder holder);
    }

    public static class BlockUIHolder {
        public final BlockUI blockUI;
        public final Player player;
        public final BlockPos pos;
        public final BlockState blockState;

        public BlockUIHolder(BlockUI blockUI, Player player, BlockPos pos, BlockState blockState) {
            this.blockUI = blockUI;
            this.player = player;
            this.pos = pos;
            this.blockState = blockState;
        }
    }
}
