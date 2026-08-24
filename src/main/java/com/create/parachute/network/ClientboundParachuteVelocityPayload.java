package com.create.parachute.network;

import com.create.parachute.ExampleMod;
import com.create.parachute.parachute.ParachuteBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

public record ClientboundParachuteVelocityPayload(
        BlockPos pos,
        double lx, double ly, double lz   // 体坐标系方向 (服务端从世界速度转来的)
) implements CustomPacketPayload {

    public static final Type<ClientboundParachuteVelocityPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "parachute_velocity"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ClientboundParachuteVelocityPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.fromCodec(BlockPos.CODEC), ClientboundParachuteVelocityPayload::pos,
                    ByteBufCodecs.DOUBLE, ClientboundParachuteVelocityPayload::lx,
                    ByteBufCodecs.DOUBLE, ClientboundParachuteVelocityPayload::ly,
                    ByteBufCodecs.DOUBLE, ClientboundParachuteVelocityPayload::lz,
                    ClientboundParachuteVelocityPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleClient(ClientboundParachuteVelocityPayload payload, Level clientLevel) {
        BlockEntity be = clientLevel.getBlockEntity(payload.pos());
        if (be instanceof ParachuteBlockEntity pbe) {
            pbe.setClientVel(payload.lx(), payload.ly(), payload.lz());
        }
    }
}
