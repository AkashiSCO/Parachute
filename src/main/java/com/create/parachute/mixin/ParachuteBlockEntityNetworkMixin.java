package com.create.parachute.mixin;

import com.create.parachute.parachute.ParachuteBlockEntity;
import com.verr1.synaxis.foundation.blockentity.NetworkBlockEntityAccess;
import com.verr1.synaxis.foundation.blockentity.NetworkBlockEntitySupport;
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
 * <h3>安全性</h3>
 * <ul>
 *   <li>目标是我们自己的类，接口用字符串在 {@link ParachuteMixinPlugin} 里门控：
 *       只有装了 Synaxis 才应用（本类引用 Synaxis 类型，也只有那时才会被加载）；</li>
 *   <li>幽灵椅子不存在（普通伞包模式 / 没装 Synaxis）时返回 {@code null}，
 *       调用方（{@link com.create.parachute.mixin.ParachuteBlockChairUiMixin}）会先检查再委托。</li>
 * </ul>
 */
@Mixin(ParachuteBlockEntity.class)
public abstract class ParachuteBlockEntityNetworkMixin implements NetworkBlockEntityAccess {

    @Override
    public NetworkBlockEntitySupport networkSupport() {
        return (NetworkBlockEntitySupport) ((ParachuteBlockEntity) (Object) this).parachuteNetworkSupport();
    }
}
