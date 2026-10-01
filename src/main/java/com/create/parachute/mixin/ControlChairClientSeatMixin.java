package com.create.parachute.mixin;

import com.create.parachute.parachute.ParachuteBlock;
import com.create.parachute.parachute.ParachuteSeatEntity;
import com.verr1.synaxis.foundation.physics.WorldBlockPos;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 Synaxis 客户端把「坐在坐垫模式伞包上」也当成坐在控制椅上（双视角 / 视角锁定的前提）。
 *
 * <h3>Synaxis 原本的判定</h3>
 * <pre>{@code
 * // ClientControlChairManager#currentChairPos(LocalPlayer)
 * Entity vehicle = player.getVehicle();
 * if (!(vehicle instanceof SeatEntity)) return null;                        // Create 的座位实体
 * if (!(level.getBlockState(pos).getBlock() instanceof ControlChairBlock))  // 控制椅方块
 *     return null;
 * return WorldBlockPos.of(level, pos);
 * }</pre>
 * <p>两条都是具体类型的 {@code instanceof}：我们的坐垫模式伞包用的是自己的
 * {@link ParachuteSeatEntity}，方块也是伞包方块，所以客户端永远不会认为玩家"在控制椅上"，
 * 第一人称/第三人称的控制椅视角自然不生效。</p>
 *
 * <h3>这个补丁做什么</h3>
 * <p>只在原版判定返回 {@code null} 之后兜底：如果玩家骑的是伞包席位、脚下又是坐垫模式的伞包方块，
 * 就把这个坐标当成控制椅坐标返回。椅子状态本身已经能同步到客户端了（方块实体实现了
 * {@code NetworkBlockEntityAccess}，见 {@link ParachuteBlockEntityNetworkMixin}）。</p>
 *
 * <p>目标类用字符串指定、由 {@link ParachuteMixinPlugin} 门控（只有装了 Synaxis 才应用），
 * {@code require = 0} 保证 Synaxis 改结构时只丢功能不崩游戏。</p>
 */
@Mixin(targets = "com.verr1.synaxis.foundation.input.ClientControlChairManager", remap = false)
public abstract class ControlChairClientSeatMixin {

    @Inject(method = "currentChairPos", at = @At("RETURN"), cancellable = true, require = 0)
    private static void parachute$acceptOwnSeat(LocalPlayer player, CallbackInfoReturnable<WorldBlockPos> cir) {
        if (player == null || cir.getReturnValue() != null) {
            return;
        }
        if (!(player.getVehicle() instanceof ParachuteSeatEntity seat)) {
            return;
        }
        BlockPos pos = seat.blockPosition();
        BlockState state = player.level().getBlockState(pos);
        if (state.getBlock() instanceof ParachuteBlock && state.hasProperty(ParachuteBlock.SEAT)
                && state.getValue(ParachuteBlock.SEAT)) {
            cir.setReturnValue(WorldBlockPos.of(player.level(), pos));
        }
    }
}
