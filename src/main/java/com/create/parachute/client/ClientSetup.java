package com.create.parachute.client;

import com.create.parachute.ParachuteMod;
import com.create.parachute.registry.ModBlockEntities;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

/**
 * 客户端专用初始化。
 *
 * <p>{@code @EventBusSubscriber(value = Dist.CLIENT)} 让 NeoForge 只在客户端注册本类：
 * 主类 {@link ParachuteMod} 因此不再需要引用任何客户端类（连 import 都不需要），
 * 专用服务端上本类（以及它引用的 {@code net.minecraft.client.*}）永远不会被加载。</p>
 *
 * <p>不需要（也不应该）再写 {@code bus = Bus.MOD}：NeoForge 21.1 起该属性已弃用，
 * 扫描器按监听方法的参数类型自动分流——{@link EntityRenderersEvent} 实现了
 * {@code IModBusEvent}，会被自动注册到模组事件总线。</p>
 */
@EventBusSubscriber(modid = ParachuteMod.MOD_ID, value = Dist.CLIENT)
public final class ClientSetup {
    private ClientSetup() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(
                ModBlockEntities.PARACHUTE_BLOCK_ENTITY.get(),
                ParachuteRenderer::new);
    }
}
