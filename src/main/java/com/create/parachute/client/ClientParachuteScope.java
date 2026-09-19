package com.create.parachute.client;

import com.create.parachute.ParachuteMod;
import com.create.parachute.data.ParachuteManager;
import com.create.parachute.data.ParachuteScope;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端实际使用的伞源判断。
 *
 * <p>判断永远在「用的时候」现算（不是登录时缓存），所以切服务器、退出服务器都立刻生效：</p>
 * <ol>
 *   <li>单机世界、以及自己开 LAN 的世界（{@code hasSingleplayerServer()}）→ 本地
 *       {@code parachute/local}</li>
 *   <li>连别人的服务器，且已经收到对方的<b>存档 UUID</b>
 *       （{@link com.create.parachute.network.ServerIdentityPayload}）→
 *       {@code parachute/server/<UUID>}</li>
 *   <li>对方没装模组（没发 UUID）→ 兜底用
 *       {@code parachute/server/<服务器地址>}，例如 {@code localhost:25565} →
 *       {@code parachute/server/localhost_25565}</li>
 * </ol>
 *
 * <p>本类 {@code Dist.CLIENT}，专用服务端不会加载它；{@link ParachuteScope} 里那个注入点是
 * 两端共用的 {@code data} 包保持 dist-clean 的关键。</p>
 */
@EventBusSubscriber(modid = ParachuteMod.MOD_ID, value = Dist.CLIENT)
public final class ClientParachuteScope {

    /** 服务器文件夹里那份「这是哪台服务器」的说明文件（我们自己写的，扫描时会忽略） */
    private static final String INFO_FILE = "server-info.txt";

    /** 已经处理过迁移/写说明文件的 UUID，避免每次取伞源都做文件操作 */
    @Nullable
    private static String preparedFolder;

    private ClientParachuteScope() {
    }

    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        // 顺便把客户端的两个文件夹准备好（local 首次创建时做旧版本迁移）
        ParachuteManager.ensureClientFolders();
        ParachuteScope.setClientScope(ClientParachuteScope::current);
    }

    /** 退出服务器：忘掉上一台的存档 UUID，免得下一台服务器误用它 */
    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        ParachuteScope.setServerIdentity(null);
    }

    /** 当前的伞源：单机 / 自己开的世界用本地，连别人的服务器用那台服务器的文件夹 */
    public static ParachuteScope.Source current() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null) return ParachuteScope.local();

        // 单机 / LAN 主机：一律用玩家自己的 local（主机自己的伞库不参与客户端选伞）
        boolean hosting = minecraft.hasSingleplayerServer();
        ServerData server = minecraft.getCurrentServer();

        // 连别人的服务器：优先用服务端同步过来的存档 UUID
        String serverId = ParachuteScope.serverIdentity();
        if (!hosting && server != null && serverId != null) {
            prepareServerFolder(serverId, server);
            return new ParachuteScope.Source(serverId, ParachuteManager.serverPath(serverId));
        }
        if (hosting || server == null) {
            return ParachuteScope.local();
        }
        // 兜底：对方没装模组（或还没发 UUID）→ 按服务器地址分文件夹
        String folder = ParachuteScope.sanitizeServerFolder(server.ip);
        return new ParachuteScope.Source(folder, ParachuteManager.serverPath(folder));
    }

    /**
     * 第一次用到某个服务器 UUID 时做两件事：
     * <ol>
     *   <li>如果还存在早期「按地址命名」的文件夹（{@code server/<地址>}/），改名成 UUID 文件夹，
     *       这样之前下载过的伞不会凭空消失</li>
     *   <li>在里面写一份 {@code server-info.txt}（服务器名/地址），方便知道这个 UUID 是哪台服务器</li>
     * </ol>
     */
    private static void prepareServerFolder(String serverId, @Nullable ServerData server) {
        if (serverId.equals(preparedFolder)) return;
        preparedFolder = serverId;

        Path target = ParachuteManager.serverPath(serverId);
        try {
            if (!Files.exists(target) && server != null) {
                Path legacy = ParachuteManager.serverPath(ParachuteScope.sanitizeServerFolder(server.ip));
                if (Files.isDirectory(legacy)) {
                    Files.createDirectories(target.getParent());
                    Files.move(legacy, target);
                    ParachuteMod.LOGGER.info("[identity] renamed {} -> {}", legacy.getFileName(), target.getFileName());
                }
            }
            Files.createDirectories(target);
            Files.writeString(target.resolve(INFO_FILE), info(serverId, server), StandardCharsets.UTF_8);
        } catch (IOException e) {
            ParachuteMod.LOGGER.warn("[identity] failed to prepare server folder {}: {}", target, e.toString());
        }
    }

    private static String info(String serverId, @Nullable ServerData server) {
        return "parachute id: " + serverId + "\n"
                + "server name: " + (server == null ? "?" : server.name) + "\n"
                + "address: " + (server == null ? "?" : server.ip) + "\n";
    }
}
