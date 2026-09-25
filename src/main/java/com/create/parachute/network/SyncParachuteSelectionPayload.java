package com.create.parachute.network;

import com.create.parachute.ParachuteMod;
import com.create.parachute.data.ParachuteManager;
import com.create.parachute.data.ParachuteTransfer;
import com.create.parachute.parachute.ParachuteBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.jetbrains.annotations.Nullable;

/**
 * 客户端 → 服务端：请求把选中的伞名写入目标。
 * <ul>
 *   <li>{@code pos != null}：写入该位置的伞方块实体 NBT，并同步给追踪的客户端</li>
 *   <li>{@code pos == null}：写入玩家手持伞包的物品 NBT（放置时带入方块）</li>
 * </ul>
 */
public record SyncParachuteSelectionPayload(BlockPos pos, String name) implements CustomPacketPayload {

    public static final Type<SyncParachuteSelectionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(ParachuteMod.MOD_ID, "sync_parachute_selection"));

    public static final StreamCodec<FriendlyByteBuf, SyncParachuteSelectionPayload> STREAM_CODEC =
            StreamCodec.ofMember(SyncParachuteSelectionPayload::write, SyncParachuteSelectionPayload::new);

    private void write(FriendlyByteBuf buf) {
        buf.writeBoolean(this.pos != null);
        if (this.pos != null) {
            buf.writeBlockPos(this.pos);
        }
        buf.writeUtf(this.name);
    }

    private SyncParachuteSelectionPayload(FriendlyByteBuf buf) {
        this(buf.readBoolean() ? buf.readBlockPos() : null, buf.readUtf(64));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public static void handleServer(SyncParachuteSelectionPayload payload, ServerPlayer player) {
        String name = sanitize(payload.name());
        if (name == null) {
            ParachuteMod.LOGGER.warn("[selection] rejected parachute name '{}' from {}",
                    payload.name(), player.getName().getString());
            return;
        }
        if (payload.pos() != null) {
            Level level = player.level();
            if (level == null) return;
            BlockEntity be = level.getBlockEntity(payload.pos());
            if (be instanceof ParachuteBlockEntity pbe) {
                pbe.setParachuteName(name);
                pbe.setChanged();
                // 统一走 BE 的广播入口（同样是 sendBlockUpdated，只是 flags 用 UPDATE_CLIENTS）
                pbe.syncToClients();
            }
            return;
        }

        // 写入手持伞包物品 NBT
        for (InteractionHand hand : new InteractionHand[]{InteractionHand.MAIN_HAND, InteractionHand.OFF_HAND}) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof com.create.parachute.parachute.ParachutePackItem) {
                CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
                stack.set(DataComponents.CUSTOM_DATA, data.update(tag -> tag.putString("ParachuteName", name)));
                return;
            }
        }
    }

    /**
     * 校验伞名：伞名就是磁盘上的<b>文件夹名</b>，所以规则必须和
     * {@link ParachuteTransfer#isValidFolderName} 一致 —— 只禁路径分隔符、Windows 保留字符和控制字符，限长 64。
     *
     * <p>这里以前只允许 {@code [A-Za-z0-9_]}，于是 {@code AH-64D_BLK.II}、{@code big-parachute.v2}
     * 这类完全合法的文件夹名会被<b>静默丢弃</b>，症状是"GUI 里选中了、世界里却没变"
     * （继续渲染成默认蘑菇伞）；其他纯小写字母数字的伞名不受影响，所以很容易看漏。</p>
     */
    @Nullable
    private static String sanitize(@Nullable String name) {
        if (name == null) return null;
        String n = name.trim();
        return ParachuteTransfer.isValidFolderName(n) ? n : null;
    }
}
