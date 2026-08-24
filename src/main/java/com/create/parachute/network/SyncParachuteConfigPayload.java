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

public record SyncParachuteConfigPayload(
        BlockPos pos,
        int dragCoefficient,
        int rotationalDragCoefficient,
        int disconnectSpeed,
        boolean disconnectOnLowSpeed,
        boolean disconnectOnRedstone
) implements CustomPacketPayload {

    public static final Type<SyncParachuteConfigPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "sync_parachute_config"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncParachuteConfigPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.fromCodec(BlockPos.CODEC), SyncParachuteConfigPayload::pos,
            ByteBufCodecs.VAR_INT, SyncParachuteConfigPayload::dragCoefficient,
            ByteBufCodecs.VAR_INT, SyncParachuteConfigPayload::rotationalDragCoefficient,
            ByteBufCodecs.VAR_INT, SyncParachuteConfigPayload::disconnectSpeed,
            ByteBufCodecs.BOOL, SyncParachuteConfigPayload::disconnectOnLowSpeed,
            ByteBufCodecs.BOOL, SyncParachuteConfigPayload::disconnectOnRedstone,
            SyncParachuteConfigPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(SyncParachuteConfigPayload payload, ServerPlayer player) {
        Level level = player.level();
        if (level == null) {
            return;
        }
        BlockEntity be = level.getBlockEntity(payload.pos());
        if (be instanceof ParachuteBlockEntity pbe) {
            pbe.setDragCoefficient(payload.dragCoefficient() / 100.0D);
            pbe.setRotationalDragCoefficient(payload.rotationalDragCoefficient() / 100.0D);
            pbe.setDisconnectSpeedThreshold(payload.disconnectSpeed() / 100.0D);
            pbe.setDisconnectOnLowSpeed(payload.disconnectOnLowSpeed());
            pbe.setDisconnectOnRedstonePulse(payload.disconnectOnRedstone());
            pbe.setChanged();
        }
    }
}
