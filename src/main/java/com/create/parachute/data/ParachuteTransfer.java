package com.create.parachute.data;

import com.create.parachute.ParachuteMod;
import com.create.parachute.network.ParachuteFileChunkPayload;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.neoforge.network.PacketDistributor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 伞文件传输层：服务端 ↔ 客户端之间搬 {@code parachute/<伞名>/<文件>}，以及本端文件夹读写。
 *
 * <h2>为什么需要分块</h2>
 * <p>Minecraft 的自定义网络包有大小上限（1 MiB），而内置伞里 {@code bigparachute2.bbmodel} 就有 1.4 MB，
 * 所以文件被切成 {@link #CHUNK_SIZE} 的片，由 {@link ParachuteFileChunkPayload} 逐个搬运，
 * 接收端按 {@code offset} 顺序拼回内存，收到最后一片再一次性落盘。</p>
 *
 * <h2>方向语义</h2>
 * <p>收发用的是同一个包类型（{@code playBidirectional}）：<b>谁收到包，就写进谁自己的游戏目录</b>。
 * 于是两个方向天然对称——</p>
 * <ul>
 *   <li>上传：OP 客户端从 {@code parachute/local} 读文件发给服务端 → 服务端写进
 *       {@code parachute/<伞名>/}（服务端伞库，布局不变）</li>
 *   <li>下载/分发：服务端从伞库发给客户端 → 客户端按当前伞源落地：
 *       单机/自己开的世界写 {@code parachute/local/<伞名>/}，
 *       连别人的服务器写 {@code parachute/server/<服务器存档UUID>/<伞名>/}（热加载自动生效）</li>
 * </ul>
 * <p>「当前伞源」见 {@link ParachuteScope}。</p>
 *
 * <h2>安全</h2>
 * <p>两端都会校验文件夹名/文件名（不含路径分隔符与 {@code ..}），并在拼接后再次确认目标路径仍在
 * {@code parachute/} 之内，防止恶意包写到目录外（zip slip / 目录穿越）；服务端方向上还要求发送者是 OP。</p>
 */
public final class ParachuteTransfer {

    /** 单个数据包的负载大小：远小于 1 MiB 上限，1.4 MB 的 bbmodel 约 22 个包 */
    public static final int CHUNK_SIZE = 64 * 1024;
    /** 单文件大小上限，防止被封包塞满磁盘 */
    public static final int MAX_FILE_BYTES = 64 * 1024 * 1024;

    private static final int MAX_FOLDER_NAME_LENGTH = 64;
    private static final int MAX_FILE_NAME_LENGTH = 128;
    /** 同时进行的接收会话上限，防御性上限（正常时同一时刻只有 1~2 个文件在传） */
    private static final int MAX_SESSIONS = 64;

    /** 正在接收的文件：key = 伞源 + '\0' + 伞名 + '\0' + 文件名 */
    private static final Map<String, Incoming> INCOMING = new HashMap<>();

    /**
     * 一个接收会话。
     *
     * @param root 目标伞源根目录，<b>在收到第一个分片时就定下来</b>——玩家中途换服务器/切存档时，
     *             已经开传的这笔仍然写回它开始时那个文件夹，不会被写到另一个服务器名下
     */
    private record Incoming(int totalBytes, ByteArrayOutputStream buffer, Path root) {
    }

    private ParachuteTransfer() {
    }

    // ============================================================
    // 名称校验 / 路径解析（防目录穿越）
    // ============================================================

    /** 伞名（文件夹名）是否合法 */
    public static boolean isValidFolderName(String name) {
        return isSafeName(name, MAX_FOLDER_NAME_LENGTH);
    }

    /** 文件名是否合法 */
    public static boolean isValidFileName(String name) {
        return isSafeName(name, MAX_FILE_NAME_LENGTH);
    }

    private static boolean isSafeName(String name, int maxLength) {
        if (name == null || name.isEmpty() || name.length() > maxLength) return false;
        if (name.equals(".") || name.equals("..")) return false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '*' || c == '?' || c == '"'
                    || c == '<' || c == '>' || c == '|' || c < 0x20) {
                return false;
            }
        }
        return true;
    }

    /**
     * 解析接收目标路径：{@code root/[folder/]file}，并确认结果仍在 {@code root} 之内。
     *
     * @param folder 伞名；空字符串/null 表示直接放在 {@code parachute/} 根下（打包下载用）
     * @throws IllegalArgumentException 名称非法或路径越界
     */
    public static Path resolveTarget(Path root, String folder, String file) {
        Path base = root.normalize();
        if (folder != null && !folder.isEmpty()) {
            if (!isValidFolderName(folder)) {
                throw new IllegalArgumentException("illegal folder name: " + folder);
            }
            base = base.resolve(folder);
        }
        if (!isValidFileName(file)) {
            throw new IllegalArgumentException("illegal file name: " + file);
        }
        Path target = base.resolve(file).normalize();
        if (!target.startsWith(root.normalize()) || target.equals(root.normalize())) {
            throw new IllegalArgumentException("path escapes parachute root: " + folder + "/" + file);
        }
        return target;
    }

    // ============================================================
    // 本端文件夹读取
    // ============================================================

    /**
     * 本端<b>自己</b>的伞文件夹 {@code parachute/local}——{@code /parachute upload} 的读取来源
     * （玩家的伞在自己客户端上，服务端读不到，所以由客户端读这里再传上去）。
     */
    public static Path localRoot() {
        return ParachuteManager.localPath();
    }

    /**
     * <b>服务端伞库</b> {@code parachute/}——{@code /parachute list|download|distribute|delete}
     * 读写的就是它，布局和以前完全一样（伞名文件夹直接放在 {@code parachute/} 下）。
     */
    public static Path libraryRoot() {
        return ParachuteManager.rootPath();
    }

    /** 本端 {@code parachute/local} 里的伞名（上传用） */
    public static List<String> listLocalFolders() {
        return ParachuteManager.listParachuteIds(localRoot());
    }

    /** 服务端伞库里的伞名（下载/分发用；实际发送走 {@link ParachuteDownloads}） */
    public static List<String> listLibraryFolders() {
        return ParachuteManager.listParachuteIds(libraryRoot());
    }

    /** 某个伞文件夹里的常规文件（不递归，按文件名排序） */
    public static List<Path> listFolderFiles(Path folder) {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(folder)) {
            for (Path p : ds) {
                if (Files.isRegularFile(p)) {
                    files.add(p);
                }
            }
        } catch (IOException ignored) {
        }
        files.sort(Comparator.comparing(p -> p.getFileName().toString()));
        return files;
    }

    /** 读一个文件的全部字节；失败返回 null（分批下载队列也用它，所以是 public） */
    public static byte[] readFileOrNull(Path file) {
        try {
            return Files.readAllBytes(file);
        } catch (IOException e) {
            ParachuteMod.LOGGER.warn("[transfer] failed to read {}: {}", file, e.toString());
            return null;
        }
    }

    // ============================================================
    // 发送
    // ============================================================

    /**
     * 把一个文件按 {@link #CHUNK_SIZE} 切片<b>一次性</b>投递出去（上传用；服务端下发走
     * {@link ParachuteDownloads} 的分批队列，用 {@link #sendChunk}）。
     *
     * @param sink 决定发往哪端：客户端用 {@code PacketDistributor::sendToServer}，
     *             服务端用 {@code p -> PacketDistributor.sendToPlayer(player, p)}
     * @return 实际发出的分片数
     */
    public static int sendFile(String folder, String file, byte[] data, Consumer<ParachuteFileChunkPayload> sink) {
        String safeFolder = folder == null ? "" : folder;
        int total = data.length;
        if (total == 0) {
            sink.accept(new ParachuteFileChunkPayload(safeFolder, file, 0, 0, new byte[0], true));
            return 1;
        }
        int chunks = 0;
        for (int offset = 0; offset < total; offset += CHUNK_SIZE) {
            int length = Math.min(CHUNK_SIZE, total - offset);
            sendChunk(folder, file, data, offset, length, offset + length >= total, sink);
            chunks++;
        }
        return chunks;
    }

    /**
     * 发一个文件里指定的一段（分批下载的队列用：每个 tick 只发预算允许的那么多字节）。
     *
     * @param offset 本段在文件里的起始偏移（接收端按它拼接，必须严格连续）
     * @param length 本段字节数（可以小于 {@link #CHUNK_SIZE}）
     * @param last   是否是这个文件的最后一段
     */
    public static void sendChunk(String folder, String file, byte[] data, int offset, int length, boolean last,
                                 Consumer<ParachuteFileChunkPayload> sink) {
        String safeFolder = folder == null ? "" : folder;
        byte[] slice = Arrays.copyOfRange(data, offset, offset + length);
        sink.accept(new ParachuteFileChunkPayload(safeFolder, file, data.length, offset, slice, last));
    }

    /** 把本端 {@code parachute/local} 里的某个伞文件夹发给服务端（OP 上传，服务端写进伞库）。
     *  返回文件数，本地找不到该伞返回 -1 */
    public static int uploadFolderToServer(String folderName) {
        Path dir = localRoot().resolve(folderName);
        if (!Files.isDirectory(dir)) return -1;
        int count = 0;
        for (Path file : listFolderFiles(dir)) {
            byte[] data = readFileOrNull(file);
            if (data == null) continue;
            if (data.length > MAX_FILE_BYTES) {
                ParachuteMod.LOGGER.warn("[transfer] skip oversized file {} ({} bytes)", file, data.length);
                continue;
            }
            sendFile(folderName, file.getFileName().toString(), data, PacketDistributor::sendToServer);
            count++;
        }
        return count;
    }

    /** 把本端 {@code parachute/local} 里全部伞发给服务端（OP 上传全部）。返回文件总数 */
    public static int uploadAllToServer() {
        int total = 0;
        for (String id : listLocalFolders()) {
            int n = uploadFolderToServer(id);
            if (n > 0) total += n;
        }
        return total;
    }

    // ============================================================
    // 删除（仅服务端指令调用）
    // ============================================================

    /**
     * 递归删除<b>服务端伞库</b>里的某个伞文件夹（{@code parachute/<伞名>/} 整棵树）。
     *
     * <p>与接收路径用同一套校验：名字必须合法，且归一化后仍在伞库之内，
     * 绝不会删到目录外（也不会删掉 {@code parachute/} 根目录本身）。</p>
     *
     * @return 删除的文件数；伞名非法、目录不存在或删除失败返回 -1
     */
    public static int deleteParachute(String folderName) {
        if (!isValidFolderName(folderName)) return -1;
        Path root = libraryRoot().normalize();
        Path dir = root.resolve(folderName).normalize();
        if (dir.equals(root) || !dir.startsWith(root) || !Files.isDirectory(dir)) return -1;
        int files = 0;
        try (var walk = Files.walk(dir)) {
            // 先子后父，才能把目录删干净
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isRegularFile(path)) files++;
                Files.deleteIfExists(path);
            }
        } catch (IOException e) {
            ParachuteMod.LOGGER.warn("[transfer] failed to delete '{}': {}", folderName, e.toString());
            return -1;
        }
        ParachuteMod.LOGGER.info("[transfer] deleted parachute '{}' ({} file(s))", folderName, files);
        return files;
    }

    // ============================================================
    // 接收
    // ============================================================

    /**
     * 收到一个分片：按 {@code offset} 顺序拼接，最后一片落盘。
     *
     * <p>落点由流向决定：</p>
     * <ul>
     *   <li>{@code SERVERBOUND}（有人在上传）→ 服务端伞库 {@code parachute/<伞名>/}，布局不变</li>
     *   <li>{@code CLIENTBOUND}（下载/分发）→ 当前伞源（{@link ParachuteScope}）：
     *       单机/自己开的世界是 {@code parachute/local/<伞名>/}，
     *       连别人的服务器是 {@code parachute/server/<服务器存档UUID>/<伞名>/}</li>
     * </ul>
     *
     * @param flow 包的流向
     */
    public static void receiveChunk(ParachuteFileChunkPayload payload, PacketFlow flow) {
        String folder = payload.folder() == null ? "" : payload.folder();
        String file = payload.file();

        if ((!folder.isEmpty() && !isValidFolderName(folder)) || !isValidFileName(file)) {
            ParachuteMod.LOGGER.warn("[transfer] rejected illegal path '{}/{}'", folder, file);
            return;
        }
        if (payload.totalBytes() < 0 || payload.totalBytes() > MAX_FILE_BYTES) {
            ParachuteMod.LOGGER.warn("[transfer] rejected oversized transfer '{}/{}' ({} bytes)",
                    folder, file, payload.totalBytes());
            return;
        }
        if (INCOMING.size() > MAX_SESSIONS) {
            INCOMING.clear();
            ParachuteMod.LOGGER.warn("[transfer] too many concurrent transfers, sessions reset");
        }

        // 上传永远写服务端伞库；下载/分发写「当前伞源」，伞源名进 key，
        // 这样从不同服务器下来的同名文件是两个互不干扰的会话
        final Path root;
        final String scopeKey;
        if (flow == PacketFlow.SERVERBOUND) {
            root = libraryRoot();
            scopeKey = "library";
        } else {
            ParachuteScope.Source scope = ParachuteScope.clientScope();
            root = scope.root();
            scopeKey = scope.label();
        }

        String key = scopeKey + '\0' + folder + '\0' + file;
        Incoming session = INCOMING.get(key);
        if (session == null) {
            if (payload.offset() != 0) {
                // 中间片丢失/乱序：本次会话无法完成，直接丢弃，等发送方下次重传
                ParachuteMod.LOGGER.warn("[transfer] chunk without session '{}/{}' offset={}", folder, file, payload.offset());
                return;
            }
            session = new Incoming(payload.totalBytes(),
                    new ByteArrayOutputStream(Math.min(payload.totalBytes(), CHUNK_SIZE)), root);
            INCOMING.put(key, session);
        } else if (payload.offset() != session.buffer().size()) {
            INCOMING.remove(key);
            ParachuteMod.LOGGER.warn("[transfer] out-of-order chunk for '{}/{}' (offset {} != {})",
                    folder, file, payload.offset(), session.buffer().size());
            return;
        }

        session.buffer().writeBytes(payload.data());
        if (session.buffer().size() > MAX_FILE_BYTES) {
            INCOMING.remove(key);
            ParachuteMod.LOGGER.warn("[transfer] aborted oversized transfer '{}/{}'", folder, file);
            return;
        }
        if (!payload.last() && session.buffer().size() < session.totalBytes()) {
            return;
        }

        INCOMING.remove(key);
        byte[] data = session.buffer().toByteArray();
        try {
            Path target = resolveTarget(session.root(), folder, file);
            Files.createDirectories(target.getParent());
            Files.write(target, data);
            String where = scopeKey + "/" + (folder.isEmpty() ? "" : folder + "/") + file;
            ParachuteMod.LOGGER.info("[transfer] {} {} ({} bytes)",
                    flow == PacketFlow.SERVERBOUND ? "stored" : "written", where, data.length);
        } catch (Exception e) {
            ParachuteMod.LOGGER.warn("[transfer] failed to write '{}/{}': {}", folder, file, e.toString());
        }
    }

    /**
     * 客户端收到「请上传」请求：读本地文件夹并发给服务端，然后给玩家本地反馈。
     *
     * @param payload {@code folder} 为空表示上传本地全部伞
     */
    public static void handleUploadRequest(String folder, Player feedbackTo) {
        int files;
        String key;
        if (folder == null || folder.isEmpty()) {
            files = uploadAllToServer();
            key = "command.create_parachute.upload.done_all";
        } else {
            if (!isValidFolderName(folder)) return;
            files = uploadFolderToServer(folder);
            key = "command.create_parachute.upload.done";
        }
        if (feedbackTo != null) {
            if (files <= 0) {
                feedbackTo.displayClientMessage(
                        Component.translatable("command.create_parachute.upload.nothing",
                                folder == null || folder.isEmpty() ? "-" : folder), false);
            } else {
                feedbackTo.displayClientMessage(
                        Component.translatable(key, folder == null || folder.isEmpty() ? "-" : folder, files), false);
            }
        }
        ParachuteMod.LOGGER.info("[transfer] uploaded {} file(s) for '{}'", files, folder == null ? "<all>" : folder);
    }
}
