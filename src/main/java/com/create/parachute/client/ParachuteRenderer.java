package com.create.parachute.client;

import com.create.parachute.ParachuteConfig;
import com.create.parachute.client.assets.ParachuteAssets;
import com.create.parachute.client.assets.ParachuteAssets.BakedParachute;
import com.create.parachute.client.assets.obj.ObjGpuMesh;
import com.create.parachute.parachute.ParachuteBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * 伞方块实体渲染器：按方块实体 NBT 里的伞名从 {@link ParachuteAssets} 取模型/动画/贴图。
 * <p>本机没有该伞时自动回退蘑菇伞（渲染是客户端的事，多人游戏缺失伞时默认显示蘑菇伞）。
 * 伞面贴图为白色底，用染料 ARGB 顶点染色。</p>
 */
public class ParachuteRenderer implements BlockEntityRenderer<ParachuteBlockEntity> {

    /** 渲染包围盒的最小膨胀量（格）：拿不到模型半径（bbmodel 等）时用这个兜底 */
    private static final double RENDER_BOX_MIN = 16.0D;

    private static final Vector3f ANIMATION_CACHE = new Vector3f();

    public ParachuteRenderer(BlockEntityRendererProvider.Context context) {
    }

    /**
     * 伞的可见距离（格），来自 {@link ParachuteConfig#VIEW_DISTANCE}（0 = 用默认 512，上限 65536）。
     *
     * <p>原版用它做 {@code Vec3.closerThan(cameraPos, (double) distance)} 判断，double 运算，
     * 所以 65536 这种大值不会溢出。真正决定能不能看见的还是区块有没有加载：伞是方块实体，
     * 客户端的区块追踪范围之外根本没有这个实体，渲染器不会被调用（服务器那边的方向同步同理，
     * 走的是 {@code sendToPlayersTrackingChunk}）。</p>
     */
    @Override
    public int getViewDistance() {
        int dist = ParachuteConfig.VIEW_DISTANCE.get();
        return dist > 0 ? dist : 512;
    }

    /**
     * <b>这才是"转身模型就消失"的真正开关。</b>
     *
     * <p>NeoForge 在 {@code LevelRenderer} 的方块实体渲染循环里插了一个可见性判断
     * （{@code ClientHooks.isBlockEntityRendererVisible}），它的实现是：</p>
     * <pre>
     *   renderer != null &amp;&amp; frustum.isVisible(renderer.getRenderBoundingBox(be))
     * </pre>
     * <p>也就是说剔除用的是<b>渲染器自己的渲染包围盒</b>，而默认值只有方块那 1x1x1 的体积。
     * 伞的模型远大于方块（AH-64D 16 格长），于是"方块一离开视野 → 整个模型消失"，
     * 而且 {@link #shouldRenderOffScreen} / {@link #shouldRender} 都不在这个判断里，
     * 怎么改都没用（实测日志证实：转身后 render() 根本不会被调用）。</p>
     *
     * <p>这里给一个覆盖整个模型的盒子（按模型自己的几何半径算，见
     * {@link ParachuteAssets.BakedParachute#renderRadius()}），等价于取消视锥剔除；真正的限制仍然是
     * "方块实体所在区块有没有加载"。拿不到半径时用 {@link #RENDER_BOX_MIN} 兜底。</p>
     */
    @Override
    public AABB getRenderBoundingBox(ParachuteBlockEntity be) {
        BakedParachute parachute = ParachuteAssets.get(be.getParachuteName());
        double radius = RENDER_BOX_MIN;
        if (parachute != null && parachute.renderRadius() > 0.0F) {
            // 模型绕枢轴旋转/摆动，所以用"球半径"（旋转无关）；再把枢轴和整体偏移算进去，保守但不会切掉模型
            radius = parachute.renderRadius() * Math.max(0.001F, be.getRenderScale())
                    + Math.abs(be.getPivotX()) + Math.abs(be.getPivotY()) + Math.abs(be.getPivotZ())
                    + Math.abs(be.getOffX()) + Math.abs(be.getOffY()) + Math.abs(be.getOffZ())
                    + 1.0D;
        }
        return new AABB(be.getBlockPos()).inflate(Math.max(RENDER_BOX_MIN, radius));
    }

