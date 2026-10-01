package com.create.parachute.compat.synaxis;

import com.create.parachute.ParachuteMod;
import net.minecraft.world.entity.player.Player;

/** 兼容层的日志工具（带一次性去重，避免每 tick 刷屏）。 */
public final class SynaxisChairLog {
    private static boolean pilotFallbackLogged;

    /** 控制椅的 findPilot 兜底命中我们伞包席位时记一条（只记一次）。 */
    public static void pilotFallback(Player player) {
        if (pilotFallbackLogged) {
            return;
        }
        pilotFallbackLogged = true;
        ParachuteMod.LOGGER.info("[create_parachute] 控制椅 findPilot 兜底命中：伞包席位上的驾驶员 = {}",
                player.getGameProfile().getName());
    }

    private SynaxisChairLog() {
    }
}
