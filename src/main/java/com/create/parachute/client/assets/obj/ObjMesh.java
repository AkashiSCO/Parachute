package com.create.parachute.client.assets.obj;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 烘焙好的 OBJ 网格：按 {@code o}（没有则 {@code g}）分组，每组是<b>按角展开</b>的扁平数组。
 *
 * <p>一个"角"= 一个顶点引用；3 个角构成一个三角形（{@code cornerCount} 一定是 3 的倍数）。
 * 展开而不是用索引，是为了渲染时 {@code compile()} 里零分配、顺序读取 —— 代价是顶点不共享，
 * 但 Blender 导出的 OBJ 本来就是逐角 UV/法线，共享与否对显存没有意义。</p>
 *
 * <p>这里做完的事：单位缩放（烘进坐标）、OBJ→MC 的 V 翻转、法线生成（文件有 vn 用文件，
 * 否则按 {@code s} 决定平面/平滑）。</p>
 */
public final class ObjMesh {

    /** 一个组 = (材质, 对象) 组合。{@link Group#name()} 是对象名（骨骼名），{@link Group#material()} 是材质名（决定贴图） */
    public static final class Group {
        private final String name;
        private final String material;
        private final float[] positions;
        private final float[] uvs;
        private final float[] normals;
        private final int cornerCount;
        private final float minX;
        private final float minY;
        private final float minZ;
        private final float maxX;
        private final float maxY;
        private final float maxZ;

        Group(String name, String material, float[] positions, float[] uvs, float[] normals, int cornerCount,
              float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
            this.name = name;
            this.material = material;
            this.positions = positions;
            this.uvs = uvs;
            this.normals = normals;
            this.cornerCount = cornerCount;
            this.minX = minX;
            this.minY = minY;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxY = maxY;
            this.maxZ = maxZ;
        }

        public String name() {
            return this.name;
        }

        public String material() {
            return this.material;
        }

        public float[] positions() {
            return this.positions;
        }

        public float[] uvs() {
            return this.uvs;
        }

        public float[] normals() {
            return this.normals;
        }

        public int cornerCount() {
            return this.cornerCount;
        }

        public float minX() {
            return this.minX;
        }

        public float minY() {
            return this.minY;
        }

        public float minZ() {
            return this.minZ;
        }

        public float maxX() {
            return this.maxX;
        }

        public float maxY() {
            return this.maxY;
        }

        public float maxZ() {
            return this.maxZ;
        }
    }

    private final List<Group> groups;
    private final int triangleCount;
    private final int cornerCount;
    private final int droppedFaces;
    private final int ngonFaces;

    private ObjMesh(List<Group> groups, int triangleCount, int cornerCount, int droppedFaces, int ngonFaces) {
        this.groups = groups;
        this.triangleCount = triangleCount;
        this.cornerCount = cornerCount;
        this.droppedFaces = droppedFaces;
        this.ngonFaces = ngonFaces;
    }

    public List<Group> groups() {
        return this.groups;
    }

    public int triangleCount() {
        return this.triangleCount;
    }

    public int cornerCount() {
        return this.cornerCount;
    }

    public int droppedFaces() {
        return this.droppedFaces;
    }

    public int ngonFaces() {
        return this.ngonFaces;
    }

    // ============================================================
    // 烘焙
    // ============================================================

