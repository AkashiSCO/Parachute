package com.create.parachute.network;

import com.create.parachute.ParachuteMod;
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
 * 客户端 → 服务端：切换锁定自摆动状态。
 * 锁定时伞面三轴自摆动角度强制为 0。
 */
public record SyncParachuteLockPayload(BlockPos pos, boolean locked) implements CustomPacketPayload {

    public static final Type<SyncParachuteLockPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ParachuteMod.MOD_ID, "sync_parachute_lock"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncParachuteLockPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), SyncParachuteLockPayload::pos,
            ByteBufCodecs.BOOL, SyncParachuteLockPayload::locked,
            SyncParachuteLockPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(SyncParachuteLockPayload payload, ServerPlayer player) {
        Level level = player.level();
        if (level == null) return;
        BlockEntity be = level.getBlockEntity(payload.pos());
        if (be instanceof ParachuteBlockEntity pbe) {
            pbe.setWobbleLocked(payload.locked());
            // 锁定只影响渲染，不动 blockstate，必须显式广播给其他玩家
            pbe.setChanged();
            pbe.syncToClients();
        }
    }
}
