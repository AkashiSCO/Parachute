package com.create.parachute.client.assets;

import com.create.parachute.ParachuteMod;
import com.create.parachute.client.assets.obj.ObjGpuCache;
import com.create.parachute.client.assets.obj.ObjMesh;
import com.create.parachute.client.assets.obj.ObjModelBuilder;
import com.create.parachute.client.assets.obj.ObjParser;
import com.create.parachute.data.ParachuteManager;
import com.create.parachute.data.ParachuteScope;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.util.GsonHelper;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.animation.AnimationDefinition;
import net.minecraft.client.animation.KeyframeAnimations;
import net.minecraft.client.model.HierarchicalModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 客户端伞资源管理器（热加载）：按<b>伞源</b>扫描并解析每把伞的 .bbmodel
 * （模型 + 开伞动画 + 贴图），按纯伞名缓存。
 *
 * <h2>伞源与优先级</h2>
 * <p>扫描顺序就是优先级顺序（{@link ParachuteScope}）：</p>
 * <ul>
 *   <li>连别人的服务器时：先 {@code parachute/server/<服务器存档UUID>/}，再 {@code parachute/local/}
 *       —— 服务器下发的伞优先，玩家本地同名伞作为兜底（这样第一次进服、服务器文件夹还空着时
 *       不会一把伞都没有）</li>
 *   <li>单机 / 自己开的世界（含 LAN 主机）：只用 {@code parachute/local/}</li>
 * </ul>
 * <p>对外（GUI、方块 NBT、网络包）始终只有<b>纯伞名</b>，来源只在本类内部用于选文件夹和拼贴图
 * 路径 {@code parachute/<来源>/<伞名>/original}——同名但不同来源的两把伞各有一张贴图，
 * 不会互相覆盖。</p>
 *
 * <p><b>热加载</b>：每次访问时按文件夹内文件修改时间判断是否有变化（节流 0.2 秒），
 * 新增/修改/删除伞自动生效，无需重启、无需手动刷新。GUI 打开时强制刷新。</p>
 *
 * <p>查找不存在的伞名时自动回退 {@link ParachuteManager#DEFAULT_PARACHUTE}（蘑菇伞）——
 * 多人游戏中其他玩家使用本机没有的伞时即走此路径。</p>
 */
public final class ParachuteAssets {

    /**
     * 一层渲染：几何（{@code model} 或 {@code gpu} 二选一）+ 贴图 + 透明度信息。
     *
     * <p>单贴图模型（所有 bbmodel，以及没有多材质的 OBJ）<b>永远只有一层</b>，
     * 行为、贴图 ResourceLocation 都和以前完全一致；OBJ 的多材质模型按贴图合并后可能有多层。</p>
     *
     * @param model       ModelPart 树（bbmodel、以及低于烘焙阈值的小模型）
     * @param gpu         高面数 OBJ 层的 GPU 缓冲缓存（有它时 {@code model} 为 null，渲染结果相同）
     * @param translucent 是否用半透明渲染（{@code entityTranslucentCull}）而不是 cutout
     * @param alpha       整体不透明度（来自 mtl 的 {@code d}，1.0 = 不透明）
     */
    public record Layer(@Nullable ModelPart model, @Nullable ObjGpuCache gpu, @Nullable ResourceLocation texture,
                        @Nullable ResourceLocation whiteTexture, boolean translucent, float alpha) {

        /** 兼容构造：不透明、无 alpha 调制、未烘焙 */
        public Layer(ModelPart model, @Nullable ResourceLocation texture, @Nullable ResourceLocation whiteTexture) {
            this(model, null, texture, whiteTexture, false, 1.0F);
        }
    }

    /**
     * 一把伞的完整渲染数据。
     *
     * @param renderRadius 模型几何到自身原点的最大距离（格），用来算渲染包围盒（视锥剔除用）；
     *                     0 = 拿不到（bbmodel 等），渲染器会用默认值兜底
     */
    public record BakedParachute(String id, List<Layer> layers, AnimationDefinition openAnimation,
                                 float lengthSeconds, boolean bedrock, float renderRadius) {

        /** 兼容构造：没有几何半径信息（bbmodel 等） */
        public BakedParachute(String id, List<Layer> layers, AnimationDefinition openAnimation,
                              float lengthSeconds, boolean bedrock) {
            this(id, layers, openAnimation, lengthSeconds, bedrock, 0.0F);
        }

        /** 主层：单贴图模型就是唯一那层（动画、染色兜底都看它） */
        @Nullable
        public Layer primary() {
            return this.layers.isEmpty() ? null : this.layers.get(0);
        }

        /** 主层的模型树 */
        @Nullable
        public ModelPart root() {
            Layer p = primary();
            return p == null ? null : p.model();
        }

        /** 主层贴图 */
        @Nullable
        public ResourceLocation texture() {
            Layer p = primary();
            return p == null ? null : p.texture();
        }

        /** 主层白底贴图（染色用） */
        @Nullable
        public ResourceLocation whiteTexture() {
            Layer p = primary();
            return p == null ? null : p.whiteTexture();
        }

        /** 有没有任何一层能用（贴图缺失的层会被渲染器跳过） */
        public boolean hasTexture() {
            for (Layer layer : this.layers) {
                if (layer.texture() != null) return true;
            }
            return false;
        }
    }

    /** 一把伞的实际位置：所在文件夹 + 来源标签（{@code local} 或 {@code server/<文件夹名>}） */
    private record Entry(Path dir, String source) {
    }

    /** 缓存 key = 来源标签 + "/" + 纯伞名 */
    private static Map<String, BakedParachute> cache;
    /** 纯伞名 → 生效的那一份（按优先级去重后的结果） */
    private static Map<String, Entry> entries;
    private static List<String> idList;
    /** 缓存 key → 文件夹签名（全部文件最大修改时间），用于热加载检测 */
    private static final Map<String, Long> signatures = new HashMap<>();
    private static long lastScan;
    private static boolean loggedInitial;

    private static String keyOf(String source, String id) {
        return source + "/" + id;
    }

    /** 当前伞源按优先级排好序：连服务器时服务器文件夹优先，local 永远在最后兜底 */
    private static List<ParachuteScope.Source> activeSources() {
        List<ParachuteScope.Source> sources = new ArrayList<>(2);
        ParachuteScope.Source scope = ParachuteScope.clientScope();
        if (!scope.isLocal()) {
            sources.add(scope);
        }
        sources.add(ParachuteScope.local());
        return sources;
    }

    /** 热扫描节流间隔（毫秒）：导入/修改伞后约 0.2 秒内自动可见 */
    private static final long SCAN_INTERVAL_MS = 200L;

    /** OBJ 工作流的贴图子目录：{@code <伞名>/textures/*.png} */
    private static final String TEXTURE_DIR = "textures";

    /** OBJ 单位 → 方块。Blender 默认 1 unit = 1 m = 1 格，所以是 1.0；要改大小用 GUI 的 Scale */
    private static final float OBJ_UNIT_SCALE = 1.0F;

    /**
     * 超过这个三角形数就烘进 GPU 缓冲。
     * <p>低于它保持原来的逐帧发射路径；高于它只是把顶点搬到显存里（渲染状态、光照、剔除行为
     * 都和逐帧发射完全一致），换来每帧不再有 O(顶点数) 的 CPU 开销。</p>
     */
    private static final int BAKE_MIN_TRIANGLES = 20_000;

    private ParachuteAssets() {
    }

    /** 获取伞名对应的渲染数据；不存在时回退蘑菇伞；蘑菇伞也没有则返回 null（渲染器跳过） */
    @Nullable
    public static BakedParachute get(String name) {
        refresh();
        BakedParachute parachute = lookup(name);
        return parachute != null ? parachute : lookup(ParachuteManager.DEFAULT_PARACHUTE);
    }

    @Nullable
    private static BakedParachute lookup(@Nullable String name) {
        if (name == null || name.isEmpty() || entries == null) return null;
        Entry entry = entries.get(name);
        return entry == null ? null : cache.get(keyOf(entry.source(), name));
    }

    /** 该伞是否为 bedrock 模式（lav25 之类）。影响渲染朝向补偿：bedrock 需额外绕 Y 旋转。 */
    public static boolean isBedrock(String name) {
        BakedParachute p = get(name);
        return p != null && p.bedrock();
    }

    /** 已解析的伞名列表（GUI 用），空时返回空列表 */
    public static List<String> listIds() {
        refresh();
        return new ArrayList<>(idList);
    }

    /**
     * 该伞是不是「服务器文件夹里没有、只好用玩家本地那份」——GUI 用它给条目加个来源标记，
     * 免得玩家以为服务器下发的伞没生效。
     */
    public static boolean isLocalFallback(String name) {
        refresh();
        if (entries == null) return false;
        Entry entry = entries.get(name);
        // 只有在「服务器伞源生效」时才叫兜底：单机/主机时全部伞本来就来自 local
        return entry != null
                && ParachuteManager.LOCAL_FOLDER_NAME.equals(entry.source())
                && !ParachuteScope.clientScope().isLocal();
    }

    /** 该伞的伞源标签（{@code local} / {@code server/<地址>}），没有这把伞返回 null */
    @Nullable
    public static String sourceOf(String name) {
        refresh();
        Entry entry = entries == null ? null : entries.get(name);
        return entry == null ? null : entry.source();
    }

    /** 强制立即重新扫描（选择界面打开时调用） */
    public static void forceRefresh() {
        lastScan = 0;
        refresh();
    }

    /**
     * 热加载扫描：节流调用；按伞源优先级扫描，检测到文件夹变化（新增/修改/删除）时只重载变化的伞。
     * 首次调用等价于全量加载。
     */
    public static synchronized void refresh() {
        long now = System.currentTimeMillis();
        if (cache == null) {
            cache = new HashMap<>();
            entries = new HashMap<>();
            idList = new ArrayList<>();
            lastScan = 0;
        } else if (now - lastScan < SCAN_INTERVAL_MS) {
            return;
        }
        lastScan = now;

        // 按优先级收集：同名伞只保留优先级最高的那一个
        Map<String, Entry> found = new LinkedHashMap<>();
        for (ParachuteScope.Source source : activeSources()) {
            Path root = source.root();
            if (!Files.isDirectory(root)) continue;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
                for (Path dir : ds) {
                    if (!Files.isDirectory(dir)) continue;
                    String id = dir.getFileName().toString();
                    if (found.containsKey(id)) continue;
                    if (findModel(dir) == null) continue;
                    found.put(id, new Entry(dir, source.label()));
                }
            } catch (IOException e) {
                ParachuteMod.LOGGER.warn("Failed to scan parachute source '{}': {}", root, e.toString());
            }
        }

        Map<String, BakedParachute> next = new HashMap<>();
        for (Map.Entry<String, Entry> e : found.entrySet()) {
            String id = e.getKey();
            Entry entry = e.getValue();
            String key = keyOf(entry.source(), id);
            long sig = folderSignature(entry.dir());
            Long old = signatures.get(key);
            BakedParachute baked = cache.get(key);
            if (baked == null || old == null || old != sig) {
                signatures.put(key, sig);
                // 要重载了：先把旧的 GPU 缓冲释放掉，否则显存泄漏
                if (baked != null) {
                    closeGpu(key, baked);
                }
                baked = loadOne(entry.dir(), id, entry.source());
                if (baked != null) {
                    ParachuteMod.LOGGER.info("Hot-loaded parachute '{}' from {}", id, entry.source());
                } else {
                    ParachuteMod.LOGGER.warn("Parachute '{}' failed to load; removed", key);
                    continue;
                }
            }
            next.put(key, baked);
        }

        // 移除已删除的伞
        for (String gone : new ArrayList<>(cache.keySet())) {
            if (!next.containsKey(gone)) {
                closeGpu(gone, cache.get(gone));
                cache.remove(gone);
                signatures.remove(gone);
                ParachuteMod.LOGGER.info("Removed parachute '{}' (folder gone)", gone);
            }
        }

        cache = next;
        entries = found;
        idList = new ArrayList<>(found.keySet());
        idList.sort(String::compareTo);
        if (!loggedInitial) {
            loggedInitial = true;
            ParachuteMod.LOGGER.info("Loaded {} parachute(s) for source '{}'",
                    idList.size(), ParachuteScope.clientScope().label());
        }
    }

    /** 释放一把伞占用的 GPU 缓冲（热重载/删除时调用；必须在渲染线程） */
    private static void closeGpu(String key, @Nullable BakedParachute baked) {
        if (baked == null) return;
        for (Layer layer : baked.layers()) {
            if (layer.gpu() != null) {
                layer.gpu().close();
            }
        }
        ParachuteMod.LOGGER.debug("Released GPU buffers of '{}'", key);
    }

    /** 应用开伞动画：按比例把动画时间映射到 [0, length]，驱动骨骼关键帧 */
    public static void applyOpenAnimation(ModelPart root, AnimationDefinition anim, float ratio, Vector3f cache) {
        root.getAllParts().forEach(ModelPart::resetPose);
        float clamped = Mth.clamp(ratio, 0.0F, 1.0F);
        long ms = (long) (clamped * anim.lengthInSeconds() * 1000.0F);
        KeyframeAnimations.animate(new HierarchicalModel<Entity>() {
            @Override
            public ModelPart root() {
                return root;
            }

            @Override
            public void setupAnim(Entity entity, float a, float b, float c, float d, float e) {
            }
        }, anim, ms, 1.0F, cache);
    }

    @Nullable
    private static Path findBbmodel(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.bbmodel")) {
            for (Path p : ds) {
                return p;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /**
     * 文件夹里的模型文件：优先 {@code .bbmodel}（老格式，行为不变），
     * 其次 {@code .obj}（Blender 工作流：{@code xxx.obj} + {@code xxx.mtl} + {@code textures/}）。
     */
    @Nullable
    private static Path findModel(Path dir) {
        Path bb = findBbmodel(dir);
        return bb != null ? bb : findObj(dir);
    }

    @Nullable
    private static Path findObj(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.obj")) {
            for (Path p : ds) {
                return p;
            }
        } catch (IOException ignored) {
        }
        return null;
    }

    /** 文件夹签名 = 所有文件（bbmodel + png）的最大修改时间；无文件返回 -1 */
    private static long folderSignature(Path dir) {
        long max = -1;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) {
                    max = Math.max(max, Files.getLastModifiedTime(p).toMillis());
                }
            }
        } catch (IOException ignored) {
        }
        return max;
    }

    @Nullable
    private static BakedParachute loadOne(Path dir, String id, String source) {
        Path bbFile = findBbmodel(dir);
        if (bbFile == null) {
            // Blender OBJ 工作流：没有 .bbmodel 时按 <伞名>/xxx.obj + xxx.mtl + textures/ 加载
            return loadObjParachute(dir, id, source);
        }
        try {
            String json = Files.readString(bbFile, StandardCharsets.UTF_8);
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();

            ModelPart modelPart;
            AnimationDefinition anim;
            // 逐面 UV（基岩版 .bbmodel）走直接 ModelPart 构建；普通 box_uv 走 LayerDefinition
            if (BbModelParser.hasPerFaceUv(root)) {
                modelPart = BbModelParser.parseModelPart(root);
                if (modelPart == null) return null;
            } else {
                var layer = BbModelParser.parse(root);
                if (layer == null) return null;
                modelPart = layer.bakeRoot();
            }
            try (Reader reader = Files.newBufferedReader(bbFile)) {
                anim = BbAnimationParser.parse(reader);
            }
            // 无动画的模型也允许加载：anim 为 null，渲染时直接显示模型
            float lengthSeconds = anim != null ? anim.lengthInSeconds() : 1.0F;

            ResourceLocation texture = loadTexture(dir, id, source, root);
            ResourceLocation whiteTexture = loadWhiteTexture(dir, id, source, root);

            boolean bedrock = "bedrock".equalsIgnoreCase(
                    GsonHelper.getAsString(GsonHelper.getAsJsonObject(root, "meta", new JsonObject()),
                            "model_format", "modded_entity"));

            return new BakedParachute(id, List.of(new Layer(modelPart, texture, whiteTexture)),
                    anim, lengthSeconds, bedrock);
        } catch (Exception e) {
            ParachuteMod.LOGGER.warn("Failed to load parachute '{}/{}': {}", source, id, e.toString());
            return null;
        }
    }

    // ============================================================
    // OBJ 工作流（Blender）
    //   文件夹约定： <伞名>/xxx.obj + xxx.mtl + textures/*.png
    // ============================================================

    /**
     * 加载 OBJ 伞。
     *
     * <p><b>单贴图模型</b>（没有多个 {@code usemtl}，或所有材质指向同一张图）→ 只有一层，
     * 贴图的 ResourceLocation 和以前完全一致（{@code original} / {@code white}），染色走白底图；
     * <b>多材质模型</b> → 按贴图合并成多层，逐层渲染，染色时直接用原贴图 × 染料颜色
     * （不给几十张贴图都生成白底图：那会一次性上传几十张大图，代价太大）。</p>
     *
     * <p>没有动画（{@code openAnimation = null}）—— 渲染器的逻辑是"有动画才驱动开伞动画，
     * 无动画直接显示模型"，所以 OBJ 天然按完整展开状态渲染；
     * {@code bedrock = false}，走 modded_entity 那条朝向补偿分支。</p>
     */
    @Nullable
    private static BakedParachute loadObjParachute(Path dir, String id, String source) {
        Path objFile = findObj(dir);
        if (objFile == null) return null;

        ObjParser.ObjData data = ObjParser.parse(objFile);
        if (data == null) return null;

        ObjMesh mesh = ObjMesh.bake(data, OBJ_UNIT_SCALE);
        if (mesh.triangleCount() <= 0) {
            ParachuteMod.LOGGER.warn("OBJ parachute '{}/{}' has no usable triangle ({} face(s) dropped)",
                    source, id, data.droppedFaces);
            return null;
        }

        // 主贴图（兜底）：mtl 里第一个能解析到的 map_Kd，或 textures/ 里名字最小的图片
        Path primary = findObjPng(dir, data.materials);

        // 每个 (材质,对象) 组 → 它该用哪张图 + 透明度；按 (albedo, 遮罩, d) 合并成层
        Map<String, LayerSpec> byKey = new LinkedHashMap<>();
        int noMaterialMap = 0;
        for (ObjMesh.Group group : mesh.groups()) {
            ObjParser.Material material = data.materials.getOrDefault(group.material(), ObjParser.Material.DEFAULT);
            Path albedo = resolveImage(dir, material.diffuse());
            if (albedo == null) {
                albedo = primary;
                noMaterialMap++;
            }
            if (albedo == null) continue;
            Path mask = resolveImage(dir, material.alphaMask());
            String key = albedo + "\u0000" + (mask == null ? "" : mask.toString())
                    + "\u0000" + Math.round(material.dissolve() * 255.0F);
            LayerSpec spec = byKey.get(key);
            if (spec == null) {
                spec = new LayerSpec(albedo, mask, material.dissolve());
                byKey.put(key, spec);
            }
            spec.groups.add(group);
        }

        if (byKey.isEmpty()) {
            ParachuteMod.LOGGER.warn("OBJ parachute '{}/{}' has no texture; expected {}/<name>.png",
                    source, id, TEXTURE_DIR);
            return null;
        }

        boolean singleLayer = byKey.size() == 1;
        // 高面数模型烘进 GPU 缓冲（顶点常驻显存，每帧只 bind + draw）。
        //
        // 渲染结果已经逐项核对过与逐帧发射一致，差异只剩两处固有来源：
        //   - 顶点烘焙按 (光照, 染色) 分组，光照量化到 4 级（亮度差 <2%，肉眼无感）；
        //   - 逐帧路径走 MultiBufferSource 批次、烘焙路径是立即绘制，重合几何的 z-fighting
        //     胜者偶尔不同（和逐帧路径内部不同批次的相对顺序一样是任意的）。
        // 想临时退回逐帧发射做对比：-Dparachute.debug.nogpu=true
        boolean bake = mesh.triangleCount() >= BAKE_MIN_TRIANGLES
                && !Boolean.getBoolean("parachute.debug.nogpu");
        List<Layer> layers = new ArrayList<>(byKey.size());
        int index = 0;
        for (LayerSpec spec : byKey.values()) {
            ModelPart root = bake ? null : ObjModelBuilder.build(spec.groups);
            ObjGpuCache gpu = bake ? new ObjGpuCache(spec.groups) : null;
            // 是否真的需要半透明渲染：只有 d<1，或者贴图里有"大量中间 alpha"时才算。
            // 光看"有没有 map_d"是不够的 —— 例如这把 AH-64D 的遮罩 96~100% 是白色（=不透明），
            // 只有零星窗口是透明的；那种层必须用 cutout（透明处直接丢弃），
            // 否则整个机身会进半透明批次：看着像"机壳全是半透明"，而且 mipmap 把 alpha 平均掉之后
            // 离远了 alpha 趋近 0，模型就"消失"了。
            float semi = semiAlphaFraction(spec);
            boolean translucent = spec.dissolve < 0.999F || semi >= 0.20F;
            // 第一层沿用老后缀（单贴图模型的 RL 与以前一致），其余层按序号区分
            String texSuffix = index == 0 ? "original" : "m" + index + "_original";
            String whiteSuffix = index == 0 ? "white" : "m" + index + "_white";
            ResourceLocation texture = registerObjTexture(spec, source, id, texSuffix, false);
            ResourceLocation white = singleLayer && texture != null
                    ? registerObjTexture(spec, source, id, whiteSuffix, true)
                    : null;
            layers.add(new Layer(root, gpu, texture, white, translucent, spec.dissolve));
            if (translucent) {
                ParachuteMod.LOGGER.debug("OBJ layer {} of '{}/{}' is translucent (d={}, semiAlpha={}%)",
                        index, source, id, spec.dissolve, Math.round(semi * 100.0F));
            }
            index++;
        }

        ParachuteMod.LOGGER.info("Loaded OBJ parachute '{}/{}': {} triangle(s), {} object(s), {} texture layer(s), {}{}",
                source, id, mesh.triangleCount(), mesh.groups().size(), layers.size(),
                bake ? "baked to GPU buffers" : "per-frame emission",
                data.ngonFaces > 0 ? ", " + data.ngonFaces + " n-gon(s) fan-triangulated" : "");
        // 渲染顺序：不透明层（cutout）先画、半透明层次之。
        // MC 的 MultiBufferSource 按 RenderType 首次请求顺序刷批次，而半透明不做深度写入，
        // 顺序反了就会出现"半透明件被后面的不透明几何盖掉/遮挡关系错乱"。
        layers.sort(java.util.Comparator.comparing(Layer::translucent));
        int translucent = 0;
        for (Layer layer : layers) {
            if (layer.translucent()) translucent++;
        }
        if (translucent > 0) {
            ParachuteMod.LOGGER.info("OBJ parachute '{}/{}': {} layer(s) use translucent rendering (mtl d / map_d)",
                    source, id, translucent);
        }
        if (noMaterialMap > 0) {
            ParachuteMod.LOGGER.info("OBJ parachute '{}/{}': {} group(s) had no map_Kd, using the primary texture {}",
                    source, id, noMaterialMap, primary == null ? "<none>" : primary.getFileName());
        }
        if (data.droppedFaces > 0) {
            ParachuteMod.LOGGER.warn("OBJ parachute '{}/{}': {} face(s) dropped (degenerate/duplicate/out-of-range)",
                    source, id, data.droppedFaces);
        }

        // 几何半径：模型原点（附着点）到最远角点的距离。渲染器用它算渲染包围盒 —— 模型会旋转/摆动，
        // 所以要用"球半径"这种旋转无关的量；包围盒太小模型会随视角消失，太大会白白多画。
        double radiusSq = 0.0D;
        for (ObjMesh.Group group : mesh.groups()) {
            for (int corner = 0; corner < 8; corner++) {
                double x = (corner & 1) == 0 ? group.minX() : group.maxX();
                double y = (corner & 2) == 0 ? group.minY() : group.maxY();
                double z = (corner & 4) == 0 ? group.minZ() : group.maxZ();
                radiusSq = Math.max(radiusSq, x * x + y * y + z * z);
            }
        }
        float renderRadius = (float) Math.sqrt(radiusSq);
        ParachuteMod.LOGGER.info("OBJ parachute '{}/{}': render radius {} block(s)", source, id,
                String.format("%.1f", renderRadius));

        return new BakedParachute(id, List.copyOf(layers), null, 1.0F, false, renderRadius);
    }

    /**
     * 该层贴图（已合成 {@code map_d} 遮罩）里"中间 alpha"像素的比例。
     * <p>用来判断这一层到底要不要半透明渲染：接近 0 → 只有全透/全不透，cutout 就够；
     * 明显大于 0（玻璃、渐变）→ 必须走半透明，否则中间 alpha 会被 cutout 当二值处理。</p>
     */
    private static float semiAlphaFraction(LayerSpec spec) {
        try (InputStream in = Files.newInputStream(spec.albedo)) {
            NativeImage image = NativeImage.read(in);
            if (spec.mask != null) {
                image = applyAlphaMask(image, spec.mask);
            }
            int w = image.getWidth();
            int h = image.getHeight();
            long semi = 0;
            long total = 0;
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int a = (image.getPixelRGBA(x, y) >>> 24) & 0xFF;
                    if (a > 0 && a < 250) semi++;
                    total++;
                }
            }
            image.close();
            return total == 0 ? 0.0F : (float) semi / (float) total;
        } catch (Exception e) {
            ParachuteMod.LOGGER.warn("Failed to inspect alpha of '{}': {}", spec.albedo, e.toString());
            return 0.0F;
        }
    }

    /** 一层在构建期的规格：同一张 albedo + 同一个遮罩 + 同一个 d 的组合 */
    private static final class LayerSpec {
        final Path albedo;
        @Nullable
        final Path mask;
        final float dissolve;
        final List<ObjMesh.Group> groups = new ArrayList<>();

        LayerSpec(Path albedo, @Nullable Path mask, float dissolve) {
            this.albedo = albedo;
            this.mask = mask;
            this.dissolve = dissolve;
        }
    }

    /** 材质名对应的图片文件：{@code textures/<文件名>} 优先，其次伞文件夹根目录 */
    @Nullable
    private static Path resolveImage(Path dir, @Nullable String fileName) {
        if (fileName == null || fileName.isEmpty()) return null;
        Path inTextureDir = dir.resolve(TEXTURE_DIR).resolve(fileName);
        if (Files.isRegularFile(inTextureDir)) return inTextureDir;
        Path plain = dir.resolve(fileName);
        return Files.isRegularFile(plain) ? plain : null;
    }

    /**
     * OBJ 的主贴图（兜底用）：先按 {@code .mtl} 里 {@code map_Kd} 的文件名在 {@code textures/} 里找，
     * 其次伞文件夹根目录，再退到 {@code textures/} 里名字最小的图片，最后才是根目录里最小的。
     */
    @Nullable
    private static Path findObjPng(Path dir, Map<String, ObjParser.Material> materials) {
        for (ObjParser.Material material : materials.values()) {
            Path image = resolveImage(dir, material.diffuse());
            if (image != null) return image;
        }
        for (ObjParser.Material material : materials.values()) {
            Path image = resolveImage(dir, material.alphaMask());
            if (image != null) return image;
        }
        Path first = firstPng(dir.resolve(TEXTURE_DIR));
        return first != null ? first : firstPng(dir);
    }

    /** 目录里文件名最小的 png（保证结果稳定，不受文件系统顺序影响） */
    @Nullable
    private static Path firstPng(Path dir) {
        if (!Files.isDirectory(dir)) return null;
        Path best = null;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.png")) {
            for (Path p : ds) {
                if (!Files.isRegularFile(p)) continue;
                if (best == null || p.getFileName().toString().compareTo(best.getFileName().toString()) < 0) {
                    best = p;
                }
            }
        } catch (IOException ignored) {
        }
        return best;
    }

    /**
     * 注册一层的贴图；{@code white = true} 时生成白色底贴图（染色用）。
     *
     * <p>如果材质带 {@code map_d} 透明度遮罩，先把遮罩的 alpha 合成进 albedo 的副本 ——
     * MC 的实体渲染一次只能采样一张贴图，没法同时传 albedo 和遮罩，所以必须在加载时合成。</p>
     */
    @Nullable
    private static ResourceLocation registerObjTexture(LayerSpec spec, String source, String id,
                                                      String suffix, boolean white) {
        try (InputStream in = Files.newInputStream(spec.albedo)) {
            NativeImage image = NativeImage.read(in);
            if (spec.mask != null) {
                image = applyAlphaMask(image, spec.mask);
            }
            if (white) {
                image = toWhiteBase(image);
            }
            ResourceLocation location = textureLocation(source, id, suffix);
            registerTexture(location, image);
            return location;
        } catch (Exception e) {
            ParachuteMod.LOGGER.warn("Failed to load OBJ texture '{}' for '{}/{}': {}",
                    spec.albedo, source, id, e.toString());
            return null;
        }
    }

    /**
     * 把 {@code map_d} 遮罩的 alpha 合成到 albedo 的副本上（会关掉入参 image）。
     *
     * <p>遮罩规则：遮罩<b>自身带 alpha</b>（存在 &lt; 250 的像素）时用它的 alpha；
     * 否则当成灰度图用亮度（用 alpha=1 的普通 PNG 当遮罩时很常见）。</p>
     */
    private static NativeImage applyAlphaMask(NativeImage image, Path maskFile) {
        NativeImage mask;
        try (InputStream in = Files.newInputStream(maskFile)) {
            mask = NativeImage.read(in);
        } catch (Exception e) {
            ParachuteMod.LOGGER.warn("Failed to read alpha mask '{}': {}", maskFile, e.toString());
            return image;
        }
        int w = image.getWidth();
        int h = image.getHeight();
        if (mask.getWidth() != w || mask.getHeight() != h) {
            ParachuteMod.LOGGER.warn("Alpha mask '{}' is {}x{}, albedo is {}x{}; mask ignored",
                    maskFile.getFileName(), mask.getWidth(), mask.getHeight(), w, h);
            mask.close();
            return image;
        }
        boolean maskHasAlpha = false;
        for (int y = 0; y < h && !maskHasAlpha; y++) {
            for (int x = 0; x < w; x++) {
                if (((mask.getPixelRGBA(x, y) >>> 24) & 0xFF) < 250) {
                    maskHasAlpha = true;
                    break;
                }
            }
        }
        NativeImage out = new NativeImage(w, h, true);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgba = image.getPixelRGBA(x, y);
                int m = mask.getPixelRGBA(x, y);
                int a;
                if (maskHasAlpha) {
                    a = (m >>> 24) & 0xFF;
                } else {
                    int r = (m >> 16) & 0xFF;
                    int g = (m >> 8) & 0xFF;
                    int b = m & 0xFF;
                    a = (int) (0.299F * r + 0.587F * g + 0.114F * b);
                }
                out.setPixelRGBA(x, y, (a << 24) | (rgba & 0x00FFFFFF));
            }
        }
        mask.close();
        image.close();
        return out;
    }

    /**
     * 贴图的 {@link ResourceLocation}：{@code parachute/<来源>/<伞名>/<后缀>}。
     * 来源和伞名都过一遍 {@link #rlSafe}，保证路径合法；同名但来源不同的两把伞因此各有一张贴图。
     */
    private static ResourceLocation textureLocation(String source, String id, String suffix) {
        return ResourceLocation.fromNamespaceAndPath(ParachuteMod.MOD_ID,
                "parachute/" + source + "/" + rlSafe(id) + "/" + suffix);
    }

    /** 把文件夹名压成 ResourceLocation 合法字符（{@code [a-z0-9._-]}） */
    private static String rlSafe(String name) {
        StringBuilder sb = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = Character.toLowerCase(name.charAt(i));
            boolean safe = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '_' || c == '-' || c == '.';
            sb.append(safe ? c : '_');
        }
        return sb.toString();
    }

    /**
     * 原始贴图加载：优先读伞文件夹里的 {@code <贴图名>.png}（原贴图），缺失时用 .bbmodel 内嵌的 base64。
     * 未染色状态显示它（不染色）。
     */
    @Nullable
    private static ResourceLocation loadTexture(Path dir, String id, String source, JsonObject bbRoot) {
        try {
            NativeImage image = loadNativeImage(dir, id, bbRoot);
            if (image == null) return null;

            ResourceLocation location = textureLocation(source, id, "original");
            registerTexture(location, image);
            return location;
        } catch (Exception e) {
            ParachuteMod.LOGGER.warn("Failed to load texture for parachute '{}/{}': {}", source, id, e.toString());
            return null;
        }
    }

    /**
     * 白色底贴图加载：从原贴图按亮度生成（供染色用）。
     * 染色状态 = 白色底 × 染料 ARGB。
     */
    @Nullable
    private static ResourceLocation loadWhiteTexture(Path dir, String id, String source, JsonObject bbRoot) {
        try {
            NativeImage image = loadNativeImage(dir, id, bbRoot);
            if (image == null) return null;
            NativeImage white = toWhiteBase(image);

            ResourceLocation location = textureLocation(source, id, "white");
            registerTexture(location, white);
            return location;
        } catch (Exception e) {
            ParachuteMod.LOGGER.warn("Failed to generate white texture for parachute '{}/{}': {}", source, id, e.toString());
            return null;
        }
    }

    /** 读取原始贴图像素：文件夹 PNG 优先，缺失用 .bbmodel 内嵌 base64 */
    @Nullable
    private static NativeImage loadNativeImage(Path dir, String id, JsonObject bbRoot) throws IOException {
        JsonArray textures = bbRoot.has("textures") ? bbRoot.getAsJsonArray("textures") : new JsonArray();
        String texName = "";
        byte[] embedded = null;
        for (JsonElement te : textures) {
            if (!te.isJsonObject()) continue;
            JsonObject t = te.getAsJsonObject();
            if (texName.isEmpty() && t.has("name")) {
                texName = t.get("name").getAsString();
            }
            if (embedded == null && t.has("source")) {
                embedded = decodeBase64(t.get("source").getAsString());
            }
        }
        if (texName.isEmpty()) texName = id + ".png";

        Path pngFile = dir.resolve(texName);
        if (Files.isRegularFile(pngFile)) {
            try (InputStream in = Files.newInputStream(pngFile)) {
                return NativeImage.read(in);
            }
        }
        if (embedded != null) {
            try (InputStream in = new ByteArrayInputStream(embedded)) {
                return NativeImage.read(in);
            }
        }
        return null;
    }

    /** 贴图最大边长：超过就盒式降采样（2048² → 1024²，显存省 4×，也顺带减轻摩尔纹） */
    private static final int MAX_TEXTURE_SIZE = 1024;

    /** 注册并上传贴图（refresh 均在渲染线程调用，GL 操作安全） */
    private static void registerTexture(ResourceLocation location, NativeImage image) {
        ParachuteTexture texture = new ParachuteTexture(downscaleIfNeeded(image));
        Minecraft.getInstance().getTextureManager().register(location, texture);
        texture.load(Minecraft.getInstance().getResourceManager());
    }

    /** 盒式降采样：超过 {@link #MAX_TEXTURE_SIZE} 时缩小，否则原样返回（会关掉入参） */
    private static NativeImage downscaleIfNeeded(NativeImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        int max = Math.max(w, h);
        if (max <= MAX_TEXTURE_SIZE) return src;

        int nw = Math.max(1, (int) ((long) w * MAX_TEXTURE_SIZE / max));
        int nh = Math.max(1, (int) ((long) h * MAX_TEXTURE_SIZE / max));
        NativeImage out = new NativeImage(nw, nh, true);
        for (int y = 0; y < nh; y++) {
            int sy0 = y * h / nh;
            int sy1 = Math.max(sy0 + 1, (y + 1) * h / nh);
            for (int x = 0; x < nw; x++) {
                int sx0 = x * w / nw;
                int sx1 = Math.max(sx0 + 1, (x + 1) * w / nw);
                int a = 0;
                int r = 0;
                int g = 0;
                int b = 0;
                int n = 0;
                for (int sy = sy0; sy < sy1; sy++) {
                    for (int sx = sx0; sx < sx1; sx++) {
                        int p = src.getPixelRGBA(sx, sy);
                        a += (p >>> 24) & 0xFF;
                        r += (p >> 16) & 0xFF;
                        g += (p >> 8) & 0xFF;
                        b += p & 0xFF;
                        n++;
                    }
                }
                if (n == 0) n = 1;
                out.setPixelRGBA(x, y, ((a / n) << 24) | ((r / n) << 16) | ((g / n) << 8) | (b / n));
            }
        }
        src.close();
        return out;
    }

    /** 按亮度生成白色底：像素 = 白色 × (亮度 / 最大亮度)，保留 alpha；调用后 src 被关闭 */
    private static NativeImage toWhiteBase(NativeImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        float maxLum = 0.0F;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgba = src.getPixelRGBA(x, y);
                if (((rgba >>> 24) & 0xFF) > 0) {
                    float lum = luminance(rgba);
                    if (lum > maxLum) maxLum = lum;
                }
            }
        }
        if (maxLum <= 0.0F) maxLum = 1.0F;

        NativeImage out = new NativeImage(w, h, true);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgba = src.getPixelRGBA(x, y);
                int a = (rgba >>> 24) & 0xFF;
                int v = 0;
                if (a > 0) {
                    v = (int) Math.min(255.0F, luminance(rgba) / maxLum * 255.0F);
                }
                out.setPixelRGBA(x, y, (a << 24) | (v << 16) | (v << 8) | v);
            }
        }
        src.close();
        return out;
    }

    private static float luminance(int rgba) {
        int r = (rgba >> 16) & 0xFF;
        int g = (rgba >> 8) & 0xFF;
        int b = rgba & 0xFF;
        return (0.299F * r + 0.587F * g + 0.114F * b) / 255.0F;
    }

    private static byte[] decodeBase64(String source) {
        int idx = source.indexOf(',');
        String b64 = idx >= 0 ? source.substring(idx + 1) : source;
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
