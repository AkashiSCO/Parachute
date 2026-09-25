package com.create.parachute.client.assets.obj;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
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
    public static ObjGpuMesh bake(List<ObjMesh.Group> groups, int packedLight, int tintColor) {
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        MeshData data = null;
        int triangles = 0;
        try (ByteBufferBuilder bytes = new ByteBufferBuilder(1 << 20)) {
            // TRIANGLES：每三角形 3 个顶点（逐帧发射路径受 RenderType 限制只能用退化 QUADS，这里不受限）
            BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.NEW_ENTITY);
            for (ObjMesh.Group group : groups) {
                if (group.cornerCount() <= 0) continue;
                // 复用现有发射逻辑；单位姿态 + 真实光照 + 染色/不透明度（都烘进顶点）
                new ObjMeshCube(group).compileTriangles(IDENTITY.last(), builder, packedLight,
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
     * @param poseStack   方块实体渲染器给的姿态（相机相关）
     * @param texture     该层当前的贴图
     * @param translucent 半透明层（translucent 着色器 + 混合 + 剔除背面）
     */
    public void draw(PoseStack poseStack, ResourceLocation texture, boolean translucent) {
        // 用本模组自己的实体着色器：唯一区别是法线会乘上 ModelViewMat 的旋转
        // （烘焙缓冲里法线是模型空间的，原版着色器原样使用它 → 旋转过的模型光照会错）。
        ShaderInstance shader = translucent ? ObjShaders.translucentCull() : ObjShaders.cutout();
        if (shader == null) return;
        RenderSystem.setShader(() -> shader);

        RenderStateShard.TransparencyStateShard transparency = translucent
                ? RenderStateShard.TRANSLUCENT_TRANSPARENCY
                : RenderStateShard.NO_TRANSPARENCY;
        // 和渲染器里的选择一致：不透明层不剔除，半透明层剔除
        RenderStateShard.CullStateShard cull = translucent
                ? RenderStateShard.CULL
                : RenderStateShard.NO_CULL;

        transparency.setupState.run();
        cull.setupState.run();
        RenderStateShard.LIGHTMAP.setupState.run();
        RenderStateShard.OVERLAY.setupState.run();
        RenderStateShard.COLOR_DEPTH_WRITE.setupState.run();
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
        this.buffer.bind();
        this.buffer.drawWithShader(modelView, RenderSystem.getProjectionMatrix(), shader);
        VertexBuffer.unbind();

        RenderStateShard.NO_LAYERING.clearState.run();
        RenderStateShard.LEQUAL_DEPTH_TEST.clearState.run();
        RenderStateShard.COLOR_DEPTH_WRITE.clearState.run();
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
