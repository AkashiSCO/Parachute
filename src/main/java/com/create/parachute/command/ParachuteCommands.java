package com.create.parachute.command;

import com.create.parachute.ParachuteConfig;
import com.create.parachute.ParachuteMod;
import com.create.parachute.data.ParachuteDownloads;
import com.create.parachute.data.ParachuteManager;
import com.create.parachute.data.ParachuteTransfer;
import com.create.parachute.network.ParachuteUploadRequestPayload;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.function.Predicate;

/**
 * {@code /parachute} 指令族——服务端伞库的运维入口。
 *
 * <pre>
 * /parachute list [page]        列出服务端 parachute/ 里的伞名，每页 10 个          【所有人】
 * /parachute download [name]    把自己的伞库那份（同名/全部）拉到本地              【所有人】
 * /parachute upload [name]      请求把执行者本地 parachute/local 的伞上传到服务端    【OP】
 * /parachute distribute [目标]   把服务端全部伞分发给服务器内所有玩家 / 指定玩家       【OP】
 * /parachute delete [name]      删除服务端伞（同名/全部），先弹聊天框确认             【OP】
 * </pre>
 *
 * <p>{@code list}/{@code download} 对普通玩家开放（可用
 * {@link com.create.parachute.ParachuteConfig#ALLOW_PLAYER_DOWNLOAD} 关掉），
 * 玩家不需要 OP 就能自己拿到服务器的伞；写服务端的三个子指令始终要求 OP（权限等级 2）。
 * 下载是分批的：排进 {@link ParachuteDownloads} 的每玩家队列，按 tick 预算慢慢发。</p>
 *
 * <p>{@code RegisterCommandsEvent} 是游戏事件总线上的事件，且 Fire 于专用服务端，
 * 所以本类不能引用任何客户端类型（自动注册时扫描器按参数类型分流到游戏总线）。</p>
 */
@EventBusSubscriber(modid = ParachuteMod.MOD_ID)
public final class ParachuteCommands {

    /** 每页最多列出的伞名数量 */
    private static final int PER_PAGE = 10;
    /** 聊天框里 [确认] 按钮点击后执行的隐藏子指令名 */
    private static final String CONFIRM_LITERAL = "__confirm";
    /** 聊天框里 [取消] 按钮点击后执行的隐藏子指令名 */
    private static final String CANCEL_LITERAL = "__cancel";
    /** OP 要求：权限等级 2。写服务端的子指令（upload / distribute / delete）用它 */
    private static final Predicate<CommandSourceStack> OP = source -> source.hasPermission(2);

