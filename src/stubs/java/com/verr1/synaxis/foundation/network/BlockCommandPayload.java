package com.verr1.synaxis.foundation.network;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * 编译期桩：Synaxis 的「方块实体命令」网络包。
 *
 * <p>真实类型是个 {@code CustomPacketPayload} 记录；这里只需要让伞包的转发方法
 * 在编译期有个类型可用（运行时用真类，方法描述符一致）。</p>
 */
public record BlockCommandPayload(int containerId, BlockPos pos, ResourceLocation commandId, byte[] data) {
}