    /**
     * 走原版「全局方块实体」通道（和信标光柱同一个机制），绕开按区块的 section 剔除。
     *
     * <p>配合 {@link #getRenderBoundingBox} 一起用：前者管 section 级剔除，后者管 NeoForge
     * 那个按渲染包围盒的视锥判断。</p>
     */
    @Override
    public boolean shouldRenderOffScreen(ParachuteBlockEntity be) {
        return true;
    }

    /**
     * 强制渲染：不按距离剔除。
     *
     * <p>默认实现是"距相机超过 {@link #getViewDistance()} 就不画"，这里永远返回 true。
     * 真正决定"有没有东西可画"的还是客户端区块加载：区块没加载时客户端根本没有这个方块实体。</p>
     */
    @Override
    public boolean shouldRender(ParachuteBlockEntity be, Vec3 cameraPos) {
        return true;
    }

    @Override
    public void render(ParachuteBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        renderModel(be, partialTick, poseStack, buffer, packedLight, packedOverlay);
    }

    /**
     * 真正的绘制主体：方块实体渲染器和 {@link ParachuteWorldRenderer} 的兜底绘制共用，
     * 保证两条路径画出来的东西完全一致。
     */
    public static void renderModel(ParachuteBlockEntity be, float partialTick, PoseStack poseStack,
                                   MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!be.isDeployed()) return;

        BakedParachute parachute = ParachuteAssets.get(be.getParachuteName());
        if (parachute == null || !parachute.hasTexture()) return;

        int brightLight = packedLight == 0 ? 0xF000F0 : packedLight;
        // 已染色：运行时生成的白色底贴图 + 染料 ARGB；未染色：文件夹里的原贴图（不染色）
        boolean dyed = be.isDyed();
        int color = dyed ? be.getDyeColorARGB() : -1;

        float ox = be.getFacingOffsetX();
        float oy = be.getFacingOffsetY();
        float oz = be.getFacingOffsetZ();
        float dist = be.getAttachOffset();

        float wobble = be.getWobblePhase(partialTick);
        float ratio = be.getRenderOpenRatio(partialTick);

        // 从配置读取飘动参数（大伞用大振幅，小伞用小振幅）
        float wobbleXAmp = ParachuteConfig.WOBBLE_X_AMP.get().floatValue();
        float wobbleXFreq = ParachuteConfig.WOBBLE_X_FREQ_MULT.get().floatValue();
        float wobbleZFreq = ParachuteConfig.WOBBLE_Z_FREQ_MULT.get().floatValue();
        String id = be.getParachuteName();
        boolean big = id != null && id.toLowerCase(java.util.Locale.ROOT).contains("big");
        float wobbleZAmp = (big ? ParachuteConfig.WOBBLE_Z_AMP_BIG : ParachuteConfig.WOBBLE_Z_AMP_SMALL).get().floatValue();

        poseStack.pushPose();
        poseStack.translate(0.5f + ox * dist, 0.5f + oy * dist, 0.5f + oz * dist);
        // 整体偏移：整体平移（旋转前施加，输入多少移多少）
        poseStack.translate(be.getOffX(), be.getOffY(), be.getOffZ());
        // 锁定：伞固定——不跟随速度方向、不自摆动，朝向按放置面方向（避免头朝下）
        boolean locked = be.isWobbleLocked();
        if (locked) {
            poseStack.mulPose(be.getLockedQuat());
        } else {
            poseStack.mulPose(be.getRenderQuat(partialTick));
            poseStack.mulPose(Axis.XP.rotationDegrees((float) Math.sin(wobble * wobbleXFreq) * wobbleXAmp));
            poseStack.mulPose(Axis.ZP.rotationDegrees((float) Math.cos(wobble * wobbleZFreq) * wobbleZAmp));
        }
        // 用户设置的旋转（自摆动坐标系）
        poseStack.mulPose(Axis.XP.rotationDegrees(be.getRotX()));
        poseStack.mulPose(Axis.YP.rotationDegrees(be.getRotY()));
        poseStack.mulPose(Axis.ZP.rotationDegrees(be.getRotZ()));
        // 枢轴点：模型位置偏移（旋转后施加，随旋转绕附着点摆动，输入多少移多少）
        poseStack.translate(be.getPivotX(), be.getPivotY(), be.getPivotZ());
        // 整体缩放：在枢轴平移之后施加，等价于「以枢轴点（= 模型自身原点）为中心」缩放
        float scale = be.getRenderScale();
        if (scale != 1.0F) {
            poseStack.scale(scale, scale, scale);
        }

