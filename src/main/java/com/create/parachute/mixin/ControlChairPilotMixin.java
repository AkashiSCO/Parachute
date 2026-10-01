package com.create.parachute.mixin;

import com.create.parachute.compat.synaxis.SynaxisChairLog;
import com.create.parachute.parachute.ParachuteSeatEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 让 Synaxis 控制椅把「坐垫模式伞包」上的乘客也当成驾驶员。
 *
 * <h3>为什么需要这个补丁</h3>
 * <p>Synaxis 的 {@code ControlChairBlockEntity#findPilot()} 只认 <b>Create 的 {@code SeatEntity}</b>
 * （{@code level.getEntitiesOfClass(SeatEntity.class, AABB(pos))}），而这条线索在伞包这边走不通：</p>
 * <ul>
 *   <li>Create 的 {@code SeatEntity.tick()} 里有
 *       {@code if (isVehicle() && level.getBlockState(blockPosition()).getBlock() instanceof SeatBlock) return; discard();}
 *       —— 坐垫要活下来，脚下的方块必须是 {@code SeatBlock}；</li>
 *   <li>而我们的伞包方块必须继承 {@code BaseEntityBlock}（要方块实体），无法同时继承
 *       Create 的 {@code SeatBlock}（它继承的是 {@code Block}）。</li>
 * </ul>
 * <p>所以改成保留我们自己的 {@link ParachuteSeatEntity}，在这里给 {@code findPilot()} 补一条兜底：
 * 原版找不到 Create 席位时，再看本坐标上有没有伞包席位，有就返回它的乘客。</p>
 *
 * <h3>安全性</h3>
 * <ul>
 *   <li>目标类用字符串指定（{@code targets = "..."}），所以本模组对 Synaxis 依然<b>没有编译期依赖</b>；</li>
 *   <li>是否应用由 {@link ParachuteMixinPlugin} 判定：只有装了 Synaxis 才应用；</li>
 *   <li>{@code require = 0}：万一将来 Synaxis 改名/删掉 {@code findPilot}，这里只会记一条日志，不会崩游戏。</li>
 * </ul>
 */
@Mixin(targets = "com.verr1.synaxis.content.blocks.controlchair.ControlChairBlockEntity", remap = false)
public abstract class ControlChairPilotMixin {

    @Inject(method = "findPilot", at = @At("RETURN"), cancellable = true, require = 0)
    private void parachute$acceptParachuteSeat(CallbackInfoReturnable<Player> cir) {
        if (cir.getReturnValue() != null) {
            return;
        }
        BlockEntity self = (BlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null) {
            return;
        }
        for (ParachuteSeatEntity seat : level.getEntitiesOfClass(ParachuteSeatEntity.class, new AABB(self.getBlockPos()))) {
            for (Entity passenger : seat.getPassengers()) {
                if (passenger instanceof Player player) {
                    SynaxisChairLog.pilotFallback(player);
                    cir.setReturnValue(player);
                    return;
                }
            }
        }
    }
}
