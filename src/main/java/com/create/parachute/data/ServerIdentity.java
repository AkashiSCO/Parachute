package com.create.parachute.data;

import com.create.parachute.ParachuteMod;
import com.create.parachute.network.ServerIdentityPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 服务器身份：<b>每个存档一个 UUID</b>，存在存档文件夹里，玩家登录时同步给客户端。
 *
 * <p>客户端拿它当 {@code parachute/server/<UUID>/} 的文件夹名，于是：</p>
 * <ul>
 *   <li>专用服务器：区分的是服务器上这个存档（换存档 = 换 ID = 客户端换文件夹）</li>
 *   <li>LAN：区分的是<b>主机玩家那个存档</b>（同一台主机开不同存档是不同文件夹）</li>
 *   <li>地址变了（换域名、换端口、LAN 每次开放端口都变）→ ID 不变，客户端文件夹不分裂</li>
 * </ul>
 *
 * <p>落盘位置 {@code <存档>/create_parachute/server-id.txt}：跟着存档走，备份/复制存档会一起带走，
 * 所以「同一个存档 = 同一个 ID」——把这台服务器的存档复制到另一台机器，客户端仍然认成同一个。</p>
 *
 * <p>读不到/写不进去时返回 null，客户端会自动退回「按服务器地址分文件夹」。</p>
 */
@EventBusSubscriber(modid = ParachuteMod.MOD_ID)
public final class ServerIdentity {

    /** 存档内的存放位置 */
    private static final String ID_DIR = "create_parachute";
    /** 存档 UUID 的文件名 */
    private static final String ID_FILE = "server-id.txt";

    @Nullable
    private static volatile String id;

    private ServerIdentity() {
    }

    /** 当前服务器（存档）的 UUID；未就绪/失败时为 null */
    @Nullable
    public static String id() {
        return id;
    }

    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        id = loadOrCreate(event.getServer());
        if (id != null) {
            ParachuteMod.LOGGER.info("[identity] this world's parachute id is {}", id);
        }
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        id = null;
    }

    /** 每个玩家进服时告诉他「这台服务器是哪个存档」，客户端据此决定下载落点 */
    @SubscribeEvent
    public static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        String current = id;
        if (current == null || !(event.getEntity() instanceof ServerPlayer player)) return;
        PacketDistributor.sendToPlayer(player, new ServerIdentityPayload(current));
    }

    /** 读存档里的 UUID；没有（或内容坏了）就生成一个写进去 */
    @Nullable
    private static String loadOrCreate(MinecraftServer server) {
        try {
            Path file = server.getWorldPath(LevelResource.ROOT).resolve(ID_DIR).resolve(ID_FILE);
            if (Files.isRegularFile(file)) {
                String existing = ParachuteScope.canonicalUuid(Files.readString(file, StandardCharsets.UTF_8));
                if (existing != null) return existing;
                ParachuteMod.LOGGER.warn("[identity] {} is not a valid UUID, regenerating", file);
            }
            String fresh = UUID.randomUUID().toString();
            Files.createDirectories(file.getParent());
            Files.writeString(file, fresh, StandardCharsets.UTF_8);
            return fresh;
        } catch (Exception e) {
            ParachuteMod.LOGGER.warn("[identity] failed to load/create the world parachute id: {}", e.toString());
            return null;
        }
    }
}
