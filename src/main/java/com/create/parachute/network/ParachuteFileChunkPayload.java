package com.create.parachute.network;

import com.create.parachute.ParachuteMod;
import com.create.parachute.data.ParachuteTransfer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 伞文件的一个分片，<b>双向</b>使用：谁收到包，就把内容写进谁自己的 {@code parachute/} 目录。
 *
 * <ul>
 *   <li>客户端 → 服务端：OP 上传本地伞（写进服务端伞库）</li>
 *   <li>服务端 → 客户端：下载 / 分发（写进玩家本地文件夹，热加载自动生效）</li>
 * </ul>
 *
 * <p>服务端方向额外要求发送者是 OP（权限等级 2），否则直接丢弃——这是防止普通玩家往服务端写文件的
 * 唯一一道关口，所以校验放在解包的最前面。</p>
 *
 * @param folder     伞名（文件夹名）；空字符串表示直接放在 {@code parachute/} 根下（打包下载的 zip）
 * @param file       文件名
 * @param totalBytes 整个文件的总字节数（接收端用于判断是否收齐）
 * @param offset     本分片在文件中的起始偏移（接收端按此顺序拼接）
 * @param data       本分片的字节
 * @param last       是否为最后一个分片（收到即落盘）
 */
public record ParachuteFileChunkPayload(String folder, String file, int totalBytes, int offset, byte[] data, boolean last)
        implements CustomPacketPayload {

    public static final Type<ParachuteFileChunkPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ParachuteMod.MOD_ID, "parachute_file_chunk"));

    public static final StreamCodec<FriendlyByteBuf, ParachuteFileChunkPayload> STREAM_CODEC =
            StreamCodec.of(ParachuteFileChunkPayload::write, ParachuteFileChunkPayload::read);

    private static void write(FriendlyByteBuf buf, ParachuteFileChunkPayload payload) {
        buf.writeUtf(payload.folder == null ? "" : payload.folder, 64);
        buf.writeUtf(payload.file, 128);
        buf.writeVarInt(payload.totalBytes);
        buf.writeVarInt(payload.offset);
        buf.writeByteArray(payload.data);
        buf.writeBoolean(payload.last);
    }

    private static ParachuteFileChunkPayload read(FriendlyByteBuf buf) {
        String folder = buf.readUtf(64);
        String file = buf.readUtf(128);
        int totalBytes = buf.readVarInt();
        int offset = buf.readVarInt();
        // 上限跟着分片大小走，读取时就把异常大的包拒掉
        byte[] data = buf.readByteArray(ParachuteTransfer.CHUNK_SIZE);
        boolean last = buf.readBoolean();
        return new ParachuteFileChunkPayload(folder, file, totalBytes, offset, data, last);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ParachuteFileChunkPayload payload, IPayloadContext context) {
        PacketFlow flow = context.flow();
        if (flow == PacketFlow.SERVERBOUND
                && (!(context.player() instanceof ServerPlayer serverPlayer) || !serverPlayer.hasPermissions(2))) {
            ParachuteMod.LOGGER.warn("[transfer] rejected upload from non-op {}",
                    context.player() == null ? "<unknown>" : context.player().getName().getString());
            return;
        }
        context.enqueueWork(() -> ParachuteTransfer.receiveChunk(payload, flow));
    }
}
