package com.create.parachute.mixin;

import com.create.parachute.parachute.ParachuteBlock;
import com.create.parachute.parachute.ParachuteBlockEntity;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.verr1.synaxis.foundation.blockentity.NetworkBlockEntityAccess;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 让「坐垫模式伞包」的方块实现 ldlib2 的 UI 宿主接口，从而能打开 Synaxis 控制椅的设置面板。
 *
 * <h3>为什么接口在方块上而不是 BE 上</h3>
 * <p>ldlib2 开方块界面走的是 {@code BlockUIMenuType.openUI(player, pos)}：</p>
 * <pre>{@code
 * Block block = level.getBlockState(pos).getBlock();
 * if (block instanceof BlockUIMenuType.BlockUI ui) {          // ← 查的是方块
 *     BlockUIHolder holder = ui.createUIHolder(player, pos, state);
 *     return player.openMenu(holder).isPresent();
 * }
 * return false;
 * }</pre>
 * <p>Synaxis 自己的方块是靠实现 {@code NetworkBlockEntityBlockUi}（它 extends 了这个 ldlib 接口）
 * 才能开界面的。我们这里最小实现：只提供唯一的抽象方法 {@code createUI(holder)}，
 * 其余 {@code stillValid}/{@code getUIDisplayName}/{@code createUIHolder} 都有默认实现。</p>
 *
 * <p>{@code createUI} 直接把工作交回给方块实体：{@link NetworkBlockEntityAccess} 自带
 * default {@code createModularUI(player)}，而我们的 BE 已经把该接口代理到幽灵椅子上了
 * （见 {@link ParachuteBlockEntityNetworkMixin}），所以椅子的完整设置面板就自动出现。</p>
 *
 * <p>同样由 {@link ParachuteMixinPlugin} 门控：没装 Synaxis/ldlib2 时不应用。</p>
 */
@Mixin(ParachuteBlock.class)
public abstract class ParachuteBlockChairUiMixin implements BlockUIMenuType.BlockUI {

    @Override
    public ModularUI createUI(BlockUIMenuType.BlockUIHolder holder) {
        BlockEntity blockEntity = holder.player.level().getBlockEntity(holder.pos);
        if (blockEntity instanceof ParachuteBlockEntity parachute && parachute.hasSynaxisChair()
                && blockEntity instanceof NetworkBlockEntityAccess access) {
            return access.createModularUI(holder.player);
        }
        return null;
    }
}
