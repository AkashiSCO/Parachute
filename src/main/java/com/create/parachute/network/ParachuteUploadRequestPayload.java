package com.create.parachute.network;

import com.create.parachute.ParachuteMod;
import com.create.parachute.data.ParachuteTransfer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：「请把你本地的伞文件传上来」。
 *
 * <p>为什么要有这个请求包：{@code /parachute upload} 是在服务端执行的，而文件在玩家的客户端上，
 * 服务端读不到，所以由服务端发一个请求，客户端收到后自己读本地文件夹并用
 * {@link ParachuteFileChunkPayload} 分片回传（服务端会在那边校验 OP 权限）。</p>
 *
 * @param folder 要上传的伞名；空字符串表示上传本地全部伞
 */
public record ParachuteUploadRequestPayload(String folder) implements CustomPacketPayload {

    public static final Type<ParachuteUploadRequestPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ParachuteMod.MOD_ID, "parachute_upload_request"));

    public static final StreamCodec<FriendlyByteBuf, ParachuteUploadRequestPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeUtf(payload.folder == null ? "" : payload.folder, 64),
                    buf -> new ParachuteUploadRequestPayload(buf.readUtf(64)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handle(ParachuteUploadRequestPayload payload, IPayloadContext context) {
        context.enqueueWork(() -> ParachuteTransfer.handleUploadRequest(payload.folder(), context.player()));
    }
}
