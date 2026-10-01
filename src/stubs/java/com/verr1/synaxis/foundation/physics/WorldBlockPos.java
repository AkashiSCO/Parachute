package com.verr1.synaxis.foundation.physics;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** 编译期桩：Synaxis 的 {@code WorldBlockPos}（我们只用到 {@code of(Level, BlockPos)} 和 {@code pos()}）。 */
public class WorldBlockPos {
    public static WorldBlockPos of(Level level, BlockPos pos) {
        return new WorldBlockPos();
    }

    /** 对应的方块坐标。 */
    public BlockPos pos() {
        return BlockPos.ZERO;
    }
}