    /**
     * 把解析结果烘焙成可直接渲染的扁平网格。
     *
     * @param unitScale OBJ 单位 → 方块（Blender 默认 1 unit = 1 m = 1 格，所以是 1.0）
     */
    public static ObjMesh bake(ObjParser.ObjData d, float unitScale) {
        final int tris = d.triangleCount;
        if (tris <= 0) {
            return new ObjMesh(List.of(), 0, 0, d.droppedFaces, d.ngonFaces);
        }

        // ---- 1. 分组：按 (材质, 对象) 切 ----
        //   对象名 → ModelPart 子节点名（骨骼名）；单贴图模型的结构和以前完全一样
        //   材质名 → 决定用哪张贴图；调用方会把用同一张贴图的组合并成"一层"
        boolean byObject = d.sawObjectLine;
        Map<String, List<Integer>> byGroup = new LinkedHashMap<>();
        Map<String, String> groupObject = new LinkedHashMap<>();
        Map<String, String> groupMaterial = new LinkedHashMap<>();
        for (int t = 0; t < tris; t++) {
            String object = byObject
                    ? d.objectNames.get(d.triObject.get(t))
                    : d.groupNames.get(d.triGroup.get(t));
            // 材质名跨分件延续：obj 里 usemtl 的作用域一直到下一个 usemtl，不因 o/g 清空
            String material = d.materialNames.get(d.triMaterial.get(t));
            String key = material + '\0' + object;
            byGroup.computeIfAbsent(key, k -> new ArrayList<>()).add(t);
            groupObject.put(key, object);
            groupMaterial.put(key, material);
        }

        // ---- 2. 面法线 ----
        float[] faceNormals = new float[tris * 3];
        for (int t = 0; t < tris; t++) {
            computeFaceNormal(d, t, faceNormals);
        }

        // ---- 3. 逐角法线 ----
        float[] cornerNormals = new float[tris * 9];
        if (d.sawNormalIndex) {
            for (int t = 0; t < tris; t++) {
                for (int k = 0; k < 3; k++) {
                    int ni = d.triVn.get(t * 3 + k);
                    int out = (t * 3 + k) * 3;
                    if (ni >= 0 && ni < d.normals.size()) {
                        float[] n = d.normals.get(ni);
                        cornerNormals[out] = n[0];
                        cornerNormals[out + 1] = n[1];
                        cornerNormals[out + 2] = n[2];
                    } else {
                        // 文件里有 vn、但这个角没索引：用面法线兜底
                        // （Blender 那边会给 (0,0,0)，在 MC 里就是一片黑，所以这里刻意不同）
                        copy3(faceNormals, t * 3, cornerNormals, out);
                    }
                }
            }
        } else {
            Map<PosKey, float[]> smooth = new HashMap<>();
            for (int t = 0; t < tris; t++) {
                if (d.triSmooth.get(t) == 0) continue;
                for (int k = 0; k < 3; k++) {
                    float[] acc = smooth.computeIfAbsent(keyOf(d, d.triV.get(t * 3 + k)), x -> new float[3]);
                    acc[0] += faceNormals[t * 3];
                    acc[1] += faceNormals[t * 3 + 1];
                    acc[2] += faceNormals[t * 3 + 2];
                }
            }
            for (int t = 0; t < tris; t++) {
                boolean isSmooth = d.triSmooth.get(t) != 0;
                for (int k = 0; k < 3; k++) {
                    int out = (t * 3 + k) * 3;
                    boolean done = false;
                    if (isSmooth) {
                        float[] acc = smooth.get(keyOf(d, d.triV.get(t * 3 + k)));
                        if (acc != null) {
                            double len = Math.sqrt(acc[0] * acc[0] + acc[1] * acc[1] + acc[2] * acc[2]);
                            if (len > 1.0E-6) {
                                cornerNormals[out] = (float) (acc[0] / len);
                                cornerNormals[out + 1] = (float) (acc[1] / len);
                                cornerNormals[out + 2] = (float) (acc[2] / len);
                                done = true;
                            }
                        }
                    }
                    if (!done) {
                        copy3(faceNormals, t * 3, cornerNormals, out);
                    }
                }
            }
        }

        // ---- 4. 展开成扁平数组 ----
        List<Group> out = new ArrayList<>(byGroup.size());
        int corners = 0;
        for (Map.Entry<String, List<Integer>> e : byGroup.entrySet()) {
            List<Integer> ts = e.getValue();
            int n = ts.size() * 3;
            float[] pos = new float[n * 3];
            float[] uv = new float[n * 2];
            float[] nor = new float[n * 3];
            float minX = Float.MAX_VALUE;
            float minY = Float.MAX_VALUE;
            float minZ = Float.MAX_VALUE;
            float maxX = -Float.MAX_VALUE;
            float maxY = -Float.MAX_VALUE;
            float maxZ = -Float.MAX_VALUE;

            int c = 0;
            for (int t : ts) {
                for (int k = 0; k < 3; k++, c++) {
                    float[] p = d.positions.get(d.triV.get(t * 3 + k));
                    float x = p[0] * unitScale;
                    // 让伞面（模型 +Y 侧）落到 -Y（挂在附着点下面），和 bbmodel 路径的模型空间一致。
                    // 但必须是**纯旋转**（绕 X 轴 180°）而不是只翻 Y：
                    // 只翻 Y 是一次镜像，手性会反过来 —— bbmodel 那边由 MC 实体渲染自带的
                    // scale(-1,-1,1)（绕 Z 的 180° 旋转，手性不变）补掉，OBJ 这条路径没有那一步，
                    // 于是模型整体前后镜像（AH-64D 这类不对称模型一眼可见）。
                    // 绕 X 轴 180°：(x, y, z) -> (x, -y, -z)，Y 和 Z 一起翻，det = +1。
                    float y = -p[1] * unitScale;
                    float z = -p[2] * unitScale;
                    pos[c * 3] = x;
                    pos[c * 3 + 1] = y;
                    pos[c * 3 + 2] = z;
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (z < minZ) minZ = z;
                    if (x > maxX) maxX = x;
                    if (y > maxY) maxY = y;
                    if (z > maxZ) maxZ = z;

                    int ti = d.triVt.get(t * 3 + k);
                    if (ti >= 0 && ti < d.uvs.size()) {
                        float[] q = d.uvs.get(ti);
                        uv[c * 2] = q[0];
                        // OBJ 的 vt 原点在左下，MC 贴图原点在左上 → 翻一次 V
                        // （Blender 两侧都是原样读写，这个翻转是 MC 与 OBJ 的约定差，不是 Blender 的）
                        uv[c * 2 + 1] = 1.0F - q[1];
                    }

                    int src = (t * 3 + k) * 3;
                    nor[c * 3] = cornerNormals[src];
                    // 法线跟着位置一起做绕 X 轴 180°（Y、Z 同时翻），保持和几何一致
                    nor[c * 3 + 1] = -cornerNormals[src + 1];
                    nor[c * 3 + 2] = -cornerNormals[src + 2];
                }
            }
            out.add(new Group(groupObject.get(e.getKey()), groupMaterial.get(e.getKey()),
                    pos, uv, nor, n, minX, minY, minZ, maxX, maxY, maxZ));
            corners += n;
        }

        return new ObjMesh(List.copyOf(out), tris, corners, d.droppedFaces, d.ngonFaces);
    }

