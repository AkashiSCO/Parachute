package com.create.parachute.compat.synaxis;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.fml.ModList;

/**
 * Synaxis 兼容层的入口：判断装没装 Synaxis，并按需造一个「幽灵控制椅」。
 *
 * <p>本模组对 Synaxis 没有编译期依赖（和伞包那边「不依赖 Create，只认 c:tools/wrench 标签」
 * 的思路一致），所有交互都走反射；Synaxis 缺席时这里只会返回 {@code null}，
 * 坐垫模式伞包的行为和以前完全一样。</p>
 */
public final class SynaxisCompat {
    public static final String MOD_ID = "synaxis";

    private static Boolean loaded;

    /** 是否装了 Synaxis（结果缓存）。 */
    public static boolean isLoaded() {
        if (loaded == null) {
            boolean value;
            try {
                value = ModList.get() != null && ModList.get().isLoaded(MOD_ID);
            } catch (Throwable t) {
                value = false;
            }
            loaded = value;
        }
        return loaded;
    }

    /**
     * 造一个绑定到指定坐标的幽灵控制椅；失败（没装 Synaxis / 反射失败）返回 {@code null}。
     */
    public static SynaxisChairSupport createChair(Level level, BlockPos pos, BlockState state) {
        if (!isLoaded()) {
            return null;
        }
        return SynaxisChairBridge.create(level, pos, state);
    }

    private SynaxisCompat() {
    }
}
