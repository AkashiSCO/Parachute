package com.create.parachute.mixin;

import com.create.parachute.parachute.ParachuteSeatEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 坐在「坐垫模式伞包」上的玩家<b>不许自己走</b>。
 *
 * <h3>为什么要这么干</h3>
 * <p>伞包坐垫的乘客位置是每 tick 由 {@link ParachuteSeatEntity#positionRider} 钉住的
 * （枢轴点/偏移一改，人立刻跟着走）。但玩家自己的移动如果还在生效，就会变成：</p>
 * <ol>
 *   <li>这一 tick 先按 WASD 走一点点 → <b>走路距离 walkDist 累加</b>；</li>
 *   <li>下一 tick 位置又被坐垫钉回去（坐标看着没变）；</li>
 * </ol>
 * <p>位置被拉回来没问题，但 <b>walkDist 的累加会一直驱动原版的「视角摆动」</b>
 * （{@code bobView}）：相机每 tick 上下摆一次 → 表现成"画面持续上下抖"，
 * 而且 F3 坐标不变、按住移动键才出现 —— 用户报的就是这个现象。
 * 视角控制椅那种大座舱模型把相机包在里面时尤其明显。</p>
 *
 * <p>所以这里直接在最上游掐掉：乘客是我们的坐垫实体时，{@code travel}（原版
 * 处理自身移动/跳跃的入口）整个跳过。位置仍然由 {@code positionRider} 决定，
 * 所以"跟着枢轴点走""跟着船走"这些都不受影响。</p>
 *
 * <p>目标是原版 {@code Player}，无门控；只在"骑的是我们的坐垫实体"时生效，
 * 其它情况一律原样放行。</p>
 */
@Mixin(Player.class)
public abstract class PlayerSeatMovementMixin {

    @Inject(method = "travel", at = @At("HEAD"), cancellable = true)
    private void parachute$noTravelWhileSeated(Vec3 travelVector, CallbackInfo ci) {
        if (((Entity) (Object) this).getVehicle() instanceof ParachuteSeatEntity) {
            ci.cancel();
        }
    }
}
