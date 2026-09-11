package com.create.parachute;

import com.create.parachute.data.ParachuteManager;
import com.create.parachute.network.ClientboundParachuteVelocityPayload;
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
import net.neoforged.fml.loading.FMLEnvironment;
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

        // 客户端专用：在游戏根目录创建 parachute/ 文件夹并导出内置伞。
        // 只有客户端的 ParachuteAssets 会解析 .bbmodel/.png，服务端从不读取这些文件，
        // 所以在专用服务端上导出只是白占磁盘、还会让服主误以为往里丢模型能分发给玩家。
        // FMLEnvironment 是公共类，这里不涉及任何客户端类型，主类依然 dist-clean。
        if (FMLEnvironment.dist.isClient()) {
            ParachuteManager.ensureParachuteFolder();
        }

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
    }

}