    /** 位置量化 key（1e-4）：平滑法线按"同一个位置"合并，和 Blender 的顶点焊接语义一致 */
    private record PosKey(int x, int y, int z) {
    }

    private static PosKey keyOf(ObjParser.ObjData d, int vertexIndex) {
        float[] p = d.positions.get(vertexIndex);
        return new PosKey(Math.round(p[0] * 10000.0F), Math.round(p[1] * 10000.0F), Math.round(p[2] * 10000.0F));
    }

    private static void computeFaceNormal(ObjParser.ObjData d, int t, float[] out) {
        float[] a = d.positions.get(d.triV.get(t * 3));
        float[] b = d.positions.get(d.triV.get(t * 3 + 1));
        float[] c = d.positions.get(d.triV.get(t * 3 + 2));
        float e1x = b[0] - a[0];
        float e1y = b[1] - a[1];
        float e1z = b[2] - a[2];
        float e2x = c[0] - a[0];
        float e2y = c[1] - a[1];
        float e2z = c[2] - a[2];
        float nx = e1y * e2z - e1z * e2y;
        float ny = e1z * e2x - e1x * e2z;
        float nz = e1x * e2y - e1y * e2x;
        double len = Math.sqrt(nx * nx + ny * ny + nz * nz);
        int o = t * 3;
        if (len < 1.0E-12) {
            // 退化面（面积 ~0）：Blender 会强制平面处理，这里给一个向上的法线避免渲染成黑面
            out[o] = 0.0F;
            out[o + 1] = 1.0F;
            out[o + 2] = 0.0F;
            return;
        }
        out[o] = (float) (nx / len);
        out[o + 1] = (float) (ny / len);
        out[o + 2] = (float) (nz / len);
    }

    private static void copy3(float[] src, int srcOff, float[] dst, int dstOff) {
        dst[dstOff] = src[srcOff];
        dst[dstOff + 1] = src[srcOff + 1];
        dst[dstOff + 2] = src[srcOff + 2];
    }
}
