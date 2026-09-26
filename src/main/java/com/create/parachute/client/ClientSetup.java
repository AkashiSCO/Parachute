package com.create.parachute.client;

import com.create.parachute.ParachuteMod;
import com.create.parachute.parachute.ParachuteSeatEntity;
import com.create.parachute.registry.ModBlockEntities;
import com.create.parachute.registry.ModEntities;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
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
        // 坐垫的座位实体完全不可见（外观由方块模型负责），但显式注册一个空渲染器，
        // 免得依赖"没注册渲染器时渲染分发器会跳过"这个细节。
        event.registerEntityRenderer(ModEntities.PARACHUTE_SEAT.get(), SeatRenderer::new);
    }

    /** 什么都不画的渲染器：座位实体只用于骑乘，可见外观是坐垫方块本身 */
    private static final class SeatRenderer extends EntityRenderer<ParachuteSeatEntity> {
        private static final ResourceLocation DUMMY =
                ResourceLocation.withDefaultNamespace("textures/misc/white.png");

        private SeatRenderer(EntityRendererProvider.Context context) {
            super(context);
        }

        @Override
        public void render(ParachuteSeatEntity entity, float entityYaw, float partialTick, PoseStack poseStack,
                           MultiBufferSource bufferSource, int packedLight) {
        }

        @Override
        public ResourceLocation getTextureLocation(ParachuteSeatEntity entity) {
            return DUMMY;
        }
    }
}
