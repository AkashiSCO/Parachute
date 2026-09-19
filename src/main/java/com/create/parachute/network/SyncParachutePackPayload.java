package com.create.parachute.network;

import com.create.parachute.ParachuteMod;
import com.create.parachute.parachute.ParachuteBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 客户端 → 服务端：切换「伞包方块自身模型」的显示/隐藏（控制器界面那个红键）。
 *
 * <p>落在 {@link ParachuteBlock#PACK} 方块状态属性上：方块状态本来就是全服同步、随区块存档的，
 * 所以隐藏后所有玩家看到的一致，重启也还在；方块模型是否渲染由
 * {@link ParachuteBlock#getRenderShape} 决定（隐藏时 INVISIBLE），BER 画的伞面不受影响。</p>
 */
public record SyncParachutePackPayload(BlockPos pos, boolean visible) implements CustomPacketPayload {

    public static final Type<SyncParachutePackPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ParachuteMod.MOD_ID, "sync_parachute_pack"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SyncParachutePackPayload> STREAM_CODEC =
            StreamCodec.composite(
                    ByteBufCodecs.fromCodec(BlockPos.CODEC), SyncParachutePackPayload::pos,
                    ByteBufCodecs.BOOL, SyncParachutePackPayload::visible,
                    SyncParachutePackPayload::new
            );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(SyncParachutePackPayload payload, ServerPlayer player) {
        Level level = player.level();
        if (level == null) return;
        BlockPos pos = payload.pos();
        if (!level.isLoaded(pos)) return;
        BlockState state = level.getBlockState(pos);
        if (!state.hasProperty(ParachuteBlock.PACK)) return;
        if (state.getValue(ParachuteBlock.PACK) == payload.visible()) return;
        // setBlock 会自己通知客户端（方块状态同步），不用再手动广播 BE 数据
        level.setBlock(pos, state.setValue(ParachuteBlock.PACK, payload.visible()),
                net.minecraft.world.level.block.Block.UPDATE_ALL);
    }
}