        // 有动画才驱动开伞动画；无动画直接显示模型
        if (parachute.openAnimation() != null) {
            ModelPart root = parachute.root();
            if (root != null) {
                ParachuteAssets.applyOpenAnimation(root, parachute.openAnimation(), ratio, ANIMATION_CACHE);
            }
        }
        // 逐层渲染：单贴图模型只有一层（等价于以前的一次 render），多材质 OBJ 每张贴图一层。
        // 光影包状态每帧只探测一次，供这一帧所有层共用 —— 探测结果要和烘 GPU 缓冲时一致
        // （两者不一致的那一帧会画错：一份的几何配剔除、或两份的几何配不剔除）。
        boolean shaderPack = ShaderPackCompat.shaderPackInUse();
        for (ParachuteAssets.Layer layer : parachute.layers()) {
            ResourceLocation layerTex = dyed
                    ? (layer.whiteTexture() != null ? layer.whiteTexture() : layer.texture())
                    : layer.texture();
            if (layerTex == null) {
                continue;
            }
            // mtl 的 d（整体不透明度）用顶点 alpha 表达
            int layerColor = color;
            if (layer.alpha() < 1.0F) {
                int a = Math.round(((color >>> 24) & 0xFF) * layer.alpha());
                layerColor = (color & 0x00FFFFFF) | (a << 24);
            }
            if (layer.gpu() != null) {
                // 高面数模型：顶点常驻显存，这里只 bind + draw（光照/染色烘在缓冲里，行为和逐帧发射一致）。
                // 几何份数 / 是否剔除背面（见 ObjMeshCube.Backface 与 ParachuteConfig.ShadersGeometry）：
                //   半透明层 → 固定两份 + 剔除（不剔除的话正反面片元各混合一次，颜色会明显加深）
                //   不透明层 → 按配置，**开不开光影都一样**（这样两种情况下外观一致）：
                //              SINGLE_CULL（默认）一份 + 剔除；DOUBLE 两份 + 剔除；SINGLE_NO_CULL 一份 + 不剔除
                boolean duplicate;
                boolean cull;
                if (layer.translucent()) {
                    duplicate = true;
                    cull = true;
                } else {
                    ParachuteConfig.ShadersGeometry mode = ParachuteConfig.SHADERS_GEOMETRY.get();
                    duplicate = mode == ParachuteConfig.ShadersGeometry.DOUBLE;
                    cull = mode != ParachuteConfig.ShadersGeometry.SINGLE_NO_CULL;
                }
                layer.gpu().get(brightLight, layerColor, duplicate)
                        .draw(poseStack, layerTex, layer.translucent(), shaderPack, cull);
                continue;
            }
            if (layer.model() == null) {
                continue;
            }
            // 逐帧发射路径（低面数模型）：几何份数同样按配置（见 ParachuteAssets 里的 opaqueBackface），
            // 这里只负责渲染类型 —— 两类都在**剔除背面**：
            //   两份几何时，被剔除的永远是不该看见的那一面（DCS 绕序不一致、薄片单面都被消化掉了）；
            //   一份几何时，剔除保证"画出来的片元法线一定朝向相机"，光照必然正确（薄片背面会消失）。
            // 这条路径用的是原版 RenderType，没有自有着色器，所以拿不到 gl_FrontFacing 那条更省的做法。
            // 半透明层本来就该剔除（玻璃球罩不剔除时正/背面与内层会叠在一起，看着像一堆三角锯齿）。
            VertexConsumer layerVc = buffer.getBuffer(layer.translucent()
                    ? RenderType.entityTranslucentCull(layerTex)
                    : RenderType.entityCutout(layerTex));
            layer.model().render(poseStack, layerVc, brightLight, packedOverlay, layerColor);
        }

        poseStack.popPose();
    }
}
