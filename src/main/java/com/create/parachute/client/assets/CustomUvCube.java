package com.create.parachute.client.assets;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.core.Direction;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.EnumSet;

/**
 * 逐面 UV 方块：继承原版 {@link ModelPart.Cube}，覆写 {@code compile}，
 * 用每面独立的 UV 矩形（基岩版 .bbmodel 的 {@code faces} 逐面 UV）输出顶点。
 *
 * <p>顶点顺序与 UV 角映射与原版 Cube/Polygon 完全一致：
 * 每面 4 个顶点 c0..c3 依次取 (u1,v0)、(u0,v0)、(u0,v1)、(u1,v1)，
 * UV 除以贴图宽高归一化。面顺序固定为
 * DOWN(0) UP(1) WEST(2) NORTH(3) EAST(4) SOUTH(5)。</p>
 */
public class CustomUvCube extends ModelPart.Cube {

    private static final Direction[] FACE_DIRS = {
            Direction.DOWN, Direction.UP, Direction.WEST, Direction.NORTH, Direction.EAST, Direction.SOUTH
    };

    private final float[] u0, v0, u1, v1;
    private final boolean[] valid;
    private final float texW, texH;

    public CustomUvCube(int texOffU, int texOffV, float x, float y, float z, float sx, float sy, float sz,
                        float growX, float growY, float growZ, boolean mirror, float texW, float texH,
                        float[] u0, float[] v0, float[] u1, float[] v1, boolean[] valid) {
        super(texOffU, texOffV, x, y, z, sx, sy, sz, growX, growY, growZ, mirror, texW, texH,
                EnumSet.allOf(Direction.class));
        this.u0 = u0;
        this.v0 = v0;
        this.u1 = u1;
        this.v1 = v1;
        this.valid = valid;
        this.texW = texW;
        this.texH = texH;
    }

    @Override
    public void compile(PoseStack.Pose pose, VertexConsumer consumer, int packedLight, int packedOverlay, int color) {
        Matrix4f mat = pose.pose();
        float minX = this.minX, minY = this.minY, minZ = this.minZ;
        float maxX = this.maxX, maxY = this.maxY, maxZ = this.maxZ;
        Vector3f v7 = new Vector3f(minX, minY, minZ);
        Vector3f v0p = new Vector3f(maxX, minY, minZ);
        Vector3f v1p = new Vector3f(maxX, maxY, minZ);
        Vector3f v2p = new Vector3f(minX, maxY, minZ);
        Vector3f v3p = new Vector3f(minX, minY, maxZ);
        Vector3f v4p = new Vector3f(maxX, minY, maxZ);
        Vector3f v5p = new Vector3f(maxX, maxY, maxZ);
        Vector3f v6p = new Vector3f(minX, maxY, maxZ);
        // 每面顶点顺序与原版 Cube 一致：c0←(u1,v0) c1←(u0,v0) c2←(u0,v1) c3←(u1,v1)
        Vector3f[][] corners = {
                {v4p, v3p, v7, v0p},    // DOWN
                {v1p, v2p, v6p, v5p},   // UP
                {v7, v3p, v6p, v2p},    // WEST
                {v0p, v7, v2p, v1p},    // NORTH
                {v4p, v0p, v1p, v5p},   // EAST
                {v3p, v4p, v5p, v6p}    // SOUTH
        };
        // 顶点顺序固定（BlockBench mirror_uv 只翻贴图，几何/绕序/法线不变）
        for (int f = 0; f < 6; f++) {
            // 未贴图的面（texture:null / 无 uv）不输出顶点
            if (f >= this.valid.length || !this.valid[f]) continue;
            float u0n = this.u0[f] / this.texW;
            float v0n = this.v0[f] / this.texH;
            float u1n = this.u1[f] / this.texW;
            float v1n = this.v1[f] / this.texH;
            // 逐面 UV（BlockBench 预览 updateUV 逐面分支，mirror_uv 忽略）：
            // 面角 k ← (u0,v0),(u1,v0),(u0,v1),(u1,v1)，再按游戏盒 X/Y 翻转映射到槽位。
            // DOWN/UP 槽位物理对应 authored up/down 面，角序差 180°；
            // WEST/NORTH/EAST/SOUTH 槽位对应 authored east/north/west/south，角序为原版。
            boolean base180 = f == 0 || f == 1; // DOWN, UP
            float[][] uv = base180
                    ? new float[][]{{u0n, v1n}, {u1n, v1n}, {u1n, v0n}, {u0n, v0n}} // 180°
                    : new float[][]{{u1n, v0n}, {u0n, v0n}, {u0n, v1n}, {u1n, v1n}}; // 原版
            Vector3f dir = FACE_DIRS[f].step();
            // 法线必须随 part 姿态变换（与原版 Cube.compile 一致），否则旋转后的光照错误
            Vector3f normal = pose.transformNormal(dir, new Vector3f());
            float nx = normal.x();
            float ny = normal.y();
            float nz = normal.z();
            for (int k = 0; k < 4; k++) {
                Vector3f corner = corners[f][k];
                Vector3f out = mat.transformPosition(
                        corner.x() / 16.0F, corner.y() / 16.0F, corner.z() / 16.0F, new Vector3f());
                consumer.addVertex(out.x(), out.y(), out.z(), color,
                        uv[k][0], uv[k][1], packedOverlay, packedLight, nx, ny, nz);
            }
        }
    }
}
