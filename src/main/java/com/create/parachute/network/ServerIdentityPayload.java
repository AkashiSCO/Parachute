package com.create.parachute.network;

import com.create.parachute.ParachuteMod;
import com.create.parachute.data.ParachuteScope;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * 服务端 → 客户端：这台服务器（准确说是它当前这个<b>存档</b>）的唯一 UUID。
 *
 * <p>客户端用它当 {@code parachute/server/<UUID>/} 的文件夹名，这样同一台服务器的下载不会和
 * 别的服务器、也不会和玩家自己的 {@code parachute/local} 混在一起；服务器换地址/换端口
 * （LAN 每次开放端口都变）也不会让客户端又多出一个文件夹。</p>
 *
 * <p>客户端会校验它是不是合法 UUID（见 {@link ParachuteScope#canonicalUuid}）——
 * 这个字符串最终会变成文件夹名，绝不能直接信服务器给的原文。</p>
 *
 * @param serverId 存档 UUID 的规范字符串；服务器没就绪时为空串（客户端忽略）
 */
public record ServerIdentityPayload(String serverId) implements CustomPacketPayload {

    public static final Type<ServerIdentityPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ParachuteMod.MOD_ID, "server_identity"));

    public static final StreamCodec<FriendlyByteBuf, ServerIdentityPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buf, payload) -> buf.writeUtf(payload.serverId == null ? "" : payload.serverId, 36),
                    buf -> new ServerIdentityPayload(buf.readUtf(36)));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** 客户端收到：记下当前服务器的存档 UUID（校验与规范化都在 ParachuteScope 里做） */
    public static void handleClient(ServerIdentityPayload payload, IPayloadContext context) {
        // 只写一个 volatile 字段，不用 enqueueWork
        ParachuteScope.setServerIdentity(payload.serverId());
    }
}
