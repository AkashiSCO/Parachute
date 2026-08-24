package com.create.parachute.client;

import com.create.parachute.ParachuteConfig;
import com.create.parachute.ExampleMod;
import com.create.parachute.client.assets.ParachuteAssets;
import com.create.parachute.client.assets.ParachuteAssets.BakedParachute;
import com.create.parachute.parachute.ParachuteBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Vector3f;

/**
 * 伞方块实体渲染器：按方块实体 NBT 里的伞名从 {@link ParachuteAssets} 取模型/动画/贴图。
 * <p>本机没有该伞时自动回退蘑菇伞（渲染是客户端的事，多人游戏缺失伞时默认显示蘑菇伞）。
 * 伞面贴图为白色底，用染料 ARGB 顶点染色。</p>
 */
public class ParachuteRenderer implements BlockEntityRenderer<ParachuteBlockEntity> {

    private final Vector3f animationCache = new Vector3f();

    /** [TEMP-DEBUG lav25] 渲染矩阵输出计数 */
    private int debugCount = 0;

    public ParachuteRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public int getViewDistance() {
        int dist = ParachuteConfig.VIEW_DISTANCE.get();
        return dist > 0 ? dist : 512;
    }

    @Override
    public void render(ParachuteBlockEntity be, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffer, int packedLight, int packedOverlay) {
        if (!be.isDeployed()) return;

        BakedParachute parachute = ParachuteAssets.get(be.getParachuteName());
        if (parachute == null || parachute.texture() == null) return;

        int brightLight = packedLight == 0 ? 0xF000F0 : packedLight;
        // 已染色：运行时生成的白色底贴图 + 染料 ARGB；未染色：文件夹里的原贴图（不染色）
        boolean dyed = be.isDyed();
        ResourceLocation tex = dyed
                ? (parachute.whiteTexture() != null ? parachute.whiteTexture() : parachute.texture())
                : parachute.texture();
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

        VertexConsumer vc = buffer.getBuffer(RenderType.entityCutoutNoCull(tex));
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

        // 有动画才驱动开伞动画；无动画直接显示模型
        if (parachute.openAnimation() != null) {
            ParachuteAssets.applyOpenAnimation(
                    parachute.root(), parachute.openAnimation(), ratio, this.animationCache);
        }
        // [TEMP-DEBUG lav25] 输出模型到世界的变换矩阵，用于确定正确坐标约定
        if (id != null && id.toLowerCase(java.util.Locale.ROOT).contains("lav25") && debugCount < 3) {
            debugCount++;
            org.joml.Matrix4f pm = poseStack.last().pose();
            ExampleMod.LOGGER.info("[lav25dbg] pose m00={} m01={} m02={} m03={} | m10={} m11={} m12={} m13={} | m20={} m21={} m22={} m23={}",
                    pm.m00(), pm.m01(), pm.m02(), pm.m03(), pm.m10(), pm.m11(), pm.m12(), pm.m13(), pm.m20(), pm.m21(), pm.m22(), pm.m23());
            ExampleMod.LOGGER.info("[lav25dbg] renderQuat={} lockedQuat={} userRot=({},{},{}) wobbleLocked={}",
                    be.getRenderQuat(partialTick), be.getLockedQuat(), be.getRotX(), be.getRotY(), be.getRotZ(), be.isWobbleLocked());
        }
        parachute.root().render(poseStack, vc, brightLight, packedOverlay, color);
        poseStack.popPose();
    }
}
