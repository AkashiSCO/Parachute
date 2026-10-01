package com.create.parachute.mixin;

import com.create.parachute.parachute.ParachuteBlock;
import com.create.parachute.parachute.ParachuteBlockEntity;
import com.lowdragmc.lowdraglib2.gui.factory.BlockUIMenuType;
import com.lowdragmc.lowdraglib2.gui.ui.ModularUI;
import com.lowdragmc.lowdraglib2.gui.ui.UI;
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
        // ensureSynaxisChair()：客户端那侧的椅子是懒建立的，界面可能比它先到，
        // 不先建立就会拿到"没有椅子"的 BE（界面能用但改参数不生效）。
        if (blockEntity instanceof ParachuteBlockEntity parachute && parachute.ensureSynaxisChair()
                && blockEntity instanceof NetworkBlockEntityAccess access) {
            // 用 BlockUIHolder 重载（Synaxis 自己的方块就是用这个）：它会登记 UI 观察者与
            // UI 会话（beginOpening），字段值的下发/会话快照都靠它。注意命令那条校验
            // （handleCommand 里的 isOpenUiContainer）对幽灵椅子不成立，所以命令是由
            // ParachuteBlockEntityNetworkMixin 自己转发、自己校验玩家的。
            ModularUI ui = access.createModularUI(holder);
            if (ui != null) {
                return ui;
            }
        }
        // 关键：绝不能返回 null。ldlib 拿到 null 会在 setMenu 时 NPE 并把客户端踢出游戏
        // （客户端那侧的幽灵椅子是懒建立的，界面比它先打开就会走到这里）。
        // Synaxis 自己的参照实现也是返回空 UI，这里照抄。
        return ModularUI.of(UI.empty(), holder.player);
    }
}
