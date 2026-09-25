package com.create.parachute.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.create.parachute.ParachuteMod;
import net.neoforged.fml.loading.FMLPaths;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 伞包文件夹管理器：管理游戏根目录下的 {@code parachute/} 及其子目录。
 *
 * <p>每个伞文件夹是一把伞，内含 {@code <伞名>.bbmodel}（模型+动画+内嵌贴图）和可选的同目录
 * {@code .png} 贴图。首次加载（文件夹原本不存在）时自动把内置的 5 把伞（大伞/小伞系列）
 * 从模组资源导出到该文件夹。</p>
 *
 * <h2>目录结构</h2>
 * <pre>
 * 服务端（专用服务器 / 单机 / LAN 主机）
 * parachute/&lt;伞名&gt;/…            ← 服务端伞库，布局从未变过
 *
 * 客户端（玩家本机）
 * parachute/
 * ├─ &lt;伞名&gt;/…                   ← 自己开世界时的伞库（单机 / LAN 主机）
 * ├─ local/&lt;伞名&gt;/…             ← 玩家自己的伞：上传来源，单机/主机时的伞源
 * └─ server/&lt;服务器存档UUID&gt;/&lt;伞名&gt;/… ← 从该服务器下载/分发下来的伞（拿不到 UUID 时按地址兜底）
 * </pre>
 *
 * <p>「当前该用哪个文件夹」由 {@link ParachuteScope} 决定；服务端伞库永远是
 * {@link #rootFolder()} 本身，所以 {@code /parachute list|upload|download|distribute}
 * 的行为不受影响。</p>
 *
 * <p>文件在两端的搬运见 {@link ParachuteTransfer}。</p>
 */
public final class ParachuteManager {

    /** 伞包根文件夹名（游戏根目录下） */
    public static final String FOLDER_NAME = "parachute";
    /** 客户端自己的伞文件夹名（{@code parachute/local}） */
    public static final String LOCAL_FOLDER_NAME = "local";
    /** 按服务器分文件夹的父目录名（{@code parachute/server/<服务器存档 UUID>}） */
    public static final String SERVER_FOLDER_NAME = "server";
    /** 未选择伞时的默认伞（蘑菇伞） */
    public static final String DEFAULT_PARACHUTE = "mushroom";
    /**
     * 模组自带的伞 id（对应 {@code resources/assets/.../models/entity/<id>.bbmodel}）。
     *
     * <p><b>它们只留在 jar 里，不会自动导出到文件夹</b>：只有 {@link #DEFAULT_PARACHUTE}
     * （蘑菇伞，渲染兜底）会在缺失时补进 {@code parachute/} 与 {@code parachute/local/}。
     * 这个列表保留下来当"自带伞有哪些"的说明/API，导出逻辑不再使用它。</p>
     */
    public static final List<String> BUILTIN_IDS = List.of(
            "parachute", "parachute1", "mushroom", "bigparachute", "bigparachute2");

    private static final String ASSET_MODEL_DIR = "assets/" + ParachuteMod.MOD_ID + "/models/entity";

    private ParachuteManager() {
    }

    // ============================================================
    // 路径
    // ============================================================

    /** {@code parachute/} 的路径（不创建目录） */
    public static Path rootPath() {
        return FMLPaths.GAMEDIR.get().resolve(FOLDER_NAME);
    }

    /** {@code parachute/local} 的路径（不创建目录） */
    public static Path localPath() {
        return rootPath().resolve(LOCAL_FOLDER_NAME);
    }

    /**
     * {@code parachute/server/<folder>} 的路径（不创建目录）。
     * {@code folder} 要么是服务端同步来的存档 UUID，要么是兜底的服务器地址
     * （{@link ParachuteScope#sanitizeServerFolder} / {@link ParachuteScope#canonicalUuid} 生成）；
     * 这里再做一道兜底，任何带分隔符/上级目录的名字都会被换成 {@code unknown}，绝不会越出 {@code parachute/}。
     */
    public static Path serverPath(String folder) {
        return rootPath().resolve(SERVER_FOLDER_NAME).resolve(safeFolder(folder));
    }

    /** 游戏根目录下的 {@code parachute} 文件夹（如不存在则创建） */
    public static Path rootFolder() {
        return createDir(rootPath());
    }

    private static Path createDir(Path dir) {
        try {
            Files.createDirectories(dir);
        } catch (IOException ignored) {
        }
        return dir;
    }

    /** 兜底：把不能当文件夹名的东西统一换成 {@code unknown} */
    private static String safeFolder(String folder) {
        if (folder == null || folder.isEmpty() || folder.length() > 64
                || folder.contains("/") || folder.contains("\\") || folder.contains("..")
                || folder.equals(".")) {
            return "unknown";
        }
        return folder;
    }

    // ============================================================
    // 初始化 / 旧版本迁移
    // ============================================================

    /**
     * 确保三个文件夹都存在：{@code parachute/}、{@code parachute/local/}、{@code parachute/server/}。
     *
     * <p>{@code local} 是玩家自己的伞，{@code server} 下面再按服务器/存档 UUID 分文件夹。
     * 两个都是空文件夹也保留，方便玩家直接把伞文件夹丢进去。</p>
     */
    public static void ensureFolderLayout() {
        createDir(rootPath());
        createDir(localPath());
        createDir(rootPath().resolve(SERVER_FOLDER_NAME));
    }

    /**
     * 两端都会调：确保<b>服务端伞库</b> {@code parachute/} 存在。
     *
     * <p>只建文件夹结构，<b>不</b>往里面铺任何内置伞 —— 默认伞（蘑菇）由客户端的
     * {@link #ensureClientFolders()} 直接放进 {@code parachute/local/}。
     * 服务端伞库里有什么，完全由服主决定；{@code /parachute distribute} 也只散它里面有的。</p>
     */
    public static void ensureServerLibrary() {
        ensureFolderLayout();
    }

    /**
     * <b>客户端</b>调用：确保玩家自己的伞文件夹 {@code parachute/local} 存在，
     * 并把默认伞（蘑菇）直接放进去。
     *
     * <p>第一次创建时会先把现有的 {@code parachute/<伞名>} 各复制一份进来（旧版本升级迁移）。
     * 用复制而不是移动：{@code parachute/<伞名>} 在客户端上还是「自己开世界」时的伞库，
     * 主机开 LAN 时 {@code /parachute list|distribute} 读的就是它，删掉就没了。</p>
     */
    public static void ensureClientFolders() {
        Path root = rootPath();
        Path local = localPath();
        boolean firstLoad = !Files.isDirectory(local);   // 必须在建文件夹之前判断

        ensureFolderLayout();                            // parachute/、local/、server/ 都要有

        if (firstLoad && Files.isDirectory(root)) {
            for (String id : listParachuteIds(root)) {
                Path dst = local.resolve(id);
                if (Files.isDirectory(dst)) continue;
                try {
                    copyTree(root.resolve(id), dst);
                    ParachuteMod.LOGGER.info("Copied parachute '{}' into {}", id, dst);
                } catch (IOException e) {
                    ParachuteMod.LOGGER.warn("Failed to copy parachute '{}' into local: {}", id, e.toString());
                }
            }
        }
        // 默认伞（蘑菇）直接放 local：缺了就补回来。其余内置伞只留在 jar 里，不导出。
        ensureBuiltins(local, List.of(DEFAULT_PARACHUTE));
    }

    /** 把给定的内置伞导出到文件夹（已存在则跳过） */
    private static void ensureBuiltins(Path root, List<String> ids) {
        createDir(root);
        for (String id : ids) {
            exportIfMissing(root, id);
        }
    }

    /** 递归复制整棵文件夹（迁移用） */
    private static void copyTree(Path from, Path to) throws IOException {
        try (var walk = Files.walk(from)) {
            for (Path path : walk.toList()) {
                Path dst = to.resolve(from.relativize(path));
                if (Files.isDirectory(path)) {
                    Files.createDirectories(dst);
                } else {
                    Files.copy(path, dst, StandardCopyOption.REPLACE_EXISTING);
                }
            }
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
            ParachuteMod.LOGGER.info("Exported built-in parachute '{}' to {}", id, dir);
        } catch (Exception e) {
            ParachuteMod.LOGGER.warn("Failed to export built-in parachute '{}': {}", id, e.toString());
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
     * 扫描 {@code parachute/}（服务端伞库）下所有含 {@code .bbmodel} 的子文件夹，返回伞名列表（已排序）。
     *
     * <p>服务端用它做伞库列表（{@code /parachute list}、download、distribute）；客户端渲染/上传
     * 用的是带伞源扫描的 {@code client.assets.ParachuteAssets}，不再走这里。</p>
     */
    public static List<String> listParachuteIds() {
        return listParachuteIds(rootFolder());
    }

    /** 扫描指定伞源根目录下的伞名列表（含 {@code .bbmodel} 的直接子文件夹，已排序） */
    public static List<String> listParachuteIds(Path root) {
        List<String> ids = new ArrayList<>();
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
            ParachuteMod.LOGGER.warn("Built-in bbmodel '{}' missing from classpath", bbResource);
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
            ParachuteMod.LOGGER.warn("Failed to export textures for built-in '{}': {}", id, e.toString());
        }
    }

    /** 把 bbmodel 的相对贴图路径解析为类路径资源（assets/ 开头） */
    private static String resolveRelativeAsset(String relativePath, String fileName) {
        if (relativePath.isEmpty()) {
            return "assets/" + ParachuteMod.MOD_ID + "/textures/entity/" + fileName;
        }
        // bbmodel 逻辑目录：assets/<mod>/models/entity
        Path base = Path.of("assets", ParachuteMod.MOD_ID, "models", "entity");
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
