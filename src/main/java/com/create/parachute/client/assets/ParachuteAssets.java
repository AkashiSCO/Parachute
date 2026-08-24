package com.create.parachute.client.assets;

import com.create.parachute.ExampleMod;
import com.create.parachute.data.ParachuteManager;
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
import java.util.List;
import java.util.Map;

/**
 * 客户端伞资源管理器（热加载）：扫描游戏根目录 {@code parachute/}，解析每把伞的
 * .bbmodel（模型 + 开伞动画 + 贴图），按伞名缓存。
 *
 * <p><b>热加载</b>：每次访问时按文件夹内文件修改时间判断是否有变化（节流 0.2 秒），
 * 新增/修改/删除伞自动生效，无需重启、无需手动刷新。GUI 打开时强制刷新。</p>
 *
 * <p>查找不存在的伞名时自动回退 {@link ParachuteManager#DEFAULT_PARACHUTE}（蘑菇伞）——
 * 多人游戏中其他玩家使用本机没有的伞时即走此路径。</p>
 */
public final class ParachuteAssets {

    /** 一把伞的完整渲染数据 */
    public record BakedParachute(String id, ModelPart root, AnimationDefinition openAnimation,
                                 ResourceLocation texture, ResourceLocation whiteTexture, float lengthSeconds,
                                 boolean bedrock) {
    }

    private static Map<String, BakedParachute> cache;
    private static List<String> idList;
    /** 伞 id → 文件夹签名（全部文件最大修改时间），用于热加载检测 */
    private static final Map<String, Long> signatures = new HashMap<>();
    private static long lastScan;
    private static boolean loggedInitial;

    /** 热扫描节流间隔（毫秒）：导入/修改伞后约 0.2 秒内自动可见 */
    private static final long SCAN_INTERVAL_MS = 200L;

    private ParachuteAssets() {
    }

    /** 获取伞名对应的渲染数据；不存在时回退蘑菇伞；蘑菇伞也没有则返回 null（渲染器跳过） */
    @Nullable
    public static BakedParachute get(String name) {
        refresh();
        if (name == null || name.isEmpty()) {
            return cache.get(ParachuteManager.DEFAULT_PARACHUTE);
        }
        BakedParachute p = cache.get(name);
        if (p != null) return p;
        return cache.get(ParachuteManager.DEFAULT_PARACHUTE);
    }

    /** 该伞是否为 bedrock 模式（lav25 之类）。影响渲染朝向补偿：bedrock 需额外绕 Y 旋转。 */
    public static boolean isBedrock(String name) {
        BakedParachute p = get(name);
        return p != null && p.bedrock();
    }

    /** 已解析的伞 id 列表（GUI 用），空时返回空列表 */
    public static List<String> listIds() {
        refresh();
        return new ArrayList<>(idList);
    }

    /** 强制立即重新扫描（选择界面打开时调用） */
    public static void forceRefresh() {
        lastScan = 0;
        refresh();
    }

    /**
     * 热加载扫描：节流调用；检测到文件夹变化（新增/修改/删除）时只重载变化的伞。
     * 首次调用等价于全量加载。
     */
    public static synchronized void refresh() {
        long now = System.currentTimeMillis();
        if (cache == null) {
            cache = new HashMap<>();
            idList = new ArrayList<>();
            lastScan = 0;
        } else if (now - lastScan < SCAN_INTERVAL_MS) {
            return;
        }
        lastScan = now;

        Path root = ParachuteManager.rootFolder();
        List<String> found = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path dir : ds) {
                if (!Files.isDirectory(dir)) continue;
                String id = dir.getFileName().toString();
                Path bb = findBbmodel(dir);
                if (bb == null) continue;
                found.add(id);
                long sig = folderSignature(dir);
                Long old = signatures.get(id);
                if (!cache.containsKey(id) || old == null || old != sig) {
                    signatures.put(id, sig);
                    BakedParachute baked = loadOne(dir, id);
                    if (baked != null) {
                        cache.put(id, baked);
                        ExampleMod.LOGGER.info("Hot-loaded parachute '{}'", id);
                    } else {
                        cache.remove(id);
                        ExampleMod.LOGGER.warn("Parachute '{}' failed to load; removed", id);
                    }
                }
            }
        } catch (IOException e) {
            ExampleMod.LOGGER.warn("Failed to scan parachute folder: {}", e.toString());
        }

        // 移除已删除的伞
        for (String id : new ArrayList<>(cache.keySet())) {
            if (!found.contains(id)) {
                cache.remove(id);
                signatures.remove(id);
                ExampleMod.LOGGER.info("Removed parachute '{}' (folder gone)", id);
            }
        }
        idList = new ArrayList<>(found);
        idList.sort(String::compareTo);
        if (!loggedInitial) {
            loggedInitial = true;
            ExampleMod.LOGGER.info("Loaded {} parachutes from {}", idList.size(), root);
        }
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
    private static BakedParachute loadOne(Path dir, String id) {
        try {
            Path bbFile = findBbmodel(dir);
            if (bbFile == null) return null;

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

            ResourceLocation texture = loadTexture(dir, id, root);
            ResourceLocation whiteTexture = loadWhiteTexture(dir, id, root);

            boolean bedrock = "bedrock".equalsIgnoreCase(
                    GsonHelper.getAsString(GsonHelper.getAsJsonObject(root, "meta", new JsonObject()),
                            "model_format", "modded_entity"));

            return new BakedParachute(id, modelPart, anim, texture, whiteTexture, lengthSeconds, bedrock);
        } catch (Exception e) {
            ExampleMod.LOGGER.warn("Failed to load parachute '{}': {}", id, e.toString());
            return null;
        }
    }

    /**
     * 原始贴图加载：优先读伞文件夹里的 {@code <贴图名>.png}（原贴图），缺失时用 .bbmodel 内嵌的 base64。
     * 未染色状态显示它（不染色）。
     */
    @Nullable
    private static ResourceLocation loadTexture(Path dir, String id, JsonObject bbRoot) {
        try {
            NativeImage image = loadNativeImage(dir, id, bbRoot);
            if (image == null) return null;

            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                    ExampleMod.MOD_ID, "parachute/" + id + "/original");
            registerTexture(location, image);
            return location;
        } catch (Exception e) {
            ExampleMod.LOGGER.warn("Failed to load texture for parachute '{}': {}", id, e.toString());
            return null;
        }
    }

    /**
     * 白色底贴图加载：从原贴图按亮度生成（供染色用）。
     * 染色状态 = 白色底 × 染料 ARGB。
     */
    @Nullable
    private static ResourceLocation loadWhiteTexture(Path dir, String id, JsonObject bbRoot) {
        try {
            NativeImage image = loadNativeImage(dir, id, bbRoot);
            if (image == null) return null;
            NativeImage white = toWhiteBase(image);

            ResourceLocation location = ResourceLocation.fromNamespaceAndPath(
                    ExampleMod.MOD_ID, "parachute/" + id + "/white");
            registerTexture(location, white);
            return location;
        } catch (Exception e) {
            ExampleMod.LOGGER.warn("Failed to generate white texture for parachute '{}': {}", id, e.toString());
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

    /** 注册并上传贴图（refresh 均在渲染线程调用，GL 操作安全） */
    private static void registerTexture(ResourceLocation location, NativeImage image) {
        ParachuteTexture texture = new ParachuteTexture(image);
        Minecraft.getInstance().getTextureManager().register(location, texture);
        texture.load(Minecraft.getInstance().getResourceManager());
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
