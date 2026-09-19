package com.create.parachute.data;

import com.create.parachute.ParachuteConfig;
import com.create.parachute.ParachuteMod;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 服务端伞库的<b>分批下发队列</b>：{@code /parachute download} 和 {@code /parachute distribute}
 * 都不再一次性把伞库灌出去，而是排进每个玩家自己的队列，由 {@link ServerTickEvent.Post}
 * 按 {@link ParachuteConfig#DOWNLOAD_BYTES_PER_TICK} 的字节预算逐 tick 发送——用时间换带宽。
 *
 * <h2>为什么是一个玩家一条队列</h2>
 * <ul>
 *   <li>每个玩家的连接是独立的，分开排队互不影响</li>
 *   <li>同一玩家<b>一次只发一个文件</b>（发完才取下一个），所以接收端永远只有一条进行中的会话，
 *       不会出现同名文件的分片交错</li>
 *   <li>队列里存的是「伞名 + 文件名」，不是字节：内存占用与伞库大小无关，只有当前这个文件读进内存</li>
 * </ul>
 *
 * <p>排队上限见 {@link ParachuteConfig#DOWNLOAD_MAX_PENDING_FILES}；玩家掉线/停服时队列直接丢弃。</p>
 */
@EventBusSubscriber(modid = ParachuteMod.MOD_ID)
public final class ParachuteDownloads {

    /** 一个待发文件 */
    private record Job(String folder, String file) {
    }

    /** 配置里填了非 0 但小得离谱的值时的下限，免得一个文件要传几小时 */
    private static final int MIN_BYTES_PER_TICK = 1024;

    /** 一个玩家的下载进度 */
    private static final class Session {
        ServerPlayer player;
        final Deque<Job> jobs = new ArrayDeque<>();
        /** 当前正在发的文件 */
        Job current;
        byte[] data;
        int offset;
        /** 统计（用于完成时的提示） */
        int chutes;
        int files;

        Session(ServerPlayer player) {
            this.player = player;
        }

        boolean idle() {
            return this.current == null && this.jobs.isEmpty();
        }
    }

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    private ParachuteDownloads() {
    }

    /**
     * 把若干伞文件夹排进这个玩家的下载队列。
     *
     * @return 排入的文件数；超过单玩家排队上限返回 -1；这些文件夹里一个可发文件都没有返回 0
     */
    public static int enqueue(ServerPlayer player, Collection<String> folders) {
        Path root = ParachuteTransfer.libraryRoot();
        List<Job> pending = new ArrayList<>();
        int chutes = 0;
        for (String folder : folders) {
            if (folder == null || folder.isEmpty()) continue;
            List<Path> files = ParachuteTransfer.listFolderFiles(root.resolve(folder));
            if (files.isEmpty()) continue;
            chutes++;
            for (Path file : files) {
                pending.add(new Job(folder, file.getFileName().toString()));
            }
        }
        if (pending.isEmpty()) return 0;

        Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session(player));
        // 同一 UUID 换了实例（重连）时用新的 player 引用
        if (session.player != player) {
            session.player = player;
        }
        if (session.jobs.size() + countPendingCurrent(session) + pending.size()
                > ParachuteConfig.DOWNLOAD_MAX_PENDING_FILES.get()) {
            return -1;
        }
        session.jobs.addAll(pending);
        session.chutes += chutes;
        return pending.size();
    }

    /** 这个玩家是否还有没发完的东西 */
    public static boolean isBusy(ServerPlayer player) {
        Session session = SESSIONS.get(player.getUUID());
        return session != null && !session.idle();
    }

    /** 正在发的那个文件也算排队中（它已经不在 jobs 里了） */
    private static int countPendingCurrent(Session session) {
        return session.current == null ? 0 : 1;
    }

    // ============================================================
    // 发送
    // ============================================================

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (SESSIONS.isEmpty()) return;
        int budget = ParachuteConfig.DOWNLOAD_BYTES_PER_TICK.get();
        Iterator<Map.Entry<UUID, Session>> it = SESSIONS.entrySet().iterator();
        while (it.hasNext()) {
            Session session = it.next().getValue();
            if (step(session, budget)) {
                it.remove();
                notifyFinished(session);
            }
        }
    }

    @SubscribeEvent
    public static void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        SESSIONS.remove(event.getEntity().getUUID());
    }

    @SubscribeEvent
    public static void onServerStopped(ServerStoppedEvent event) {
        SESSIONS.clear();
    }

    /**
     * 发一个 tick 的量。
     *
     * @return 该玩家的队列是否已经空了（可以移出 map）
     */
    private static boolean step(Session session, int budgetPerTick) {
        ServerPlayer player = session.player;
        if (player.hasDisconnected()) return true;

        int budget = budgetPerTick <= 0 ? Integer.MAX_VALUE : Math.max(budgetPerTick, MIN_BYTES_PER_TICK);
        Path root = ParachuteTransfer.libraryRoot();

        while (true) {
            if (session.current == null) {
                Job job = session.jobs.poll();
                if (job == null) return true;
                byte[] data = ParachuteTransfer.readFileOrNull(root.resolve(job.folder()).resolve(job.file()));
                if (data == null || data.length > ParachuteTransfer.MAX_FILE_BYTES) {
                    ParachuteMod.LOGGER.warn("[download] skip unreadable file {}/{}", job.folder(), job.file());
                    continue;
                }
                session.current = job;
                session.data = data;
                session.offset = 0;
            }

            int total = session.data.length;
            int length = Math.min(Math.min(ParachuteTransfer.CHUNK_SIZE, total - session.offset), budget);
            if (length <= 0 && total > 0) break;    // 这个 tick 的预算用完了，下个 tick 接着发
            boolean last = session.offset + length >= total;
            ParachuteTransfer.sendChunk(session.current.folder(), session.current.file(), session.data,
                    session.offset, length, last, payload -> PacketDistributor.sendToPlayer(player, payload));
            session.offset += length;
            budget -= length;
            if (last) {
                session.files++;
                session.current = null;
                session.data = null;
                if (budget <= 0) break;
            }
        }
        return false;
    }

    private static void notifyFinished(Session session) {
        if (session.files <= 0) return;
        ServerPlayer player = session.player;
        if (player.hasDisconnected()) return;
        player.displayClientMessage(Component.translatable("command.create_parachute.download.finished",
                session.chutes, session.files), false);
        ParachuteMod.LOGGER.info("[download] sent {} chute(s)/{} file(s) to {}",
                session.chutes, session.files, player.getName().getString());
    }
}
