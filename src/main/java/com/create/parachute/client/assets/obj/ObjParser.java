package com.create.parachute.client.assets.obj;

import com.create.parachute.ParachuteConfig;
import com.create.parachute.ParachuteMod;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Wavefront OBJ 解析器（Blender 工作流），不依赖任何外部库。
 *
 * <h2>文件夹约定</h2>
 * <pre>
 * parachute/local/&lt;伞名&gt;/
 *   ├── xxx.obj         模型
 *   ├── xxx.mtl         材质（map_Kd 指向 textures/ 里的贴图）
 *   └── textures/
 *       └── yyy.png     贴图
 * </pre>
 *
 * <h2>按 Blender 源码对齐的语义</h2>
 * <ul>
 *   <li><b>索引</b>：1-based；支持负数相对索引（{@code idx += 已解析数量}）；0 或越界 → 整个面丢弃；
 *       {@code v} / {@code v/vt} / {@code v//vn} / {@code v/vt/vn} 四种形式都支持</li>
 *   <li><b>面</b>：Blender 导入导出默认都<b>不三角化</b>，所以这里自己扇形三角化；
 *       &lt;3 个角的面丢弃；顶点索引有重复的面丢弃（Blender 用 Delaunay 切开，我们直接丢并计数）；
 *       n-gon（&gt;4 角）计数并在日志里提示导出时勾 Triangulated Mesh</li>
 *   <li><b>vt</b>：文件里完全没有任何 {@code vt} 时，所有 vt 索引按"没有"处理（对齐 Blender #103212）</li>
 *   <li><b>vn / s</b>：文件里出现任何 {@code vn} 就按逐角自定义法线处理（缺 vn 索引的角交给
 *       {@link ObjMesh} 用面法线兜底，Blender 那边是 (0,0,0)）；完全没有 vn 时才看 {@code s}：
 *       {@code s 0|off|缺省} = 平面，其它值 = 平滑。<b>{@code s} 状态在每次 {@code o}/{@code g} 切换时重置为平面</b></li>
 *   <li><b>分组</b>：Blender 导入时只有 {@code o} 会切分（{@code use_split_objects=true}），
 *       {@code g} 默认不切分。所以骨骼按 {@code o} 切，文件里一个 {@code o} 都没有时才退回 {@code g}</li>
 *   <li><b>mtllib</b>：可多行、去重、去引号；另外<b>同目录的 {@code <obj名>.mtl} 即使没被引用也会加载</b>（#97757）</li>
 *   <li><b>容忍</b>：{@code v} 行可能是 3/4(w)/6(rgb)/7(rgba) 个浮点（顶点色扩展），只取前三个；
 *       {@code l}/{@code p} 线、{@code cstype}/{@code deg}/{@code curv}/{@code parm} 等 NURBS 语句跳过；
 *       行尾 {@code \} 续行；未知关键字一律忽略</li>
 * </ul>
 */
public final class ObjParser {

    /**
     * 单个 .obj 文件的大小上限（字节），读配置 {@code visual.objMaxFileMB}；{@code 0} = 不限。
     *
     * <p>原来这里是写死的 256 MB，现在交给配置 —— 见 {@link ParachuteConfig#OBJ_MAX_FILE_MB}。</p>
     */
    private static long maxFileBytes() {
        int mb = ParachuteConfig.OBJ_MAX_FILE_MB.get();
        return mb <= 0 ? Long.MAX_VALUE : (long) mb * 1024L * 1024L;
    }

    /**
     * 单个模型最多加载的三角形数，读配置 {@code visual.objMaxTriangles}；{@code 0} = 不限。
     *
     * <p>原来写死 400 万，见 {@link ParachuteConfig#OBJ_MAX_TRIANGLES}。</p>
     */
    private static int maxTriangles() {
        int n = ParachuteConfig.OBJ_MAX_TRIANGLES.get();
        return n <= 0 ? Integer.MAX_VALUE : n;
    }

    private ObjParser() {
    }

    /** 可增长的 int 数组（避免每个面都 new 小数组） */
    static final class IntList {
        private int[] a = new int[256];
        private int n;

        void add(int v) {
            if (this.n == this.a.length) {
                this.a = Arrays.copyOf(this.a, this.a.length * 2);
            }
            this.a[this.n++] = v;
        }

        int get(int i) {
            return this.a[i];
        }

        int size() {
            return this.n;
        }
    }

    /**
     * 一个材质的贴图/透明度信息（来自 .mtl）。
     *
     * <ul>
     *   <li>{@code diffuse} —— {@code map_Kd}，albedo 文件名（可为 null）</li>
     *   <li>{@code alphaMask} —— {@code map_d}，透明度遮罩文件名（可为 null）</li>
     *   <li>{@code dissolve} —— {@code d}，整体不透明度 0~1（默认 1）</li>
     * </ul>
     *
     * <p>DCS/Blender 导出的模型里，玻璃之类的半透明部件<b>透明信息不在 albedo 里</b>
     * （albedo 常常是纯不透明 jpg），而是靠 {@code map_d} 或 {@code d} 表达 ——
     * 所以这两个键必须解析，否则那些部件会渲染成不透明的，看起来就是"半透明材质没了"。</p>
     */
    public record Material(@Nullable String diffuse, @Nullable String alphaMask, float dissolve,
                           float colorR, float colorG, float colorB) {

        public static final Material DEFAULT = new Material(null, null, 1.0F, 1.0F, 1.0F, 1.0F);

        /** 兼容构造：Kd 按白色（没写 Kd 的材质） */
        public Material(@Nullable String diffuse, @Nullable String alphaMask, float dissolve) {
            this(diffuse, alphaMask, dissolve, 1.0F, 1.0F, 1.0F);
        }

        Material withDiffuse(String name) {
            return new Material(name, this.alphaMask, this.dissolve, this.colorR, this.colorG, this.colorB);
        }

        Material withAlphaMask(String name) {
            return new Material(this.diffuse, name, this.dissolve, this.colorR, this.colorG, this.colorB);
        }

        Material withDissolve(float value) {
            return new Material(this.diffuse, this.alphaMask, value, this.colorR, this.colorG, this.colorB);
        }

        Material withColor(float r, float g, float b) {
            return new Material(this.diffuse, this.alphaMask, this.dissolve, r, g, b);
        }

        /**
         * 没有 {@code map_Kd} 时用的纯色（Kd），打包成 0xRRGGBB。
         *
         * <p>DCS 的玻璃就是这种材质：{@code illum 9} + {@code Kd 0 0 0} + {@code Ks 1 1 1} + {@code d 0.1}，
         * <b>完全不引用贴图</b>。以前这种组会去借用"模型的主贴图"，于是别的贴图（例如
         * {@code ah-64d_dispenser_d.jpg} 上那两行 WARNING 文字）就会被糊到玻璃上。</p>
         */
        public int solidRgb() {
            int r = Math.round(Math.min(1.0F, Math.max(0.0F, this.colorR)) * 255.0F);
            int g = Math.round(Math.min(1.0F, Math.max(0.0F, this.colorG)) * 255.0F);
            int b = Math.round(Math.min(1.0F, Math.max(0.0F, this.colorB)) * 255.0F);
            return (r << 16) | (g << 8) | b;
        }

        /** 这个材质是否需要半透明渲染 */
        public boolean hasAlpha() {
            return this.alphaMask != null || this.dissolve < 0.999F;
        }
    }

    /** 解析结果：顶点池 + 三角形（已扇形三角化）+ 分组/材质表 */
    public static final class ObjData {

        /** 顶点池，文件顺序（负索引要靠"已解析数量"解析，所以必须按顺序保留） */
        public final List<float[]> positions = new ArrayList<>();
        /** UV 池 */
        public final List<float[]> uvs = new ArrayList<>();
        /** 法线池（读入时已归一化） */
        public final List<float[]> normals = new ArrayList<>();
        /** 材质名 → 贴图/透明度信息 */
        public final Map<String, Material> materials = new LinkedHashMap<>();

        /** 每个三角形 3 个顶点索引 */
        final IntList triV = new IntList();
        /** 每个三角形 3 个 UV 索引；-1 = 没有 */
        final IntList triVt = new IntList();
        /** 每个三角形 3 个法线索引；-1 = 没有 */
        final IntList triVn = new IntList();
        /** 每个三角形所属 o / g / 材质（表内索引） */
        final IntList triObject = new IntList();
        final IntList triGroup = new IntList();
        final IntList triMaterial = new IntList();
        /** 每个三角形是否平滑（s）*/
        final IntList triSmooth = new IntList();

        /** 名字池，索引 0 恒为 ""（表示"未指定"） */
        final List<String> objectNames = new ArrayList<>(List.of(""));
        final List<String> groupNames = new ArrayList<>(List.of(""));
        final List<String> materialNames = new ArrayList<>(List.of(""));

        /** 文件里是否出现过 o 行（决定骨骼按 o 还是 g 切） */
        boolean sawObjectLine;
        /** 是否至少有一个角带 vn 索引 */
        boolean sawNormalIndex;
        /** 达到上限被截断 */
        boolean truncated;
        /** 三角形上限（解析开始时从配置读一次，{@code 0} 配置表示不限 → 这里存 Integer.MAX_VALUE） */
        int maxTriangles = Integer.MAX_VALUE;

        public int triangleCount;
        public int droppedFaces;
        public int ngonFaces;
        /** vt/vn 索引非法（按"没有"处理）的次数 */
        public int badUvOrNormalRefs;

        int internObject(String name) {
            return intern(this.objectNames, name);
        }

        int internGroup(String name) {
            return intern(this.groupNames, name);
        }

        int internMaterial(String name) {
            return intern(this.materialNames, name);
        }

        private static int intern(List<String> pool, String name) {
            int i = pool.indexOf(name);
            if (i >= 0) return i;
            pool.add(name);
            return pool.size() - 1;
        }
    }

    /**
     * 解析一个 .obj（连同它的 .mtl）。
     *
     * @return 解析结果；文件过大、读不了、或没有可用三角形时返回 null
     */
    @Nullable
    public static ObjData parse(Path objFile) {
        try {
            long size = Files.size(objFile);
            long limit = maxFileBytes();
            if (size > limit) {
                ParachuteMod.LOGGER.warn("OBJ '{}' is too large ({} bytes > {}), skipped",
                        objFile.getFileName(), size, limit);
                return null;
            }
        } catch (IOException e) {
            ParachuteMod.LOGGER.warn("Failed to stat OBJ '{}': {}", objFile, e.toString());
            return null;
        }

        ObjData d = new ObjData();
        d.maxTriangles = maxTriangles();
        // 单遍流式解析：允许的上限现在到了 GB 级，先把整个文件读成 List<String> 会直接吃爆堆，
        // 所以这里逐行喂进去，只有顶点/面/index 这些真正的几何数据留在内存里。
        Cursor cur = new Cursor();
        Set<String> libs = new LinkedHashSet<>();

        forEachLine(objFile, raw -> {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) return;
            int sp = firstWhitespace(line);
            String kw = (sp < 0 ? line : line.substring(0, sp)).toLowerCase(Locale.ROOT);
            String rest = sp < 0 ? "" : line.substring(sp + 1).trim();

            switch (kw) {
                case "v" -> {
                    float[] p = parseFloats(rest, 3);
                    if (p != null) d.positions.add(p);
                }
                case "vt" -> {
                    float[] t = parseFloats(rest, 2);
                    if (t != null) d.uvs.add(t);
                }
                case "vn" -> {
                    float[] n = parseFloats(rest, 3);
                    if (n != null) d.normals.add(normalize(n));
                }
                case "f" -> parseFace(d, rest, cur.objectIdx, cur.groupIdx, cur.materialIdx, cur.smooth);
                case "o" -> {
                    cur.objectIdx = d.internObject(rest.isEmpty() ? "object" : rest);
                    // Blender: o/g 切换会把平滑状态重置为平面
                    cur.smooth = false;
                    d.sawObjectLine = true;
                }
                case "g" -> {
                    cur.groupIdx = d.internGroup(rest);
                    cur.smooth = false;
                }
                case "usemtl" -> cur.materialIdx = d.internMaterial(rest);
                case "s" -> cur.smooth = !(rest.isEmpty() || rest.equalsIgnoreCase("off") || rest.equals("0"));
                case "mtllib" -> {
                    for (String lib : splitTokens(rest)) {
                        String clean = unquote(lib);
                        if (!clean.isEmpty()) libs.add(clean);
                    }
                }
                default -> {
                    // l / p / curv / cstype / deg / parm / 其它扩展：忽略
                }
            }
        });

        // 材质库：mtllib 指定的 + 同目录同名 .mtl（Blender #97757：没引用也会加）
        Path dir = objFile.getParent();
        for (String lib : libs) {
            readMtl(dir.resolve(lib), d.materials);
        }
        String base = objFile.getFileName().toString();
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            Path sibling = dir.resolve(base.substring(0, dot) + ".mtl");
            if (Files.isRegularFile(sibling)) {
                readMtl(sibling, d.materials);
            }
        }

        if (d.truncated) {
            ParachuteMod.LOGGER.warn("OBJ '{}' exceeds {} triangles; the rest was ignored",
                    objFile.getFileName(), d.maxTriangles);
        }
        return d;
    }

    // ============================================================
    // 面
    // ============================================================

    private static void parseFace(ObjData d, String rest, int objectIdx, int groupIdx, int materialIdx, boolean smooth) {
        String[] toks = splitTokens(rest);
        int n = toks.length;
        if (n < 3) {
            d.droppedFaces++;
            return;
        }

        int[] vi = new int[n];
        int[] ti = new int[n];
        int[] ni = new int[n];
        for (int i = 0; i < n; i++) {
            if (!parseCorner(d, toks[i], vi, ti, ni, i)) {
                d.droppedFaces++;
                return;
            }
        }
        // 顶点索引重复的面：三角化后必然出现退化三角形，直接丢（Blender 用 Delaunay 切开）
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                if (vi[i] == vi[j]) {
                    d.droppedFaces++;
                    return;
                }
            }
        }
        if (n > 4) d.ngonFaces++;

        for (int i = 1; i + 1 < n; i++) {
            if (d.triangleCount >= d.maxTriangles) {
                d.truncated = true;
                return;
            }
            pushTriangle(d, vi[0], vi[i], vi[i + 1], ti[0], ti[i], ti[i + 1], ni[0], ni[i], ni[i + 1],
                    objectIdx, groupIdx, materialIdx, smooth);
        }
    }

    private static void pushTriangle(ObjData d, int v0, int v1, int v2, int t0, int t1, int t2,
                                     int n0, int n1, int n2, int objectIdx, int groupIdx, int materialIdx, boolean smooth) {
        d.triV.add(v0);
        d.triV.add(v1);
        d.triV.add(v2);
        d.triVt.add(t0);
        d.triVt.add(t1);
        d.triVt.add(t2);
        d.triVn.add(n0);
        d.triVn.add(n1);
        d.triVn.add(n2);
        d.triObject.add(objectIdx);
        d.triGroup.add(groupIdx);
        d.triMaterial.add(materialIdx);
        d.triSmooth.add(smooth ? 1 : 0);
        d.triangleCount++;
    }

    /**
     * 解析一个角 {@code v}、{@code v/vt}、{@code v//vn}、{@code v/vt/vn}。
     * <p>顶点索引非法 → 返回 false（整个面丢弃）；vt/vn 索引非法 → 按"没有"处理并计数。</p>
     */
    private static boolean parseCorner(ObjData d, String token, int[] vi, int[] ti, int[] ni, int k) {
        String a = token;
        String b = null;
        String c = null;
        int s1 = token.indexOf('/');
        if (s1 >= 0) {
            a = token.substring(0, s1);
            int s2 = token.indexOf('/', s1 + 1);
            if (s2 < 0) {
                b = token.substring(s1 + 1);
            } else {
                b = token.substring(s1 + 1, s2);
                c = token.substring(s2 + 1);
            }
        }
        int v = resolveIndex(a, d.positions.size());
        if (v < 0) return false;
        vi[k] = v;

        if (b == null || b.isEmpty()) {
            ti[k] = -1;
        } else {
            int t = resolveIndex(b, d.uvs.size());
            if (t < 0) d.badUvOrNormalRefs++;
            ti[k] = t;
        }
        if (c == null || c.isEmpty()) {
            ni[k] = -1;
        } else {
            int nn = resolveIndex(c, d.normals.size());
            if (nn < 0) d.badUvOrNormalRefs++;
            ni[k] = nn;
            if (nn >= 0) d.sawNormalIndex = true;
        }
        return true;
    }

    /** OBJ 索引：正数 1-based，负数相对"已解析数量"，非法返回 -1 */
    private static int resolveIndex(String token, int count) {
        if (token.isEmpty()) return -1;
        try {
            int idx = Integer.parseInt(token.trim());
            if (idx > 0) {
                int r = idx - 1;
                return r < count ? r : -1;
            }
            if (idx < 0) {
                int r = count + idx;
                return r >= 0 ? r : -1;
            }
            return -1;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    // ============================================================
    // .mtl
    // ============================================================

    /** 读一个 .mtl：收集 {@code map_Kd}（albedo）、{@code map_d}（透明度遮罩）、{@code d}（整体不透明度） */
    private static void readMtl(Path mtl, Map<String, Material> out) {
        if (!Files.isRegularFile(mtl)) return;
        String[] current = {""};
        forEachLine(mtl, raw -> {
            String line = stripComment(raw).trim();
            if (line.isEmpty()) return;
            int sp = firstWhitespace(line);
            String kw = (sp < 0 ? line : line.substring(0, sp)).toLowerCase(Locale.ROOT);
            String rest = sp < 0 ? "" : line.substring(sp + 1).trim();
            if (kw.equals("newmtl")) {
                current[0] = rest;
                return;
            }
            if (current[0].isEmpty()) return;
            switch (kw) {
                case "map_kd" -> {
                    String name = lastTokenFileName(rest);
                    if (name != null) {
                        out.put(current[0], out.getOrDefault(current[0], Material.DEFAULT).withDiffuse(name));
                    }
                }
                case "map_d" -> {
                    String name = lastTokenFileName(rest);
                    if (name != null) {
                        out.put(current[0], out.getOrDefault(current[0], Material.DEFAULT).withAlphaMask(name));
                    }
                }
                case "kd" -> {
                    // 漫反射颜色：没有 map_Kd 的材质（DCS 的玻璃等）就靠它上色
                    String[] toks = splitTokens(rest);
                    if (toks.length >= 3) {
                        try {
                            float r = Float.parseFloat(toks[0]);
                            float g = Float.parseFloat(toks[1]);
                            float b = Float.parseFloat(toks[2]);
                            out.put(current[0], out.getOrDefault(current[0], Material.DEFAULT).withColor(r, g, b));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                case "d" -> {
                    // 可能是 "d 0.5"，也可能是 "d -halo 0.5"：取最后一个 token
                    String[] toks = splitTokens(rest);
                    if (toks.length > 0) {
                        try {
                            float v = Float.parseFloat(toks[toks.length - 1]);
                            if (v >= 0.0F && v <= 1.0F) {
                                out.put(current[0], out.getOrDefault(current[0], Material.DEFAULT).withDissolve(v));
                            }
                        } catch (NumberFormatException ignored) {
                        }
                    }
                }
                default -> {
                }
            }
        });
    }

    /** 取路径的最后一个 token 并只留文件名（去引号、去目录） */
    @Nullable
    private static String lastTokenFileName(String rest) {
        String[] toks = splitTokens(rest);
        if (toks.length == 0) return null;
        String path = unquote(toks[toks.length - 1]);
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return name.isEmpty() ? null : name;
    }

    // ============================================================
    // 文本工具
    // ============================================================

    /**
     * 逐行流式读文本，支持行尾 {@code \} 续行。
     *
     * <p>以前这里是 {@code Files.readAllLines} 把整个文件读成 {@code List<String>}，
     * 配置允许的上限到 GB 级以后那一份副本就足以把堆吃爆（2 GB 文本 → 2 GB 字节 + 一堆 String），
     * 所以改成边读边喂；内存里只留解析出来的几何数据。</p>
     */
    private static void forEachLine(Path file, Consumer<String> sink) {
        StringBuilder pending = new StringBuilder();
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String l;
            while ((l = reader.readLine()) != null) {
                if (l.endsWith("\\")) {
                    pending.append(l, 0, l.length() - 1);
                    continue;
                }
                if (pending.length() > 0) {
                    pending.append(l);
                    sink.accept(pending.toString());
                    pending.setLength(0);
                } else {
                    sink.accept(l);
                }
            }
        } catch (IOException e) {
            ParachuteMod.LOGGER.warn("Failed to read '{}': {}", file, e.getMessage());
        }
        if (pending.length() > 0) sink.accept(pending.toString());
    }

    /** 解析游标：{@code forEachLine} 的 lambda 里需要可变状态（o/g/usemtl/s 会切换当前归属） */
    private static final class Cursor {
        int objectIdx;
        int groupIdx;
        int materialIdx;
        boolean smooth;
    }

    private static String stripComment(String line) {
        int i = line.indexOf('#');
        return i < 0 ? line : line.substring(0, i);
    }

    private static int firstWhitespace(String s) {
        for (int i = 0; i < s.length(); i++) {
            if (Character.isWhitespace(s.charAt(i))) return i;
        }
        return -1;
    }

    private static String[] splitTokens(String s) {
        String t = s.trim();
        return t.isEmpty() ? new String[0] : t.split("\\s+");
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.charAt(0) == '"' && s.charAt(s.length() - 1) == '"') {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 取前 count 个浮点；不足返回 null（v 行允许 4/6/7 个分量，只取前 3） */
    @Nullable
    private static float[] parseFloats(String rest, int count) {
        String[] toks = splitTokens(rest);
        if (toks.length < count) return null;
        float[] out = new float[count];
        try {
            for (int i = 0; i < count; i++) {
                out[i] = Float.parseFloat(toks[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return out;
    }

    private static float[] normalize(float[] v) {
        double len = Math.sqrt(v[0] * v[0] + v[1] * v[1] + v[2] * v[2]);
        if (len < 1.0E-9) return new float[]{0.0F, 1.0F, 0.0F};
        return new float[]{(float) (v[0] / len), (float) (v[1] / len), (float) (v[2] / len)};
    }
}
