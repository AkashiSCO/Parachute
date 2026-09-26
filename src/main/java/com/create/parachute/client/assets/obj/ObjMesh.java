package com.create.parachute.client.assets.obj;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
        return bake(d, unitScale, 0.0D);
    }

    /**
     * 把解析结果烘焙成可直接渲染的扁平网格。
     *
     * @param unitScale      OBJ 单位 → 方块（Blender 默认 1 unit = 1 m = 1 格，所以是 1.0）
     * @param smoothAngleDeg 顶点法线自动平滑角度（度）：{@code 0} = 用文件里的 {@code vn}（或按 {@code s}）；
     *                       {@code >0} = 按几何重算 —— 同一个顶点上与该面夹角在阈值内的面做角度加权平均，
     *                       超过阈值的保持硬边（见 {@link #applyAutoSmooth}）
     */
    public static ObjMesh bake(ObjParser.ObjData d, float unitScale, double smoothAngleDeg) {
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
        if (smoothAngleDeg > 0.0D) {
            // 自动平滑：完全按几何重算（文件的 vn / s 都不看）。
            // 好处之一是法线必然与绕序一致 —— 这也是"一份几何 + 剔除背面"能保证光照正确的前提。
            for (int t = 0; t < tris; t++) {
                for (int k = 0; k < 3; k++) {
                    copy3(faceNormals, t * 3, cornerNormals, (t * 3 + k) * 3);
                }
            }
            int changed = applyAutoSmooth(d, tris, faceNormals, cornerNormals, smoothAngleDeg);
            com.create.parachute.ParachuteMod.LOGGER.info(
                    "OBJ: auto-smooth normals (angle {}°) changed {} of {} corners",
                    String.format("%.0f", smoothAngleDeg), changed, tris * 3);
        } else if (d.sawNormalIndex) {
            // 文件里的 vn 可能是"部分轴存反"的：这个 AH-64D 就有一批零件（炮 / dummy / object…）
            // 的 vn **Y 轴整体反向**，而其余零件正常 —— 表现就是那几组"顶面暗、底面亮"，
            // 而整体取反又会把其余零件弄反，所以不能一刀切。
            //
            // 判据：按对象统计三个轴的"相关性" Σ(几何法线_i × vn_i)（用绕序算出的面法线作基准）。
            // 正常情况三个分量都是正的；哪个轴为负，说明该零件的 vn 在这个轴上存反了。
            // （只翻 Y 的情况用"整体点积"判不出来——侧面仍然同向，只有顶/底面反向，正好各占一半。）
            Map<String, double[]> corr = new HashMap<>();   // object -> [x, y, z]
            for (int t = 0; t < tris; t++) {
                String object = byObject
                        ? d.objectNames.get(d.triObject.get(t))
                        : d.groupNames.get(d.triGroup.get(t));
                double[] a = corr.computeIfAbsent(object, k -> new double[3]);
                float gx = faceNormals[t * 3];
                float gy = faceNormals[t * 3 + 1];
                float gz = faceNormals[t * 3 + 2];
                for (int k = 0; k < 3; k++) {
                    int ni = d.triVn.get(t * 3 + k);
                    if (ni < 0 || ni >= d.normals.size()) continue;
                    float[] n = d.normals.get(ni);
                    a[0] += (double) gx * n[0];
                    a[1] += (double) gy * n[1];
                    a[2] += (double) gz * n[2];
                }
            }

            Map<String, float[]> signs = new HashMap<>();
            int axisFixes = 0;
            int affected = 0;
            for (Map.Entry<String, double[]> e : corr.entrySet()) {
                double[] a = e.getValue();
                float sx = a[0] < 0.0D ? -1.0F : 1.0F;
                float sy = a[1] < 0.0D ? -1.0F : 1.0F;
                float sz = a[2] < 0.0D ? -1.0F : 1.0F;
                if (sx < 0.0F) axisFixes++;
                if (sy < 0.0F) axisFixes++;
                if (sz < 0.0F) axisFixes++;
                if (sx < 0.0F || sy < 0.0F || sz < 0.0F) {
                    signs.put(e.getKey(), new float[] {sx, sy, sz});
                    affected++;
                }
            }
            if (affected > 0) {
                com.create.parachute.ParachuteMod.LOGGER.info(
                        "OBJ: {} object(s) had per-axis flipped vn ({} axis fix(es)); corrected",
                        affected, axisFixes);
            }

            for (int t = 0; t < tris; t++) {
                String object = byObject
                        ? d.objectNames.get(d.triObject.get(t))
                        : d.groupNames.get(d.triGroup.get(t));
                float[] sign = signs.get(object);
                float sx = sign == null ? 1.0F : sign[0];
                float sy = sign == null ? 1.0F : sign[1];
                float sz = sign == null ? 1.0F : sign[2];
                for (int k = 0; k < 3; k++) {
                    int ni = d.triVn.get(t * 3 + k);
                    int out = (t * 3 + k) * 3;
                    if (ni >= 0 && ni < d.normals.size()) {
                        float[] n = d.normals.get(ni);
                        cornerNormals[out] = sx * n[0];
                        cornerNormals[out + 1] = sy * n[1];
                        cornerNormals[out + 2] = sz * n[2];
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
                    // 绕 Y 轴 180°：(x, y, z) -> (-x, y, -z)，det = +1（纯旋转，手性不变）。
                    //
                    // 为什么是绕 Y 而不是绕 X：模型空间约定"伞顶 = +Y"（渲染器 rotateTo(0,+1,0, 阻力方向)），
                    // bbmodel 那边加载时会做同样的归一化（modded_entity 绕 Z 180°、bedrock 绕 X 180°）。
                    // OBJ（Blender 导出 up=+Y）伞顶本来就在 +Y 侧，绕 X 180° 会把它压到 -Y（伞面朝下）；
                    // 绕 Y 180° 既保留"伞顶在 +Y"又不镜像，和 bbmodel 路径解出来的解一致。
                    float x = -p[0] * unitScale;
                    float y = p[1] * unitScale;
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
                    // 法线跟着几何一起做同一个绕 Y 轴 180°（X、Z 同时翻），保持同向：
                    // "模型里外翻"的零件已经在上面按对象做的投票/散度测试里翻回来了。
                    nor[c * 3] = -cornerNormals[src];
                    nor[c * 3 + 1] = cornerNormals[src + 1];
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

    /**
     * 按几何自动平滑顶点法线（相当于 Blender 的 "Shade Auto Smooth" / 角度阈值平滑）。
     *
     * <p>做法：先把<b>同一位置</b>上的角归成一组（不管它们在文件里是不是同一个顶点索引 ——
     * DCS/Blender 导出的硬边会把顶点拆开，按位置合并才能跨拆分平滑），然后对每个角，把同组里
     * 与<b>本面</b>夹角在阈值内的面法线做<b>角度加权</b>平均（权重 = 该面在这个位置上的内角）。
     * 超过阈值的面不参与，所以折边、面板分界仍然是硬边；曲面（机身、旋翼、炮管）则变平滑。</p>
     *
     * <p>DCS 转出来的 OBJ 大多是<b>逐面法线</b>（实测 AH-64D：979203 个面 / 2596357 个互不相同的
     * 角法线，同一个位置上平均有 2.8 个不同法线），不做这一步曲面就是"一格一格"的硬边。</p>
     *
     * <p>另外，这样算出来的法线必定与绕序一致（这就是它的来源），所以"一份几何 + 剔除背面"
     * 那种模式下光照必然正确。</p>
     *
     * @return 被改动过的角数（日志用）
     */
    private static int applyAutoSmooth(ObjParser.ObjData d, int tris, float[] faceNormals,
                                      float[] cornerNormals, double angleDeg) {
        float cosLimit = (float) Math.cos(Math.toRadians(angleDeg));

        // 1. 位置 → 该位置上的所有角（角编号 = t*3+k）；bucket[0] 是数量，数据从 [1] 开始
        Map<PosKey, int[]> byPos = new HashMap<>(tris * 2);
        for (int t = 0; t < tris; t++) {
            for (int k = 0; k < 3; k++) {
                PosKey key = keyOf(d, d.triV.get(t * 3 + k));
                int corner = t * 3 + k;
                int[] bucket = byPos.get(key);
                if (bucket == null) {
                    byPos.put(key, new int[] {1, corner, 0, 0, 0});
                } else {
                    if (bucket[0] + 1 >= bucket.length) {
                        bucket = Arrays.copyOf(bucket, bucket.length * 2);
                        byPos.put(key, bucket);
                    }
                    bucket[++bucket[0]] = corner;
                }
            }
        }

        // 2. 每个角的"面内夹角"作为权重（角度加权，比平均权重更接近真实曲面法线）
        float[] weight = new float[tris * 3];
        for (int t = 0; t < tris; t++) {
            for (int k = 0; k < 3; k++) {
                weight[t * 3 + k] = cornerAngle(d, t, k);
            }
        }

        // 3. 逐角平滑
        int changed = 0;
        for (int[] bucket : byPos.values()) {
            int count = bucket[0];
            for (int i = 1; i <= count; i++) {
                int c1 = bucket[i];
                int t1 = c1 / 3;
                float nx = faceNormals[t1 * 3];
                float ny = faceNormals[t1 * 3 + 1];
                float nz = faceNormals[t1 * 3 + 2];
                double sx = 0.0D;
                double sy = 0.0D;
                double sz = 0.0D;
                for (int j = 1; j <= count; j++) {
                    int c2 = bucket[j];
                    int t2 = c2 / 3;
                    float mx = faceNormals[t2 * 3];
                    float my = faceNormals[t2 * 3 + 1];
                    float mz = faceNormals[t2 * 3 + 2];
                    // 超过阈值 = 硬边，这个面不参与平滑
                    if (nx * mx + ny * my + nz * mz < cosLimit) continue;
                    float w = weight[c2];
                    sx += w * mx;
                    sy += w * my;
                    sz += w * mz;
                }
                double len = Math.sqrt(sx * sx + sy * sy + sz * sz);
                if (len < 1.0E-9) continue;   // 退化（夹角全为 0 之类）：保留原法线
                float rx = (float) (sx / len);
                float ry = (float) (sy / len);
                float rz = (float) (sz / len);
                int o = c1 * 3;
                if (rx * cornerNormals[o] + ry * cornerNormals[o + 1] + rz * cornerNormals[o + 2] < 0.999F) {
                    changed++;
                }
                cornerNormals[o] = rx;
                cornerNormals[o + 1] = ry;
                cornerNormals[o + 2] = rz;
            }
        }
        return changed;
    }

    /** 三角形 t 在角 k 处的内角（弧度） */
    private static float cornerAngle(ObjParser.ObjData d, int t, int k) {
        float[] a = d.positions.get(d.triV.get(t * 3 + k));
        float[] b = d.positions.get(d.triV.get(t * 3 + (k + 1) % 3));
        float[] c = d.positions.get(d.triV.get(t * 3 + (k + 2) % 3));
        double ux = b[0] - a[0];
        double uy = b[1] - a[1];
        double uz = b[2] - a[2];
        double vx = c[0] - a[0];
        double vy = c[1] - a[1];
        double vz = c[2] - a[2];
        double lu = Math.sqrt(ux * ux + uy * uy + uz * uz);
        double lv = Math.sqrt(vx * vx + vy * vy + vz * vz);
        if (lu < 1.0E-12 || lv < 1.0E-12) return 0.0F;
        double cos = (ux * vx + uy * vy + uz * vz) / (lu * lv);
        return (float) Math.acos(Math.max(-1.0D, Math.min(1.0D, cos)));
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
