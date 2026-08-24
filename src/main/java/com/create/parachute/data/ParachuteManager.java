package com.create.parachute.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.create.parachute.ExampleMod;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 伞包文件夹管理器。
 *
 * <p>模组成功加载时在游戏根目录创建 {@code parachute/}，每个子文件夹是一把伞，
 * 内含 {@code <伞名>.bbmodel}（模型+动画+内嵌贴图）和可选的同目录 {@code .png} 贴图。
 * 首次加载时自动把内置的 5 把伞（大伞/小伞系列）从模组资源导出到该文件夹。</p>
 */
public final class ParachuteManager {

    /** 伞包根文件夹名（游戏根目录下） */
    public static final String FOLDER_NAME = "parachute";
    /** 未选择伞时的默认伞（蘑菇伞） */
    public static final String DEFAULT_PARACHUTE = "mushroom";
    /** 内置伞 id（对应 resources/models/entity/*.bbmodel） */
    public static final List<String> BUILTIN_IDS = List.of(
            "parachute", "parachute1", "mushroom", "bigparachute", "bigparachute2");

    private static final String ASSET_MODEL_DIR = "assets/" + ExampleMod.MOD_ID + "/models/entity";

    private ParachuteManager() {
    }

    /** 游戏根目录下的 parachute 文件夹（如不存在则创建） */
    public static Path rootFolder() {
        Path dir = FMLPaths.GAMEDIR.get().resolve(FOLDER_NAME);
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir;
    }

    /**
     * 模组加载时调用：确保 parachute/ 文件夹存在。
     * 首次加载（文件夹原本不存在）导出全部内置伞；
     * 后续加载只确保蘑菇伞存在（其他伞玩家可自行删除，不会重新导出）。
     */
    public static void ensureParachuteFolder() {
        Path root = FMLPaths.GAMEDIR.get().resolve(FOLDER_NAME);
        boolean firstLoad = !Files.isDirectory(root);
        if (firstLoad) {
            for (String id : BUILTIN_IDS) {
                exportIfMissing(root, id);
            }
        } else {
            // 后续进入世界：只补全蘑菇伞
            exportIfMissing(root, DEFAULT_PARACHUTE);
        }
    }

    /** 若目标伞文件夹缺失（或无 .bbmodel）则从内置资源导出 */
    private static void exportIfMissing(Path root, String id) {
        Path dir = root.resolve(id);
        try {
            if (Files.isDirectory(dir) && hasBbmodel(dir)) {
                return;
            }
            Files.createDirectories(dir);
            exportBuiltin(id, dir);
            ExampleMod.LOGGER.info("Exported built-in parachute '{}' to {}", id, dir);
        } catch (Exception e) {
            ExampleMod.LOGGER.warn("Failed to export built-in parachute '{}': {}", id, e.toString());
        }
    }

    /** 该伞文件夹下是否已有 .bbmodel */
    public static boolean hasBbmodel(Path dir) {
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.bbmodel")) {
            return ds.iterator().hasNext();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 扫描 parachute/ 下所有含 .bbmodel 的子文件夹，返回伞 id 列表。
     * 客户端 GUI 用它展示可选的伞；服务端也可用（仅作展示）。
     */
    public static List<String> listParachuteIds() {
        List<String> ids = new ArrayList<>();
        Path root = rootFolder();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(root)) {
            for (Path p : ds) {
                if (Files.isDirectory(p) && hasBbmodel(p)) {
                    ids.add(p.getFileName().toString());
                }
            }
        } catch (IOException ignored) {
        }
        ids.sort(String::compareTo);
        return ids;
    }

    /**
     * 从类路径资源导出内置伞：复制 .bbmodel 和其引用的贴图到目标目录。
     * 贴图优先取类路径资源（按 relative_path 解析），缺失时用 .bbmodel 内嵌的 base64。
     */
    private static void exportBuiltin(String id, Path dir) throws IOException {
        String bbResource = ASSET_MODEL_DIR + "/" + id + ".bbmodel";
        String bbText = readClasspathString(bbResource);
        if (bbText == null) {
            ExampleMod.LOGGER.warn("Built-in bbmodel '{}' missing from classpath", bbResource);
            return;
        }
        Path bbFile = dir.resolve(id + ".bbmodel");
        Files.writeString(bbFile, bbText, StandardCharsets.UTF_8);

        try {
            JsonObject root = JsonParser.parseString(bbText).getAsJsonObject();
            JsonArray textures = root.has("textures") ? root.getAsJsonArray("textures") : new JsonArray();
            for (JsonElement te : textures) {
                if (!te.isJsonObject()) continue;
                JsonObject t = te.getAsJsonObject();
                String rel = t.has("relative_path") ? t.get("relative_path").getAsString() : "";
                String name = t.has("name") ? t.get("name").getAsString() : "";
                if (name.isEmpty()) continue;
                // 解析相对路径：bbmodel 位于 assets/<mod>/models/entity/
                String resourcePath = resolveRelativeAsset(rel, name);
                Path target = dir.resolve(name);
                byte[] bytes = readClasspathBytes(resourcePath);
                if (bytes == null) {
                    // 退路：内嵌 base64
                    String source = t.has("source") ? t.get("source").getAsString() : "";
                    bytes = decodeEmbeddedPng(source);
                }
                if (bytes != null && bytes.length > 0) {
                    Files.write(target, bytes);
                }
            }
        } catch (Exception e) {
            ExampleMod.LOGGER.warn("Failed to export textures for built-in '{}': {}", id, e.toString());
        }
    }

    /** 把 bbmodel 的相对贴图路径解析为类路径资源（assets/ 开头） */
    private static String resolveRelativeAsset(String relativePath, String fileName) {
        if (relativePath.isEmpty()) {
            return "assets/" + ExampleMod.MOD_ID + "/textures/entity/" + fileName;
        }
        // bbmodel 逻辑目录：assets/<mod>/models/entity
        Path base = Path.of("assets", ExampleMod.MOD_ID, "models", "entity");
        Path resolved = base.resolve(relativePath.replace('\\', '/')).normalize();
        String s = resolved.toString().replace('\\', '/');
        return s.startsWith("/") ? s.substring(1) : s;
    }

    @Nullable
    private static String readClasspathString(String resource) {
        byte[] bytes = readClasspathBytes(resource);
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }

    @Nullable
    private static byte[] readClasspathBytes(String resource) {
        try (InputStream in = ParachuteManager.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) return null;
            return in.readAllBytes();
        } catch (IOException e) {
            return null;
        }
    }

    /** 解码 "data:image/png;base64,..." 内嵌贴图 */
    @Nullable
    private static byte[] decodeEmbeddedPng(String source) {
        if (source == null || source.isEmpty()) return null;
        int idx = source.indexOf(',');
        String b64 = idx >= 0 ? source.substring(idx + 1) : source;
        try {
            return Base64.getDecoder().decode(b64);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
