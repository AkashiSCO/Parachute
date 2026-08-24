package com.create.parachute;

import com.create.parachute.client.ClientSetup;
import com.create.parachute.data.ParachuteManager;
import com.create.parachute.network.ClientboundParachuteVelocityPayload;
import com.create.parachute.network.SyncParachuteConfigPayload;
import com.create.parachute.network.SyncParachuteLockPayload;
import com.create.parachute.network.SyncParachuteSelectionPayload;
import com.create.parachute.network.SyncParachuteTransformPayload;
import com.create.parachute.registry.ModBlockEntities;
import com.create.parachute.registry.ModBlocks;
import com.create.parachute.registry.ModCreativeTabs;
import com.create.parachute.registry.ModMenus;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(ExampleMod.MOD_ID)
public class ExampleMod {
    public static final String MOD_ID = "create_parachute";
    public static final Logger LOGGER = LogManager.getLogger();

    public ExampleMod(IEventBus modEventBus, ModContainer container) {
        // 注册模组全局配置（COMMON 类型 → 主菜单 Mods → Parachute → Config 可调）
        container.registerConfig(ModConfig.Type.COMMON, ParachuteConfig.SPEC);

        // 模组成功加载：在游戏根目录创建 parachute/ 文件夹并导出内置伞
        ParachuteManager.ensureParachuteFolder();

        ModBlocks.BLOCKS.register(modEventBus);
        ModBlocks.ITEMS.register(modEventBus);
        ModCreativeTabs.CREATIVE_MODE_TABS.register(modEventBus);
        ModBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ModMenus.MENU_TYPES.register(modEventBus);
        modEventBus.addListener(this::registerPayloads);

        if (FMLEnvironment.dist.isClient()) {
            ClientSetup.register(modEventBus);
        }
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar(ExampleMod.MOD_ID);
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
