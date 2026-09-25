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
 * <p>绕序不用管：渲染器用的是 {@code entityCutoutNoCull}（不背面剔除），
 * 所以 Blender 在镜像变换下反转绕序的行为对我们没有影响。</p>
 */
public final class ObjMeshCube extends ModelPart.Cube {

    private final ObjMesh.Group group;
    /** 复用的临时向量：compile 每帧都在跑，绝不能在里面 new */
    private final Vector3f tmpA = new Vector3f();
    private final Vector3f tmpB = new Vector3f();

    public ObjMeshCube(ObjMesh.Group group) {
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
    }

    /**
     * GPU 烘焙专用：按真正的三角形输出（{@code Mode.TRIANGLES}），每三角形 3 个顶点。
     * <p>逐帧发射路径必须走 {@link #compile}（entity 系 RenderType 是 QUADS，得补退化顶点），
     * 但烘进顶点缓冲时没有这个限制 —— 用三角形能省掉 1/4 的顶点（980k 三角形少 98 万顶点）。</p>
     */
    public void compileTriangles(PoseStack.Pose pose, VertexConsumer consumer, int packedLight,
                                 int packedOverlay, int color) {
        float[] p = this.group.positions();
        float[] uv = this.group.uvs();
        float[] n = this.group.normals();
        int corners = this.group.cornerCount();
        Matrix4f mat = pose.pose();
        for (int t = 0; t + 2 < corners; t += 3) {
            emit(pose, mat, consumer, p, uv, n, t, packedLight, packedOverlay, color);
            emit(pose, mat, consumer, p, uv, n, t + 1, packedLight, packedOverlay, color);
            emit(pose, mat, consumer, p, uv, n, t + 2, packedLight, packedOverlay, color);
        }
    }

    @Override
    public void compile(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay, int color) {
        float[] p = this.group.positions();
        float[] uv = this.group.uvs();
        float[] n = this.group.normals();
        int corners = this.group.cornerCount();
        Matrix4f mat = pose.pose();

        for (int t = 0; t + 2 < corners; t += 3) {
            // 顶点顺序保持 OBJ 的原样（逆时针即正面）：
            // ObjMesh.bake 对坐标做的是绕 X 轴 180° 旋转（Y、Z 同时翻，det = +1），
            // 手性和绕序都没变，所以这里不需要再翻转。
            // （早期版本只翻 Y 造成镜像，才用 (a,c,b,b) 把绕序翻回去补偿 —— 那是治标。）
            emit(pose, mat, consumer, p, uv, n, t, packedLight, packedOverlay, color);
            emit(pose, mat, consumer, p, uv, n, t + 1, packedLight, packedOverlay, color);
            emit(pose, mat, consumer, p, uv, n, t + 2, packedLight, packedOverlay, color);
            // QUADS 模式：补一个与第三个角重合的顶点 → 退化四边形
            emit(pose, mat, consumer, p, uv, n, t + 2, packedLight, packedOverlay, color);
        }
    }

    private void emit(PoseStack.Pose pose, Matrix4f mat, VertexConsumer consumer,
                      float[] p, float[] uv, float[] n, int corner,
                      int packedLight, int packedOverlay, int color) {
        // 法线必须随 part 姿态变换，否则旋转后的光照是错的（与原版 Cube.compile 一致）
        this.tmpA.set(n[corner * 3], n[corner * 3 + 1], n[corner * 3 + 2]);
        pose.transformNormal(this.tmpA, this.tmpB);
        mat.transformPosition(p[corner * 3], p[corner * 3 + 1], p[corner * 3 + 2], this.tmpA);
        consumer.addVertex(this.tmpA.x(), this.tmpA.y(), this.tmpA.z(), color,
                uv[corner * 2], uv[corner * 2 + 1], packedOverlay, packedLight,
                this.tmpB.x(), this.tmpB.y(), this.tmpB.z());
    }
}