    private ParachuteCommands() {
    }

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("parachute")
                // ---- 所有玩家都能用：看服务端有哪些伞、自己拉下来 ----
                // （是否真的允许下载由 ParachuteConfig.ALLOW_PLAYER_DOWNLOAD 决定，在 handler 里判断，
                //   这样被关掉时能给出明确提示，而不是一句 "Unknown command"）
                .then(Commands.literal("list")
                        .executes(context -> list(context.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(context -> list(context.getSource(),
                                        IntegerArgumentType.getInteger(context, "page")))))
                .then(Commands.literal("download")
                        .executes(context -> downloadAll(context.getSource()))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> downloadOne(context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                // ---- 写服务端的操作：始终 OP ----
                .then(Commands.literal("upload")
                        .requires(OP)
                        .executes(context -> requestUpload(context.getSource(), null))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> requestUpload(context.getSource(),
                                        StringArgumentType.getString(context, "name")))))
                .then(Commands.literal("distribute")
                        .requires(OP)
                        .executes(context -> distribute(context.getSource(), null))
                        .then(Commands.argument("player", EntityArgument.players())
                                .executes(context -> distribute(context.getSource(),
                                        EntityArgument.getPlayers(context, "player")))))
                .then(Commands.literal("delete")
                        .requires(OP)
                        .executes(context -> requestDelete(context.getSource(), null))
                        // 这两个分支只用给聊天框里的 [确认] / [取消] 按钮点击执行，
                        // 用双下划线前缀避免和伞名撞车（伞名可能叫 confirm，但几乎不可能叫 __confirm）
                        .then(Commands.literal(CONFIRM_LITERAL)
                                .executes(context -> deleteAll(context.getSource()))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(context -> deleteOne(context.getSource(),
                                                StringArgumentType.getString(context, "name")))))
                        .then(Commands.literal(CANCEL_LITERAL)
                                .executes(context -> cancelDelete(context.getSource())))
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(context -> requestDelete(context.getSource(),
                                        StringArgumentType.getString(context, "name"))))));
    }

    /**
     * 普通玩家能不能自己拉伞库。关掉时只有 OP 能用，并且给非 OP 一句明确的话
     * （而不是 Brigadier 的 "Unknown command"）。
     */
    private static boolean allowDownload(CommandSourceStack source) {
        if (ParachuteConfig.ALLOW_PLAYER_DOWNLOAD.get() || source.hasPermission(2)) return true;
        source.sendFailure(Component.translatable("command.create_parachute.download.disabled"));
        return false;
    }

    // ============================================================
    // list —— 分页列出服务端伞库
    // ============================================================

    /**
     * 输出格式（每页至多 {@value #PER_PAGE} 个，序号跨页连续）：
     * <pre>
     * [1] name
     * ...
     * [10] name
     * [page] 1/3
     * </pre>
     * 第 2 页接续为 {@code [11]}…{@code [20]}。
     */
    private static int list(CommandSourceStack source, int page) {
        if (!allowDownload(source)) return 0;
        List<String> ids = ParachuteManager.listParachuteIds();
        if (ids.isEmpty()) {
            source.sendFailure(Component.translatable("command.create_parachute.list.empty",
                    ParachuteManager.rootFolder().toString()));
            return 0;
        }
        int pages = (ids.size() + PER_PAGE - 1) / PER_PAGE;
        if (page > pages) {
            source.sendFailure(Component.translatable("command.create_parachute.list.out_of_range", pages));
            return 0;
        }
        int from = (page - 1) * PER_PAGE;
        int to = Math.min(ids.size(), from + PER_PAGE);
        for (int i = from; i < to; i++) {
            final int index = i + 1;
            final String name = ids.get(i);
            source.sendSuccess(() -> Component.literal("[" + index + "] " + name), false);
        }
        final int pageNumber = page;
        final int totalPages = pages;
        source.sendSuccess(() -> Component.literal("[page] " + pageNumber + "/" + totalPages), false);
        return to - from;
    }

    // ============================================================
    // upload —— 请求执行者客户端上传本地伞
    // ============================================================

    private static int requestUpload(CommandSourceStack source, @Nullable String name) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        if (name != null && !ParachuteTransfer.isValidFolderName(name)) {
            source.sendFailure(Component.translatable("command.create_parachute.invalid_name", name));
            return 0;
        }
        String folder = name == null ? "" : name;
        PacketDistributor.sendToPlayer(player, new ParachuteUploadRequestPayload(folder));
        if (name == null) {
            source.sendSuccess(() -> Component.translatable("command.create_parachute.upload.requested_all"), false);
        } else {
            source.sendSuccess(() -> Component.translatable("command.create_parachute.upload.requested", name), false);
        }
        return 1;
    }

    // ============================================================
    // download —— 服务端伞库 → 执行者客户端（分批，排队发送）
    // ============================================================

    private static int downloadOne(CommandSourceStack source, String name) throws CommandSyntaxException {
        if (!allowDownload(source)) return 0;
        ServerPlayer player = source.getPlayerOrException();
        if (ParachuteDownloads.isBusy(player)) {
            source.sendFailure(Component.translatable("command.create_parachute.download.busy"));
            return 0;
        }
        if (!ParachuteTransfer.isValidFolderName(name)) {
            source.sendFailure(Component.translatable("command.create_parachute.invalid_name", name));
            return 0;
        }
        if (!ParachuteTransfer.listLibraryFolders().contains(name)) {
            source.sendFailure(Component.translatable("command.create_parachute.download.missing", name));
            return 0;
        }
        int files = ParachuteDownloads.enqueue(player, List.of(name));
        if (files < 0) {
            source.sendFailure(Component.translatable("command.create_parachute.download.too_many",
                    ParachuteConfig.DOWNLOAD_MAX_PENDING_FILES.get()));
            return 0;
        }
        if (files == 0) {
            source.sendFailure(Component.translatable("command.create_parachute.download.missing", name));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("command.create_parachute.download.queued_one",
                name, files), false);
        return files;
    }

    /** 全部伞：逐个伞文件夹直接排队下发（不再打包 zip，落到玩家自己的 parachute/server/<存档UUID>/） */
    private static int downloadAll(CommandSourceStack source) throws CommandSyntaxException {
        if (!allowDownload(source)) return 0;
        ServerPlayer player = source.getPlayerOrException();
        if (ParachuteDownloads.isBusy(player)) {
            source.sendFailure(Component.translatable("command.create_parachute.download.busy"));
            return 0;
        }
        List<String> ids = ParachuteTransfer.listLibraryFolders();
        if (ids.isEmpty()) {
            source.sendFailure(Component.translatable("command.create_parachute.list.empty",
                    ParachuteManager.rootFolder().toString()));
            return 0;
        }
        int files = ParachuteDownloads.enqueue(player, ids);
        if (files < 0) {
            source.sendFailure(Component.translatable("command.create_parachute.download.too_many",
                    ParachuteConfig.DOWNLOAD_MAX_PENDING_FILES.get()));
            return 0;
        }
        final int chutes = ids.size();
        final int fileCount = files;
        source.sendSuccess(() -> Component.translatable("command.create_parachute.download.queued_all",
                chutes, fileCount), false);
        return chutes;
    }

    // ============================================================
    // distribute —— 服务端伞库 → 其他玩家（同样是分批队列）
    // ============================================================

    /**
     * 分发服务端伞库（排进每个目标玩家自己的下载队列，由 tick 分批发送）。
     *
     * @param targets {@code null} = 服务器内所有在线玩家；否则只发给指定的这些玩家
     *                （参数用 {@link EntityArgument#players()}，所以 {@code @a}、{@code @p}、
     *                {@code @r}、玩家名、以及多人选择器都能用）
     */
    private static int distribute(CommandSourceStack source, @Nullable Collection<ServerPlayer> targets) {
        List<String> ids = ParachuteTransfer.listLibraryFolders();
        if (ids.isEmpty()) {
            source.sendFailure(Component.translatable("command.create_parachute.list.empty",
                    ParachuteManager.rootFolder().toString()));
            return 0;
        }
        // 去重（选择器可能重复命中），并保持稳定顺序
        List<ServerPlayer> resolved = targets == null
                ? new ArrayList<>(source.getServer().getPlayerList().getPlayers())
                : new ArrayList<>(new LinkedHashSet<>(targets));
        if (resolved.isEmpty()) {
            source.sendFailure(Component.translatable("command.create_parachute.distribute.no_players"));
            return 0;
        }
        int files = 0;
        int skipped = 0;
        for (ServerPlayer player : resolved) {
            int queued = ParachuteDownloads.enqueue(player, ids);
            if (queued < 0) {
                skipped++;
            } else {
                files += queued;
            }
        }
        final int playerCount = resolved.size();
        final int chuteCount = ids.size();
        final int fileCount = files;
        if (playerCount == 1) {
            final String name = resolved.get(0).getName().getString();
            source.sendSuccess(() -> Component.translatable("command.create_parachute.distribute.one",
                    name, chuteCount, fileCount), false);
        } else {
            source.sendSuccess(() -> Component.translatable("command.create_parachute.distribute.done",
                    playerCount, chuteCount, fileCount), false);
        }
        if (skipped > 0) {
            final int skippedCount = skipped;
            source.sendSuccess(() -> Component.translatable("command.create_parachute.distribute.skipped",
                    skippedCount), false);
        }
        return playerCount;
    }

    // ============================================================
    // delete —— 带聊天框确认的删除
    // ============================================================

    /**
     * 发出确认提示：不直接删，而是在聊天栏给出一条带可点击 [确认] / [取消] 的消息。
     * <p>[确认] 按钮执行的是隐藏子指令 {@code /parachute delete __confirm [name]}，
     * 也就是说删除动作仍然走一遍完整的指令权限校验（还是 OP）。</p>
     */
    private static int requestDelete(CommandSourceStack source, @Nullable String name) {
        List<String> ids = ParachuteManager.listParachuteIds();
        if (name == null) {
            if (ids.isEmpty()) {
                source.sendFailure(Component.translatable("command.create_parachute.list.empty",
                        ParachuteManager.rootFolder().toString()));
                return 0;
            }
            final int count = ids.size();
            source.sendSuccess(() -> confirmPrompt(
                    Component.translatable("command.create_parachute.delete.confirm_all", count),
                    "/parachute delete " + CONFIRM_LITERAL), false);
            return count;
        }
        if (!ParachuteTransfer.isValidFolderName(name)) {
            source.sendFailure(Component.translatable("command.create_parachute.invalid_name", name));
            return 0;
        }
        if (!ids.contains(name)) {
            source.sendFailure(Component.translatable("command.create_parachute.delete.missing", name));
            return 0;
        }
        source.sendSuccess(() -> confirmPrompt(
                Component.translatable("command.create_parachute.delete.confirm_one", name),
                "/parachute delete " + CONFIRM_LITERAL + " " + name), false);
        return 1;
    }

    /** 拼出「确认删除？ [确认] [取消]」，两个按钮都是可点击文本 */
    private static Component confirmPrompt(Component question, String confirmCommand) {
        Component yes = Component.translatable("command.create_parachute.delete.yes")
                .withStyle(style -> style.withColor(ChatFormatting.GREEN).withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, confirmCommand))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("command.create_parachute.delete.yes.hover"))));
        Component no = Component.translatable("command.create_parachute.delete.no")
                .withStyle(style -> style.withColor(ChatFormatting.RED).withBold(true)
                        .withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                                "/parachute delete " + CANCEL_LITERAL))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                                Component.translatable("command.create_parachute.delete.no.hover"))));
        return Component.empty()
                .append(question)
                .append(Component.literal(" "))
                .append(yes)
                .append(Component.literal(" "))
                .append(no);
    }

    /** [确认] 且未指定伞名：删除服务端伞库里的全部伞 */
    private static int deleteAll(CommandSourceStack source) {
        List<String> ids = ParachuteManager.listParachuteIds();
        if (ids.isEmpty()) {
            source.sendFailure(Component.translatable("command.create_parachute.list.empty",
                    ParachuteManager.rootFolder().toString()));
            return 0;
        }
        int deleted = 0;
        int files = 0;
        for (String id : ids) {
            int removed = ParachuteTransfer.deleteParachute(id);
            if (removed >= 0) {
                deleted++;
                files += removed;
            }
        }
        if (deleted == 0) {
            source.sendFailure(Component.translatable("command.create_parachute.delete.failed"));
            return 0;
        }
        final int chuteCount = deleted;
        final int fileCount = files;
        source.sendSuccess(() -> Component.translatable("command.create_parachute.delete.done_all",
                chuteCount, fileCount), true);
        return chuteCount;
    }

    /** [确认] 且带伞名：删除那一把伞 */
    private static int deleteOne(CommandSourceStack source, String name) {
        if (!ParachuteTransfer.isValidFolderName(name)) {
            source.sendFailure(Component.translatable("command.create_parachute.invalid_name", name));
            return 0;
        }
        int files = ParachuteTransfer.deleteParachute(name);
        if (files < 0) {
            source.sendFailure(Component.translatable("command.create_parachute.delete.missing", name));
            return 0;
        }
        final int fileCount = files;
        source.sendSuccess(() -> Component.translatable("command.create_parachute.delete.done_one",
                name, fileCount), true);
        return 1;
    }

    private static int cancelDelete(CommandSourceStack source) {
        source.sendSuccess(() -> Component.translatable("command.create_parachute.delete.cancelled"), false);
        return 0;
    }
}
