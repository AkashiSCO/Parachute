package com.create.parachute.mixin;

import com.create.parachute.parachute.ParachuteBlockEntity;
import com.verr1.synaxis.foundation.blockentity.NetworkBlockEntityAccess;
import com.verr1.synaxis.foundation.blockentity.NetworkBlockEntitySupport;
import com.verr1.synaxis.foundation.network.BlockCommandPayload;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;

/**
 * 让「坐垫模式伞包」的方块实体在 Synaxis 眼里就是一个正统的网络方块实体。
 *
 * <h3>为什么需要</h3>
 * <p>Synaxis 的同步 / 命令 / UI 会话全部是<b>按坐标</b>找方块实体、再要求它实现
 * {@link NetworkBlockEntityAccess}（{@code BlockEntitySync}、{@code SynaxisPackets}、
 * {@code LdLib*} 那一整套都这么写）。幽灵椅子虽然是个真椅子，但它不在 level 的 BE 表里，
 * 按坐标永远查不到它 —— 于是客户端拿不到椅子状态，控制椅的双视角自然也不生效。</p>
 *
 * <p>{@code NetworkBlockEntityAccess} 除 {@code networkSupport()} 以外的成员<b>全是 default 方法</b>，
 * 而 {@code getBlockPos/getLevel/getBlockState/isRemoved/setChanged} 原版 {@code BlockEntity}
 * 本来就有，所以这里只要把幽灵椅子的后端（{@code NetworkBlockEntitySupport}）交出去，
 * 整套同步/UI 就都落到幽灵椅子上了。</p>
 *
 * <h3>为什么命令要自己转发</h3>
 * <p>Synaxis 的 {@code handleCommand} 会先用<b>椅子自己</b>的 {@code canPlayerUse} 做一次校验；
 * 幽灵椅子不在 level 的 BE 表里（表里坐的是伞包），那道校验对它不成立，命令会被直接丢掉
 * —— 表现就是"界面能开、参数改不动"。这里改成：校验归我们（按坐标算距离的那套），
 * 真正的命令执行仍然交给幽灵椅子的 {@code CommandRegistry}（这样状态写入、同步、
 * 存储、电路外设全都还落在椅子上）。</p>
 *
 * <h3>安全性</h3>
 * <ul>
 *   <li>目标是我们自己的类，接口用字符串在 {@link ParachuteMixinPlugin} 里门控：
 *       只有装了 Synaxis 才应用（本类引用 Synaxis 类型，也只有那时才会被加载）；</li>
 *   <li>幽灵椅子不存在（普通伞包模式 / 没装 Synaxis）时 {@code parachuteNetworkSupport()}
 *       返回 {@code null}，这里直接忽略命令；</li>
 *   <li>玩家的合法性仍然按伞包那套判（{@code canPlayerUse} = 8 格内）。</li>
 * </ul>
 */
@Mixin(ParachuteBlockEntity.class)
public abstract class ParachuteBlockEntityNetworkMixin implements NetworkBlockEntityAccess {

    @Override
    public NetworkBlockEntitySupport networkSupport() {
        return (NetworkBlockEntitySupport) ((ParachuteBlockEntity) (Object) this).parachuteNetworkSupport();
    }

    /**
     * 命令入口：按坐标路由过来的 Synaxis 命令，由我们校验玩家、再交给幽灵椅子的命令表执行。
     *
     * <p>不调幽灵椅子自己的 {@code handleCommand}，因为那道 {@code canPlayerUse} 校验对
     * 不在 level BE 表里的幽灵椅子不成立（会把所有命令丢掉）。</p>
     */
    @Override
    public void handleCommand(BlockCommandPayload payload, ServerPlayer player) {
        ParachuteBlockEntity self = (ParachuteBlockEntity) (Object) this;
        NetworkBlockEntitySupport support = (NetworkBlockEntitySupport) self.parachuteNetworkSupport();
        if (support == null || !self.canPlayerUse(player)) {
            return;
        }
        support.commands().execute(payload, player);
    }
}
