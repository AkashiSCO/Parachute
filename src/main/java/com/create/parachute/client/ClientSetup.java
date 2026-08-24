package com.create.parachute.client;

import com.create.parachute.registry.ModBlockEntities;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

public final class ClientSetup {
    private ClientSetup() {
    }

    public static void register(IEventBus modEventBus) {
        modEventBus.addListener(ClientSetup::registerRenderers);
    }

    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                ModBlockEntities.PARACHUTE_BLOCK_ENTITY.get(),
                ParachuteRenderer::new);
    }
}
