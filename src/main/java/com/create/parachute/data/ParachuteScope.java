package com.create.parachute.data;

import org.jetbrains.annotations.Nullable;

import java.nio.file.Path;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 「当前伞源」：客户端此刻该读、该写哪个伞文件夹。
 *
 * <h2>规则</h2>
 * <ul>
 *   <li><b>单机 / 自己开的世界（含 LAN 主机）</b>：用玩家自己的 {@code parachute/local}
 *       （{@link Source#isLocal()} 为 true）</li>
 *   <li><b>连别人的服务器（含别人的 LAN 世界）</b>：用
 *       {@code parachute/server/<服务器存档 UUID>}——UUID 由服务端在登录时同步过来
 *       （{@link ServerIdentity}）；拿不到时退回 {@code parachute/server/<服务器地址>}</li>
 * </ul>
 *
 * <h2>为什么这里用「存档 UUID」而不是地址</h2>
 * <p>地址会变：LAN 每次开放端口都变、域名可以换成 IP、服务器可以换端口或换机器。存档 UUID
 * 只跟存档走，所以客户端文件夹不会因为地址变化而分裂；反过来，同一台服务器换存档 = 换 UUID =
 * 客户端换文件夹，正是「每个存档一套伞」的语义。</p>
 *
 * <h2>为什么要在这里放一个 supplier</h2>
 * <p>判断「现在连的是谁」要用 {@code net.minecraft.client.Minecraft}，而本类所在的
 * {@code data} 包两端共用、必须保持 dist-clean（专用服务端不能加载任何客户端类）。
 * 所以真正的实现由客户端（{@code client.ClientParachuteScope}）在
 * {@code FMLClientSetupEvent} 里注入；没注入时（服务端、或客户端启动早期）默认是本地文件夹。</p>
 */
public final class ParachuteScope {

    /**
     * 一个伞源。
     *
     * @param folder 服务器子文件夹名（{@code null} = 本地 {@code parachute/local}）；
     *               要么是存档 UUID，要么是兜底的服务器地址
     * @param root   该伞源的根目录（伞名文件夹就建在它下面）
     */
    public record Source(@Nullable String folder, Path root) {

        /** 是否是玩家自己的文件夹（而不是某个服务器的） */
        public boolean isLocal() {
            return this.folder == null;
        }

        /** 日志/缓存用的短名：{@code local} 或 {@code server/<文件夹名>} */
        public String label() {
            return isLocal() ? ParachuteManager.LOCAL_FOLDER_NAME
                    : ParachuteManager.SERVER_FOLDER_NAME + "/" + this.folder;
        }
    }

    /** 默认伞源：本地。客户端会在启动时替换成真正会判断服务器的实现。 */
    private static volatile Supplier<Source> clientScope = ParachuteScope::local;

    /**
     * 服务端同步过来的<b>存档 UUID</b>（只在客户端有值）。
     * {@code null} = 不知道（服务器没装模组、还没收到、或对方发来的不是合法 UUID）。
     */
    @Nullable
    private static volatile String serverIdentity;

    private ParachuteScope() {
    }

    /** 客户端当前的伞源（每 tick / 每个分片都重新取，切换服务器立刻生效） */
    public static Source clientScope() {
        return clientScope.get();
    }

    /** 由 {@code client.ClientParachuteScope} 注入；传 null 恢复默认（本地） */
    public static void setClientScope(@Nullable Supplier<Source> supplier) {
        clientScope = supplier == null ? ParachuteScope::local : supplier;
    }

    /** 本地伞源 */
    public static Source local() {
        return new Source(null, ParachuteManager.localPath());
    }

    // ============================================================
    // 服务器身份（存档 UUID）
    // ============================================================

    /** 客户端收到 {@code ServerIdentityPayload} 时写入；非法内容会被丢掉（存 null） */
    public static void setServerIdentity(@Nullable String raw) {
        serverIdentity = canonicalUuid(raw);
    }

    /** 当前服务器的存档 UUID；未知时 null */
    @Nullable
    public static String serverIdentity() {
        return serverIdentity;
    }

    /**
     * 只接受合法 UUID，并统一成 {@link UUID#toString()} 的规范形式。
     *
     * <p>这个字符串最终会变成 {@code parachute/server/} 下的文件夹名，而它来自服务器，
     * 所以必须过这一道：解析失败一律返回 null，绝不让 {@code ../} 之类的东西混进路径。</p>
     */
    @Nullable
    public static String canonicalUuid(@Nullable String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty() || trimmed.length() > 36) return null;
        try {
            return UUID.fromString(trimmed).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * 把服务器地址变成能当文件夹名字的字符串（<b>兜底方案</b>，服务端没装模组时用）：
     * {@code localhost:25565 → localhost_25565}。
     * 只保留 {@code [a-z0-9._-]}，其余字符换成 {@code _}，限长 64，去掉结尾的点（Windows 会吃掉）。
     */
    public static String sanitizeServerFolder(@Nullable String address) {
        if (address == null) return "unknown";
        String src = address.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(Math.min(src.length(), 64));
        for (int i = 0; i < src.length() && sb.length() < 64; i++) {
            char c = src.charAt(i);
            boolean safe = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
                    || c == '.' || c == '-' || c == '_';
            sb.append(safe ? c : '_');
        }
        int end = sb.length();
        while (end > 0 && sb.charAt(end - 1) == '.') {
            end--;
        }
        String out = sb.substring(0, end);
        if (out.isEmpty() || out.equals(".") || out.equals("..")) return "unknown";
        return out;
    }
}
