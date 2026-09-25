package com.create.parachute.client.assets.obj;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.EnumSet;

/**
 * 把一段 OBJ 网格塞进原版 {@link ModelPart.Cube} 管线的桥：
 * 覆写 {@link ModelPart.Cube#compile}，直接按三角形输出顶点，从而复用现有的
 * ModelPart 树 / 开伞动画 / 染色 / 缩放 / 枢轴等一切逻辑。
 *
 * <h2>两个必须遵守的细节</h2>
 * <ul>
 *   <li><b>只能覆写 compile</b>：{@code ModelPart.Polygon} / {@code ModelPart.Vertex} 是包私有的，
 *       外部包无法构造，所以"自己拼 Polygon 数组"这条路走不通（这也是 {@code CustomUvCube} 的做法）。</li>
 *   <li><b>entity 系 RenderType 是 QUADS 模式</b>：顶点必须 4 个一组，所以每个三角形发成
 *       {@code (a, b, c, c)} 的退化四边形（第二个三角形退化成一条线，GPU 直接丢掉）。
 *       代价是顶点数 ×4/3；换来的是不需要自定义 RenderType。</li>
 * </ul>
 *
 * <h2>双面光照：{@link Backface} 决定"发几份"</h2>
 * <p>DCS 转出来的 OBJ 绕序常常不一致，薄片零件又只有一面（实测 AH-64D：98 万个三角形里
 * <b>没有任何一个</b>三角形存在绕序相反的"孪生"副本，即模型本身是单层的），所以历史上用
 * "不剔除背面"兜底。但那样薄片从背面看时顶点法线仍指原方向 → 光照上下颠倒。</p>
 *
 * <p>这里的 {@link Backface} 只管"<b>发几份几何</b>"，"<b>剔不剔除背面</b>"是渲染端的事，
 * 两者由 {@code ParachuteConfig.ShadersGeometry} 一起决定（<b>开不开光影都一样</b>，外观才一致）：</p>
 * <ul>
 *   <li>{@code SINGLE_CULL}（默认）：一份 + 剔除背面。光照一定正确 —— 被剔除的永远是不该看见的那面，
 *       画出来的片元法线必然朝向相机；代价是只有一层的薄片零件从背面看会消失。</li>
 *   <li>{@code DOUBLE}：两份 + 剔除背面。薄片两面都看得见；光影包的延迟着色只认顶点法线
 *       （例如 Photon 的 gbuffer 平面法线 {@code tbn[2] = mat3(gbufferModelViewInverse) * normalize(gl_NormalMatrix * gl_Normal)}，
 *       里面没有任何 {@code gl_FrontFacing} 处理），而一个顶点只能有一个法线，
 *       所以这是"不改光影包也能两侧都对"的做法，代价是顶点数 ×2。</li>
 *   <li>{@code SINGLE_NO_CULL}：一份 + 不剔除背面，靠着色器按 {@code gl_FrontFacing} 翻法线。
 *       无光影时本模组自己的着色器就会翻（完全无损）；开光影时得光影包自己翻
 *       （Photon 加一行即可）。</li>
 * </ul>
 * <p>半透明层固定两份 + 剔除：不剔除的话正反面片元会各混合一次，颜色会明显加深。</p>
 * <p>逐帧发射路径（{@code ObjModelBuilder}，只有低面数模型）走 {@code entityCutout} /
 * {@code entityTranslucentCull} 这些原版 RenderType，没有自有着色器，所以"一份"时只能配剔除
 * （{@code SINGLE_NO_CULL} 在这条路径上表现为剔除）。</p>
 */
public final class ObjMeshCube extends ModelPart.Cube {

    /** 背面副本的发射策略（见类注释） */
    public enum Backface {
        /** 每个三角形发两份（原绕序 + 反向绕序、法线取反），配合背面剔除做到双面光照 */
        DOUBLE,
        /** 每个三角形只发一份（OBJ 原绕序）；双面光照靠不着色器翻法线（不剔除背面时），或干脆只画正面（剔除时） */
        SINGLE
    }

    private final ObjMesh.Group group;
    private final Backface backface;
    /** 复用的临时向量：compile 每帧都在跑，绝不能在里面 new */
    private final Vector3f tmpA = new Vector3f();
    private final Vector3f tmpB = new Vector3f();

    /** 默认发两份：给逐帧发射路径用（原版 RenderType 没有自有着色器） */
    public ObjMeshCube(ObjMesh.Group group) {
        this(group, Backface.DOUBLE);
    }

