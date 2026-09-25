package com.create.parachute.client.assets.obj;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.List;

/**
 * 把一层 OBJ 网格<b>烘焙进 GPU 缓冲</b>：上传一次，之后每帧只 bind + draw。
 *
 * <p>这是"Blender 那种不卡"的做法：顶点常驻显存，每帧只改模型矩阵（uniform），
 * 不在 CPU 上重新组装顶点。原路径每帧要通过 {@code VertexConsumer} 写 4×三角形 个顶点，
 * 98 万三角形 ≈ 390 万顶点/帧，CPU 上百毫秒；烘焙后 CPU 成本 O(1)。</p>
 *
 * <h2>为什么缓冲要按光照值分组</h2>
 * <p>MC 的实体着色器按<b>逐顶点 UV2</b> 采样光照贴图，缓冲里的光照是烘死的。为了"渲染结果不变"
 * （而不是烘成满亮），这里按 (光照, 染色) 组合各烘一份，由 {@link ObjGpuCache} 缓存；
 * 光照量化到 4 级 —— 白天(15)/夜里(4)光照稳定时不会重烘，只有日出日落这种渐变时才会重烘几次。</p>
 *
 * <p>渲染状态直接用原版 {@link RenderStateShard} 的 shard（1.21 里 {@code RenderType.setupRenderState}
 * 不是 public），保证和 {@code entityCutoutNoCull} / {@code entityTranslucentCull} 一致；
 * 顶点烘焙时用单位姿态，实例变换（位置/朝向/缩放/摆动）通过 ModelViewMat 施加。</p>
 *
 * <h2>几何份数：由 ShadersGeometry 配置统一决定</h2>
 * <p>顶点数就是这里的全部成本。每个不透明层烘一份还是两份、绘制时剔不剔除背面，
 * 由 {@code ParachuteConfig.ShadersGeometry} 决定（<b>开不开光影都一样</b>，见
 * {@link ObjMeshCube.Backface}）：SINGLE_CULL = 一份 + 剔除（默认），DOUBLE = 两份 + 剔除，
 * SINGLE_NO_CULL = 一份 + 不剔除（靠着色器按 {@code gl_FrontFacing} 翻法线）。
 * 半透明层固定两份 + 剔除。同一层在"份数"变化时要整批重烘，这件事由 {@link ObjGpuCache}
 * 负责（剔除与否不必重烘）。</p>
 *
 * <h2>和逐帧路径必须逐项对齐的三处（都踩过坑）</h2>
 * <ul>
 *   <li><b>packedLight 是 32 位</b>（低 16 位方块光、高 16 位天空光，白天 0xF000F0）。
 *       按 16 位 clamp 会把它压成 0xFFFF，{@code texelFetch(Sampler2, UV2/16)} 坐标越界
 *       返回全 0 → 模型全黑；量化必须两半分别做。</li>
 *   <li><b>packedOverlay 要用 {@link OverlayTexture#NO_OVERLAY}</b>，不能传 0：
 *       0 会让 {@code texelFetch(Sampler1, UV1)} 采到覆盖层贴图的别的纹素，整模型被染色。</li>
 *   <li><b>贴图过滤状态</b>：{@code ParachuteTexture} 自己设的是"线性 + 无 mip"。
 *       如果开着 mipmap，缩小的片元会采到 prepareImage 分配但从未写入的空 mip 层级
 *       （全透明）→ cutout 的 {@code alpha < 0.1} 把大片几何丢弃，看起来只剩几根细杆。</li>
 *   <li><b>深度测试必须显式打开</b>（{@code LEQUAL_DEPTH_TEST}）：原版每个 RenderType 的
 *       CompositeState 都带这一项。只开 {@code COLOR_DEPTH_WRITE} 而不开测试，
 *       所有片元一律通过 → 后画的内部结构盖住先画的外壳，看到的就是"飞机的内构"。
 *       同理也要清掉多边形偏移（{@code NO_LAYERING}），否则深度被整体偏移会出现同样的现象。</li>
 * </ul>
 */
public final class ObjGpuMesh implements AutoCloseable {

    private final VertexBuffer buffer;
    private final int triangles;

    private ObjGpuMesh(VertexBuffer buffer, int triangles) {
        this.buffer = buffer;
        this.triangles = triangles;
    }

    public int triangles() {
        return this.triangles;
    }

