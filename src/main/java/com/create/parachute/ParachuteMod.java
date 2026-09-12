package com.create.parachute;

import com.create.parachute.data.ParachuteManager;
import com.create.parachute.network.ClientboundParachuteVelocityPayload;
import com.create.parachute.network.ParachuteFileChunkPayload;
import com.create.parachute.network.ParachuteUploadRequestPayload;
import com.create.parachute.network.SyncParachuteConfigPayload;
import com.create.parachute.network.SyncParachuteLockPayload;
import com.create.parachute.network.SyncParachuteSelectionPayload;
import com.create.parachute.network.SyncParachuteTransformPayload;
import com.create.parachute.registry.ModBlockEntities;
import com.create.parachute.registry.ModBlocks;
import com.create.parachute.registry.ModCreativeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(ParachuteMod.MOD_ID)
public class ParachuteMod {
    public static final String MOD_ID = "create_parachute";
    public static final Logger LOGGER = LogManager.getLogger();

    public ParachuteMod(IEventBus modEventBus, ModContainer container) {
        // 注册模组全局配置（COMMON 类型 → 主菜单 Mods → Parachute → Config 可调）
        container.registerConfig(ModConfig.Type.COMMON, ParachuteConfig.SPEC);

        // 两端都要在游戏根目录创建 parachute/ 并导出内置伞：
        // 客户端用它渲染（本地伞库，支持热加载），服务端用它当伞库
        // （/parachute list|upload|download|distribute 读写的就是服务端的这个文件夹）。
        ParachuteManager.ensureParachuteFolder();

        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        modEventBus.addListener(this::registerPayloads);

        // 客户端渲染器注册在 com.create.parachute.client.ClientSetup，
        // 由 @EventBusSubscriber(Dist.CLIENT) 自动完成——主类保持 dist-clean。
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(ParachuteMod.MOD_ID);
        registrar.playToServer(
                SyncParachuteConfigPayload.TYPE,
                SyncParachuteConfigPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                        SyncParachuteConfigPayload.handleServer(payload, serverPlayer);
                    }
                }
        );
        registrar.playToClient(
                ClientboundParachuteVelocityPayload.TYPE,
                ClientboundParachuteVelocityPayload.STREAM_CODEC,
                (payload, context) -> ClientboundParachuteVelocityPayload.handleClient(
                        payload, context.player().level())
        );
        registrar.playToServer(
                SyncParachuteSelectionPayload.TYPE,
                SyncParachuteSelectionPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                        SyncParachuteSelectionPayload.handleServer(payload, serverPlayer);
                    }
                }
        );
        registrar.playToServer(
                SyncParachuteLockPayload.TYPE,
                SyncParachuteLockPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                        SyncParachuteLockPayload.handleServer(payload, serverPlayer);
                    }
                }
        );
        registrar.playToServer(
                SyncParachuteTransformPayload.TYPE,
                SyncParachuteTransformPayload.STREAM_CODEC,
                (payload, context) -> {
                    if (context.player() instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                        SyncParachuteTransformPayload.handleServer(payload, serverPlayer);
                    }
                }
        );

        // 伞文件传输：文件分片双向使用（谁收到就写进谁的 parachute/），
        // 服务端方向在 ParachuteFileChunkPayload.handle 里做 OP 校验。
        registrar.playBidirectional(
                ParachuteFileChunkPayload.TYPE,
                ParachuteFileChunkPayload.STREAM_CODEC,
                ParachuteFileChunkPayload::handle
        );
        // 服务端 → 客户端：请求对方把本地伞传上来（/parachute upload）
        registrar.playToClient(
                ParachuteUploadRequestPayload.TYPE,
                ParachuteUploadRequestPayload.STREAM_CODEC,
                ParachuteUploadRequestPayload::handle
        );
    }

}
