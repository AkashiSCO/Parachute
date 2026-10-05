package com.create.parachute.mixin;

import com.create.parachute.parachute.ParachuteBlockEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 幽灵控制椅的「能不能用」判定：交给它<b>所在坐标的宿主伞包</b>。
 *
 * <h3>问题</h3>
 * <p>Synaxis 的 {@code NetworkBlockEntityAccess#canPlayerUse(Player)} 由 Create 的
 * {@code SmartBlockEntity} 提供实现，里面有一条「这个坐标上的方块实体必须是我自己」的检查
 * （原意大概是防止隔着老远操作机器）。幽灵椅子是个<b>没进 level BE 表</b>的真椅子 BE
 * （那个坐标上坐着的是伞包），这条检查对它恒为 false。</p>
 *
 * <p>后果是所有以 {@code owner.canPlayerUse(player)} 开头的入口全被丢掉：</p>
 * <ul>
 *   <li>{@code handleStateRequest} —— 客户端请求状态快照（{@code requestStateSnapshot(RENDER)}）
 *       被拒 → 客户端椅子一直是默认值：<b>重进存档后，控制椅的设置要先用烈焰棒开一次界面才生效</b>
 *       （开界面会登记 UI 观察者、顺带把状态推下来，所以看着像"被激活"了）；</li>
 *   <li>{@code handleUiSessionRequest} —— UI 会话数据同步被拒；</li>
 *   <li>{@code handleCommand} —— 命令被拒（伞包那边另有一层转发兜底，见
 *       {@link ParachuteBlockEntityNetworkMixin}）。</li>
 * </ul>
 *
 * <h3>修法</h3>
 * <p>只在「这个坐标上坐着的是我们的伞包」时接管判定，其余 {@code SmartBlockEntity}
 * 一律原样走 Create 的逻辑。这样幽灵椅子的权限语义就和伞包一致了（伞包那套是
 * {@code canPlayerUse} = 8 格内），不需要在 Synaxis 的每个入口各写一遍。</p>
 *
 * <p>目标类由 {@link ParachuteMixinPlugin} 门控（Create + Synaxis 都在场才应用）；
 * {@code require = 0} 保证 Create 改动结构时只丢这个兜底、不崩游戏。</p>
 */
@Mixin(targets = "com.simibubi.create.foundation.blockEntity.SmartBlockEntity", remap = false)
public abstract class SmartBlockEntityPermissionMixin {

    @Inject(method = "canPlayerUse", at = @At("HEAD"), cancellable = true, require = 0)
    private void parachute$hostPermission(Player player, CallbackInfoReturnable<Boolean> cir) {
        BlockEntity self = (BlockEntity) (Object) this;
        Level level = self.getLevel();
        if (level == null || player == null) {
            return;
        }
        if (level.getBlockEntity(self.getBlockPos()) instanceof ParachuteBlockEntity host) {
            cir.setReturnValue(host.canPlayerUse(player));
        }
    }
}