    /** 必须在渲染线程调用（会创建/上传 GL 缓冲）：把一个层里的所有组烘进同一个缓冲 */
    public static ObjGpuMesh bake(List<ObjMesh.Group> groups, int packedLight, int tintColor,
                                  ObjMeshCube.Backface backface) {
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        MeshData data = null;
        int triangles = 0;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 20)) {
            // TRIANGLES：每三角形 3 个顶点（逐帧发射路径受 RenderType 限制只能用退化 QUADS，这里不受限）
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
            for (ObjMesh.Group group : groups) {
                if (group.cornerCount() <= 0) continue;
                // 复用现有发射逻辑；单位姿态 + 真实光照 + 染色/不透明度（都烘进顶点）
                new ObjMeshCube(group, backface).compileTriangles(IDENTITY.last(), builder, packedLight,
                        OverlayTexture.NO_OVERLAY, tintColor);
                triangles += group.cornerCount() / 3;
            }
            data = builder.buildOrThrow();
            buffer.bind();
            buffer.upload(data);
            VertexBuffer.unbind();
        } finally {
            if (data != null) {
                data.close();
            }
        }
        return new ObjGpuMesh(buffer, triangles);
    }

    /**
     * 画这一层（光照和染色已经烘在缓冲里，这里只管状态 + 矩阵 + draw）。
     *
     * @param poseStack         方块实体渲染器给的姿态（相机相关）
     * @param texture           该层当前的贴图
     * @param translucent       半透明层（translucent 着色器 + 混合 + 剔除背面）
     * @param shaderPackInUse   当前有没有光影包在生效（必须和烘这份缓冲时的判断一致）
     * @param cullBackfaces     要不要剔除背面。缓冲里是"每三角形两份"时开剔除（被剔除的那份永远是不该看见的
     *                          那面）；只有一份时，无光影路径必须不剔除（背面片元由自有着色器翻法线），
     *                          光影路径两种都行 —— 剔除则光照一定正确但薄片背面会消失（见 ParachuteConfig.ShadersGeometry）
     */
    public void draw(PoseStack poseStack, ResourceLocation texture, boolean translucent,
                     boolean shaderPackInUse, boolean cullBackfaces) {
        // 正常情况下用本模组自己的实体着色器：唯一区别是法线会乘上物体姿态矩阵
        // （烘焙缓冲里法线是模型空间的，原版着色器原样使用它 → 旋转过的模型光照会错），
        // 并且背面片元会用 gl_FrontFacing 把法线翻回来（这份几何只有一份，不剔除背面）。
        //
        // 但**开了光影（Iris）时必须换回原版着色器实例**：Iris 只接管原版程序的绘制，
        // 自定义 ShaderInstance 不在它的管线里 —— 那样一开光影包模型就整个消失。
        // 原版实例会被 Iris 替换成它自己的程序，于是这条烘焙路径也能正常走光影管线；
        // 光影包的延迟着色只认顶点法线（Photon：gbuffer 平面法线 = tbn[2]，完全来自 gl_Normal），
        // 所以"两份几何"还是"一份+改光影包"由配置决定（ParachuteConfig.ShadersGeometry）。
        ShaderInstance shader;
        if (shaderPackInUse) {
            shader = translucent
                    ? GameRenderer.getRendertypeEntityTranslucentCullShader()
                    : GameRenderer.getRendertypeEntityCutoutNoCullShader();
        } else {
            shader = translucent ? ObjShaders.translucentCull() : ObjShaders.cutout();
        }
        if (shader == null) return;
        RenderSystem.setShader(() -> shader);

        RenderStateShard.TransparencyStateShard transparency = translucent
                ? RenderStateShard.TRANSLUCENT_TRANSPARENCY
                : RenderStateShard.NO_TRANSPARENCY;
        RenderStateShard.CullStateShard cull = cullBackfaces ? RenderStateShard.CULL : RenderStateShard.NO_CULL;

        transparency.setupState.run();
        cull.setupState.run();
        RenderStateShard.LIGHTMAP.setupState.run();
        RenderStateShard.OVERLAY.setupState.run();
        // 写掩码必须和 RenderType 一致：不透明层 COLOR_DEPTH_WRITE，半透明层只用 COLOR_WRITE。
        // 原版 entityTranslucentCull 就是不写深度的（半透明靠混合叠加），这里如果跟着写深度，
        // 半透明层会把它后面的东西以及另一层半透明挡掉 —— 看起来就是"这一层明暗/光照不对"。
        RenderStateShard.WriteMaskStateShard writeMask = translucent
                ? RenderStateShard.COLOR_WRITE
                : RenderStateShard.COLOR_DEPTH_WRITE;
        writeMask.setupState.run();
        // 深度测试必须显式打开：原版每个 RenderType 的 CompositeState 都带
        // setDepthTestState(LEQUAL_DEPTH_TEST)。只开深度写入而不开测试，所有片元都会通过，
        // 后画的内部结构会盖住先画的外壳 —— 表现就是"能直接看到飞机内构"。
        RenderStateShard.LEQUAL_DEPTH_TEST.setupState.run();
        // 多边形偏移也要清掉：原版 CompositeState 默认 NO_LAYERING，
        // 如果被别的 pass（描边等）泄漏过来，模型深度会被整体偏移，同样会看到内构。
        RenderStateShard.NO_LAYERING.setupState.run();
        RenderSystem.setShaderTexture(0, texture);
        // 光照/染色都烘在顶点里了，别再叠一层颜色调制
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);

        Matrix4fStack stack = RenderSystem.getModelViewStack();
        Matrix4f modelView = new Matrix4f(stack).mul(poseStack.last().pose());
        // 法线矩阵：**只含物体姿态**（不含相机旋转），和原版 CPU 端 pose.transformNormal 的语义一致。
        // 烘焙缓冲里的法线是模型空间的，而 MC 的 Light0/Light1_Direction 是世界空间的，
        // 必须用姿态矩阵转到世界空间；用 ModelViewMat 会连相机一起转进去 → 光照跟着镜头跑。
        // 只有自有着色器声明了这个 uniform；换成原版实例（Iris）时它不存在，设了也没用。
        if (!shaderPackInUse) {
            shader.safeGetUniform("ObjNormalMat").set(poseStack.last().pose());
        }
        this.buffer.bind();
        this.buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), shader);
        VertexBuffer.unbind();

        RenderStateShard.NO_LAYERING.clearState.run();
        RenderStateShard.LEQUAL_DEPTH_TEST.clearState.run();
        writeMask.clearState.run();
        RenderStateShard.OVERLAY.clearState.run();
        RenderStateShard.LIGHTMAP.clearState.run();
        cull.clearState.run();
        transparency.clearState.run();
    }

    @Override
    public void close() {
        this.buffer.close();
    }

    /** 烘焙时用的单位姿态 */
    private static final PoseStack IDENTITY = new PoseStack();
}
