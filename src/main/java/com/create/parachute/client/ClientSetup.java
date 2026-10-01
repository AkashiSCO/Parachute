package com.create.parachute.client;

import com.create.parachute.ParachuteMod;
import com.create.parachute.parachute.ParachuteSeatEntity;
import com.create.parachute.registry.ModBlockEntities;
import com.create.parachute.registry.ModBlocks;
import com.create.parachute.registry.ModEntities;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.ItemBlockRenderTypes;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

import java.lang.reflect.Field;
import java.util.Map;

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
        // 顺带打上渲染层补丁：这个钩子一定会执行（下面的 BER 注册就靠它），
        // 比 FMLClientSetupEvent 更确定能跑起来（见 installAirBlockCutoutRenderLayer 的说明）
        installAirBlockCutoutRenderLayer("EntityRenderersEvent.RegisterRenderers");

        event.registerBlockEntityRenderer(
                ModBlockEntities.PARACHUTE_BLOCK_ENTITY.get(),
                ParachuteRenderer::new);
        // 坐垫的座位实体完全不可见（外观由方块模型负责），但显式注册一个空渲染器，
        // 免得依赖"没注册渲染器时渲染分发器会跳过"这个细节。
        event.registerEntityRenderer(ModEntities.PARACHUTE_SEAT.get(), SeatRenderer::new);
    }

    /** 第二个入口：NeoForge 的客户端初始化事件（幂等，重复调用无害）。 */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        installAirBlockCutoutRenderLayer("FMLClientSetupEvent");
    }

    /**
     * 把「空气方块」补进原版 {@code ItemBlockRenderTypes.TYPE_BY_BLOCK}，让
     * {@code ItemBlockRenderTypes.getChunkRenderType(BlockState)} 也返回 {@code cutout}。
     *
     * <h3>为什么需要这么做</h3>
     * 1.21.1（含 NeoForge 21.1.234）里运行时那个方法的实现是：
     * <pre>{@code
     * RenderType rt = TYPE_BY_BLOCK.get(block);
     * return rt != null ? rt : RenderType.solid();   // ← 自定义方块一律落到这里
     * }</pre>
     * 这张表里只有原版方块，<b>自定义方块永远拿到 {@code RenderType.solid()}</b>，
     * 模型 JSON 里的 {@code "render_type": "minecraft:cutout"} 在这个方法里被完全绕过。
     *
     * <p>而 {@code RenderType.solid()} <b>既不做 alpha 混合、也不做 alpha 测试</b>，
     * 贴图 alpha 被整个忽略：空气方块贴图是 16×16 全 {@code (255,255,255,0)}，
     * 于是所有走这条老 API 的第三方代码（典型例子：Synaxis 集成襟翼的「材质」功能，
     * 它用 {@code getParticleIcon()} 当贴图、{@code getChunkRenderType()} 当渲染层）
     * 都会把空气方块画成<b>不透明白色</b>。</p>
     *
     * <p>注意不能改用 NeoForge 的 {@code ItemBlockRenderTypes.setRenderLayer(...)}：
     * 那个方法写的是 {@code BLOCK_RENDER_TYPES}（供 {@code getRenderLayers} 用），
     * 而 {@code getChunkRenderType} 读的是 {@code TYPE_BY_BLOCK}，两者不是同一张表，
     * 所以这里只能直接往 {@code TYPE_BY_BLOCK} 里补一条。</p>
     *
     * <h3>影响范围</h3>
     * 只影响 {@code create_parachute:air_block}，且补的值（cutout）与它模型里声明的
     * {@code render_type} 一致：原本正确读取模型 render_type 的渲染路径毫无变化，
     * 只是让「按老 API 查渲染层」的代码（第三方 BER / 移动方块 / 活塞等）也拿到 cutout，
     * 从而 alpha=0 的像素被 {@code rendertype_cutout} 正确 discard。
     */
    private static void installAirBlockCutoutRenderLayer(String hook) {
        Block airBlock = ModBlocks.AIR_BLOCK.get();

        // (1) 官方 API：写 NeoForge 新增的 BLOCK_RENDER_TYPES（getRenderLayers / 模型渲染路径读这张表）
        try {
            ItemBlockRenderTypes.setRenderLayer(airBlock, RenderType.cutout());
            ParachuteMod.LOGGER.info(
                    "[create_parachute] air_block -> ItemBlockRenderTypes.setRenderLayer(cutout) ok (hook={})", hook);
        } catch (Throwable t) {
            ParachuteMod.LOGGER.warn(
                    "[create_parachute] setRenderLayer({}) 失败（hook={}）：{}", airBlock, hook, t.toString());
        }

        // (2) 老表 TYPE_BY_BLOCK：原版 getChunkRenderType 只读这张，官方 API 管不到
        try {
            Field field = ItemBlockRenderTypes.class.getDeclaredField("TYPE_BY_BLOCK");
            field.setAccessible(true);
            if (field.get(null) instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<Block, RenderType> byBlock = (Map<Block, RenderType>) raw;
                RenderType previous = byBlock.put(airBlock, RenderType.cutout());
                ParachuteMod.LOGGER.info(
                        "[create_parachute] air_block -> TYPE_BY_BLOCK=cutout (hook={}, previous={})",
                        hook, previous);
            }
        } catch (Throwable t) {
            // 原版改了字段名/结构也不该让游戏崩：空气方块在世界里依然是隐形的，
            // 只是"第三方按老 API 取渲染层"时仍会拿到 solid。
            ParachuteMod.LOGGER.warn(
                    "[create_parachute] 无法把 air_block 的 chunk 渲染层设为 cutout（hook={} 反射失败）；"
                            + "空气方块在世界里仍正常，仅部分第三方渲染器可能把它画成不透明。", hook, t);
        }
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
