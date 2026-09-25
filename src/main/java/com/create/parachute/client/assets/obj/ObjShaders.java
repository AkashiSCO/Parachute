package com.create.parachute.client.assets.obj;

import com.create.parachute.ParachuteMod;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterShadersEvent;

import java.io.IOException;

/**
 * OBJ 烘焙路径专用的实体着色器。
 *
 * <h2>为什么需要自己的着色器</h2>
 * <p>原版 {@code rendertype_entity_cutout_no_cull.vsh} 里位置走 {@code ModelViewMat}，
 * 但法线是<b>原样使用</b>的：</p>
 * <pre>
 *   gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
 *   vertexColor = minecraft_mix_light(Light0_Direction, Light1_Direction, Normal, Color);
 * </pre>
 * <p>也就是说 MC 是每帧在 CPU 上把法线转成世界/相机相对空间再写进顶点的
 * （{@code ModelPart.Cube.compile} 里的 {@code pose.transformNormal}）。
 * 顶点一旦烘进 GPU 缓冲，法线只能停在模型空间 —— 于是旋转过的模型光照会错
 * （看起来像"法线反了/更黑"）。</p>
 *
 * <p>本类的两份着色器就是原版那两个文件，只把法线换成
 * {@code normalize(mat3(ModelViewMat) * Normal)}：把"每帧在 CPU 转法线"这一步挪到 GPU 顶点着色器里，
 * 结果和动态路径（{@code pose.transformNormal}）一致 —— 模型缩放是均匀的，所以
 * {@code mat3} 与法线矩阵等价，最后归一化补齐长度。其余代码、uniform、采样器都原样保留。</p>
 */
@EventBusSubscriber(modid = ParachuteMod.MOD_ID, value = Dist.CLIENT, bus = EventBusSubscriber.Bus.MOD)
public final class ObjShaders {

    private static final String CUTOUT = "obj_entity_cutout_no_cull";
    private static final String TRANSLUCENT = "obj_entity_translucent_cull";

    private static ShaderInstance cutout;
    private static ShaderInstance translucentCull;

    private ObjShaders() {
    }

    @SubscribeEvent
    public static void onRegisterShaders(RegisterShadersEvent event) throws IOException {
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                        ResourceLocation.fromNamespaceAndPath(ParachuteMod.MOD_ID, CUTOUT),
                        DefaultVertexFormat.NEW_ENTITY),
                shader -> cutout = shader);
        event.registerShader(new ShaderInstance(event.getResourceProvider(),
                        ResourceLocation.fromNamespaceAndPath(ParachuteMod.MOD_ID, TRANSLUCENT),
                        DefaultVertexFormat.NEW_ENTITY),
                shader -> translucentCull = shader);
    }

    /** 不透明层用的（对应 {@code entityCutoutNoCull}） */
    public static ShaderInstance cutout() {
        return cutout;
    }

    /** 半透明层用的（对应 {@code entityTranslucentCull}） */
    public static ShaderInstance translucentCull() {
        return translucentCull;
    }
}