    public ObjMeshCube(ObjMesh.Group group, Backface backface) {
        // 原版 Cube 的构造参数是"像素"单位（内部 /16 存成方块单位），所以这里把包围盒乘 16 传进去，
        // 让 minX..maxZ 这些 public 字段和我们的方块坐标一致。
        // visibleFaces 传空集：我们覆写了 compile，不需要父类预先造 6 个 box 面。
        super(0, 0,
                group.minX() * 16.0F, group.minY() * 16.0F, group.minZ() * 16.0F,
                (group.maxX() - group.minX()) * 16.0F,
                (group.maxY() - group.minY()) * 16.0F,
                (group.maxZ() - group.minZ()) * 16.0F,
                0.0F, 0.0F, 0.0F, false, 1.0F, 1.0F, EnumSet.noneOf(Direction.class));
        this.group = group;
        this.backface = backface;
    }

    /**
     * GPU 烘焙专用：按真正的三角形输出（{@code Mode.TRIANGLES}），每三角形 3 个顶点。
     * <p>逐帧发射路径必须走 {@link #compile}（entity 系 RenderType 是 QUADS，得补退化顶点），
     * 但烘进顶点缓冲时没有这个限制 —— 用三角形能省掉 1/4 的顶点。</p>
     */
    public void compileTriangles(PoseStack.Pose pose, VertexConsumer consumer, int packedLight,
                                 int packedOverlay, int color) {
        float[] p = this.group.positions();
        float[] uv = this.group.uvs();
        float[] n = this.group.normals();
        int corners = this.group.cornerCount();
        Matrix4f mat = pose.pose();
        boolean doubleSided = this.backface == Backface.DOUBLE;
        for (int t = 0; t + 2 < corners; t += 3) {
            // 正面副本
            emit(pose, mat, consumer, p, uv, n, t, packedLight, packedOverlay, color, false);
            emit(pose, mat, consumer, p, uv, n, t + 1, packedLight, packedOverlay, color, false);
            emit(pose, mat, consumer, p, uv, n, t + 2, packedLight, packedOverlay, color, false);
            if (doubleSided) {
                // 背面副本：绕序反向 + 法线取反（见类注释）
                emit(pose, mat, consumer, p, uv, n, t + 2, packedLight, packedOverlay, color, true);
                emit(pose, mat, consumer, p, uv, n, t + 1, packedLight, packedOverlay, color, true);
                emit(pose, mat, consumer, p, uv, n, t, packedLight, packedOverlay, color, true);
            }
        }
    }

    @Override
    public void compile(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay, int color) {
        float[] p = this.group.positions();
        float[] uv = this.group.uvs();
        float[] n = this.group.normals();
        int corners = this.group.cornerCount();
        Matrix4f mat = pose.pose();
        boolean doubleSided = this.backface == Backface.DOUBLE;

        for (int t = 0; t + 2 < corners; t += 3) {
            // 顶点顺序保持 OBJ 的原样（逆时针即正面）：ObjMesh.bake 对坐标做的是绕 X 轴 180° 旋转
            // （Y、Z 同时翻，det = +1），手性和绕序都没变。
            emit(pose, mat, consumer, p, uv, n, t, packedLight, packedOverlay, color, false);
            emit(pose, mat, consumer, p, uv, n, t + 1, packedLight, packedOverlay, color, false);
            emit(pose, mat, consumer, p, uv, n, t + 2, packedLight, packedOverlay, color, false);
            if (!doubleSided) {
                continue;
            }
            // QUADS 模式：补一个与第三个角重合的顶点 → 退化四边形
            emit(pose, mat, consumer, p, uv, n, t + 2, packedLight, packedOverlay, color, false);
            // 背面副本（绕序反向 + 法线取反）
            emit(pose, mat, consumer, p, uv, n, t + 2, packedLight, packedOverlay, color, true);
            emit(pose, mat, consumer, p, uv, n, t + 1, packedLight, packedOverlay, color, true);
            emit(pose, mat, consumer, p, uv, n, t, packedLight, packedOverlay, color, true);
            emit(pose, mat, consumer, p, uv, n, t, packedLight, packedOverlay, color, true);
        }
    }

    private void emit(PoseStack.Pose pose, Matrix4f mat, VertexConsumer consumer,
                      float[] p, float[] uv, float[] n, int corner,
                      int packedLight, int packedOverlay, int color, boolean flipNormal) {
        // 法线必须随 part 姿态变换，否则旋转后的光照是错的（与原版 Cube.compile 一致）
        this.tmpA.set(n[corner * 3], n[corner * 3 + 1], n[corner * 3 + 2]);
        if (flipNormal) {
            this.tmpA.negate();
        }
        pose.transformNormal(this.tmpA, this.tmpB);
        mat.transformPosition(p[corner * 3], p[corner * 3 + 1], p[corner * 3 + 2], this.tmpA);
        consumer.addVertex(this.tmpA.x(), this.tmpA.y(), this.tmpA.z(), color,
                uv[corner * 2], uv[corner * 2 + 1], packedOverlay, packedLight,
                this.tmpB.x(), this.tmpB.y(), this.tmpB.z());
    }
}
