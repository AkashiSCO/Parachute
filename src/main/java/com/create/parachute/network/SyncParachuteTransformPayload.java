package com.create.parachute.network;

import com.create.parachute.ExampleMod;
import com.create.parachute.parachute.ParachuteBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 客户端 → 服务端：设置伞面变换（旋转 / 枢轴 / 整体偏移），写入目标方块实体。
 * <ul>
 *   <li>{@code mode == MODE_ROTATION}：X/Y/Z 旋转（度，自摆动坐标系）</li>
 *   <li>{@code mode == MODE_PIVOT}：X/Y/Z 枢轴偏移（模型相对枢轴的位置，格）</li>
 *   <li>{@code mode == MODE_OFFSET}：X/Y/Z 整体偏移（含放置自带偏移，格）</li>
 * </ul>
 */
public record SyncParachuteTransformPayload(BlockPos pos, int mode, float x, float y, float z)
        implements CustomPacketPayload {

    public static final int MODE_ROTATION = 0;
    public static final int MODE_PIVOT = 1;
    public static final int MODE_OFFSET = 2;

    public static final Type<SyncParachuteTransformPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "sync_parachute_transform"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncParachuteTransformPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.fromCodec(BlockPos.CODEC), SyncParachuteTransformPayload::pos,
                    ByteBufCodecs.VAR_INT, SyncParachuteTransformPayload::mode,
                    ByteBufCodecs.FLOAT, SyncParachuteTransformPayload::x,
                    ByteBufCodecs.FLOAT, SyncParachuteTransformPayload::y,
                    ByteBufCodecs.FLOAT, SyncParachuteTransformPayload::z,
                    SyncParachuteTransformPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(SyncParachuteTransformPayload payload, ServerPlayer player) {
        Level level = player.level();
        if (level == null) return;
        BlockEntity be = level.getBlockEntity(payload.pos());
        if (be instanceof ParachuteBlockEntity pbe) {
            switch (payload.mode()) {
                case MODE_ROTATION -> pbe.setRotation(payload.x(), payload.y(), payload.z());
                case MODE_PIVOT -> pbe.setPivot(payload.x(), payload.y(), payload.z());
                case MODE_OFFSET -> pbe.setOffset(payload.x(), payload.y(), payload.z());
                default -> {
                    return;
                }
            }
            pbe.setChanged();
        }
    }
}
