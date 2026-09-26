package com.create.parachute;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * 降落伞模组的全局可调参数。
 * <p>在游戏主菜单 → Mods → Parachute → Config 中可以直接修改，
 * 无需重启游戏即可生效（除动画帧数等初始化时读取的参数外）。</p>
 */
public final class ParachuteConfig {

    // ========== 视觉效果 / 动画 ==========

    /** 伞面方向平滑插值系数（lerp），越大越快跟上速度方向 */
    public static final ModConfigSpec.DoubleValue CLIENT_DIR_SMOOTHING;

    /** 飘动相位每 tick 前进量，越大飘动越快 */
    public static final ModConfigSpec.DoubleValue WOBBLE_FREQUENCY;

    /** 伞旋转基准阻尼（slerp 系数），每帧向目标方向靠近的比例 */
    public static final ModConfigSpec.DoubleValue SLERP_BASE;

    /** 动态阻尼角度阈值（度）。当前与目标夹角小于此值时 slerp 从 1.0 线性递减到 SLERP_BASE */
    public static final ModConfigSpec.DoubleValue SLERP_ANGLE_THRESHOLD;

    /** Yaw 阻尼角度阈值（度）。伞面与体空间 Y 轴夹角小于此值时，用 cos 增强 yaw 阻尼 */
    public static final ModConfigSpec.DoubleValue YAW_DAMP_THRESHOLD;

    /** Yaw 阻尼最大倍率。夹角 0° 时 slerp *= 1 - 此值 */
    public static final ModConfigSpec.DoubleValue YAW_DAMP_FORCE;

    /** 飘动 X 轴旋转幅度（度） */
    public static final ModConfigSpec.DoubleValue WOBBLE_X_AMP;

    /** 飘动 Z 轴旋转幅度（度）——大伞 & 普通伞 */
    public static final ModConfigSpec.DoubleValue WOBBLE_Z_AMP_BIG;

    /** 飘动 Z 轴旋转幅度（度）——小伞类型（伞名不含 big 的伞） */
    public static final ModConfigSpec.DoubleValue WOBBLE_Z_AMP_SMALL;

    /** 飘动 X 轴频率倍率 */
    public static final ModConfigSpec.DoubleValue WOBBLE_X_FREQ_MULT;

    /** 飘动 Z 轴频率倍率 */
    public static final ModConfigSpec.DoubleValue WOBBLE_Z_FREQ_MULT;

    /** 伞的渲染器最大可见距离（格），上限 2^16 = 65536 */
    public static final ModConfigSpec.IntValue VIEW_DISTANCE;

    /**
     * 伞模型（不透明层）的几何 / 剔除策略，<b>开不开光影都用同一套</b>（这样两种情况外观一致）。
     *
     * <p>背景：光影包的延迟着色只认<b>顶点法线</b>（例如 Photon 的 gbuffer 平面法线 =
     * {@code tbn[2] = mat3(gbufferModelViewInverse) * normalize(gl_NormalMatrix * gl_Normal)}），
     * 而一个顶点只能有一个法线 —— 所以"同一块面从两侧看光照都对"就必须有两个法线版本，
     * 也就是顶点数 ×2。只发一份又要两侧都对，只有让着色器按 {@code gl_FrontFacing} 翻法线
     * （本模组自己的着色器会翻；光影包要它自己支持）。</p>
     */
    public enum ShadersGeometry {
        /** 每面两份 + 剔除背面：薄片两面都看得见，代价是顶点/显存 ×2 */
        DOUBLE,
        /** 一份 + 剔除背面（默认）：光照一定正确（画出来的片元法线必然朝向相机），但只有一层的薄片零件从背面看会消失 */
        SINGLE_CULL,
        /** 一份 + 不剔除背面：靠着色器按 gl_FrontFacing 翻法线。无光影时本模组自己的着色器就能翻（完全无损）； */
        SINGLE_NO_CULL
    }

    /** 不透明层的几何 / 剔除策略（见 {@link ShadersGeometry}） */
    public static final ModConfigSpec.EnumValue<ShadersGeometry> SHADERS_GEOMETRY;

    /**
     * OBJ 顶点法线的自动平滑角度（度）：{@code 0} = 用文件里的 {@code vn}；{@code >0} = 按几何重算
     * （同一个顶点上夹角在阈值内的面做角度加权平均，超过阈值的保持硬边）。
     */
    public static final ModConfigSpec.DoubleValue OBJ_SMOOTH_ANGLE;

    /**
     * 坐垫伞包的座位点不再用单独的配置项：座位点 = 方块中心 + 方块上设的枢轴偏移
     * （伞包界面「枢轴点」模式，见 {@code ParachuteSeatEntity#seatPoint()}）。
     * <p>旧版本这里有个 {@code visual.seatHeight}（默认 -0.4375）；配置项已移除，
     * 旧配置文件里残留的那一行会被忽略，不影响启动。</p>
     */
    public static final String SEAT_HEIGHT_REMOVED_NOTE = "seatHeight 已被枢轴偏移取代";

    // ========== 物理 / 同步 ==========

    /** 阻力基础系数，所有拖拽力乘以此值 */
    public static final ModConfigSpec.DoubleValue DRAG_BASE;

    /** 每轴最大线性冲量，防止物理爆炸 */
    public static final ModConfigSpec.DoubleValue MAX_IMPULSE_PER_AXIS;

    /** 每轴最大角冲量（扭矩上限） */
    public static final ModConfigSpec.DoubleValue MAX_TORQUE_PER_AXIS;

    /** 开伞后多少 tick 内跳过低速自动切伞检测 */
    public static final ModConfigSpec.IntValue LOW_SPEED_GRACE_TICKS;

    /** 向客户端同步方向的最小间隔（tick） */
    public static final ModConfigSpec.IntValue SYNC_HEARTBEAT_TICKS;

    /** 方向变化平方和小于此值时跳过同步 */
    public static final ModConfigSpec.DoubleValue SYNC_DIR_EPSILON_SQ;

    /** 平移阻力系数的上限，限制 GUI 中 dragCoefficient 的最大值 */
    public static final ModConfigSpec.DoubleValue MAX_DRAG_COEFFICIENT;

    /** 旋转阻尼系数的上限，限制 GUI 中 rotationalDragCoefficient 的最大值 */
    public static final ModConfigSpec.DoubleValue MAX_ROTATIONAL_DAMP_COEFFICIENT;

    // ========== 服务器伞库下发 ==========

    /** 是否允许普通玩家自己用 /parachute list|download 拉服务器伞库（关掉就只有 OP 能用） */
    public static final ModConfigSpec.BooleanValue ALLOW_PLAYER_DOWNLOAD;

    /**
     * 每个玩家每 tick 最多下发多少字节（0 = 不限）。
     * 下载是按队列分批发的：用时间换带宽，避免一次把整个伞库灌进连接。
     */
    public static final ModConfigSpec.IntValue DOWNLOAD_BYTES_PER_TICK;

    /** 单个玩家最多能排多少个待发文件，防止刷指令把队列撑爆 */
    public static final ModConfigSpec.IntValue DOWNLOAD_MAX_PENDING_FILES;


    public static final ModConfigSpec SPEC;

    static {
        ModConfigSpec.Builder b = new ModConfigSpec.Builder();

        b.push("visual");

        CLIENT_DIR_SMOOTHING = b
                .comment("伞面方向平滑插值系数（lerp），0 表示完全不跟，1 表示瞬间跟上。推荐 0.5~0.95")
                .defineInRange("clientDirSmoothing", 0.85D, 0.01D, 1.0D);

        WOBBLE_FREQUENCY = b
                .comment("伞面飘动速度，每 tick 的相位增量。0 则不动，推荐 0.2~1.0")
                .defineInRange("wobbleFrequency", 0.4D, 0.0D, 5.0D);

        SLERP_BASE = b
                .comment("伞旋转的基准阻尼系数。数值越小伞越粘滞、转向越慢")
                .defineInRange("slerpBase", 0.4D, 0.01D, 1.0D);

        SLERP_ANGLE_THRESHOLD = b
                .comment("动态阻尼的角度阈值（度）。当前朝向与目标夹角小于此值时，"
                       + "slerp 系数从 1.0 线性过渡到 slerpBase，大于此值时使用 slerpBase")
                .defineInRange("slerpAngleThreshold", 7.5D, 0.1D, 90.0D);

        YAW_DAMP_THRESHOLD = b
                .comment("Yaw 阻尼角度阈值（度）。伞面(vel)与体空间 Y 轴夹角小于此值时，"
                       + "用 cos 增强 yaw 阻尼：cos(angle/threshold * π/2)")
                .defineInRange("yawDampThreshold", 20.0D, 1.0D, 90.0D);

        YAW_DAMP_FORCE = b
                .comment("Yaw 阻尼最大倍率。夹角 0° 时 slerp *= 1 - 此值（0°=强阻尼，阈值=无阻尼）")
                .defineInRange("yawDampForce", 0.9D, 0.0D, 1.0D);

        WOBBLE_X_AMP = b
                .comment("飘动绕 X 轴的最大摆动角度（度）")
                .defineInRange("wobbleXAmp", 0.6D, 0.0D, 20.0D);

        WOBBLE_Z_AMP_BIG = b
                .comment("飘动绕 Z 轴的最大摆动角度（度）——大伞 & 普通伞")
                .defineInRange("wobbleZAmpBig", 0.5D, 0.0D, 20.0D);

        WOBBLE_Z_AMP_SMALL = b
                .comment("飘动绕 Z 轴的最大摆动角度（度）——小伞类型（伞名不含 big 的伞）")
                .defineInRange("wobbleZAmpSmall", 0.5D, 0.0D, 20.0D);

        WOBBLE_X_FREQ_MULT = b
                .comment("飘动 X 轴频率倍率，乘以 wobblePhase")
                .defineInRange("wobbleXFreqMult", 1.1D, 0.0D, 5.0D);

        WOBBLE_Z_FREQ_MULT = b
                .comment("飘动 Z 轴频率倍率，乘以 wobblePhase")
                .defineInRange("wobbleZFreqMult", 1.3D, 0.0D, 5.0D);

        VIEW_DISTANCE = b
                .comment("伞（方块实体）渲染器的最大可见距离，单位格，上限 65536（2^16），设为 0 则用默认值 512。",
                         "这是客户端自己的渲染设置；原版上限 1024 只是历史值，数学上 65536 完全安全",
                         "（原版判断是 Vec3.closerThan(pos, (double) viewDistance)，double 运算不会溢出）。",
                         "注意实际能看多远还受客户端区块加载距离限制：伞是方块实体，区块没加载就没有实体可渲染，",
                         "所以原版设置下有效上限约等于客户端渲染距离（最高 32 区块 = 512 格），",
                         "装了提高渲染距离/区块缓存的模组才能真正用到大数值。")
                .defineInRange("viewDistance", 512, 0, 65536);

        SHADERS_GEOMETRY = b
                .comment("伞模型不透明层的几何/剔除策略，**开不开光影都用这一套**（两种情况外观一致）。",
                         "顶点数是一倍还是两倍，帧数差别很大：实测 98 万面的 AH-64D 在 Photon 下",
                         "DOUBLE 约 14ms/帧、SINGLE_* 约 7ms/帧。",
                         "SINGLE_CULL（默认）：只发一份 + 剔除背面。光照一定正确（画出来的片元法线必然朝向相机），",
                         "  代价是只有一层几何的薄片零件从背面看会消失（出现小孔）。",
                         "DOUBLE：每个三角形发两份（绕序相反、法线各朝一侧）+ 剔除背面。",
                         "  薄片两面都看得见，代价是顶点/显存 ×2。",
                         "SINGLE_NO_CULL：只发一份 + 不剔除背面，靠着色器按 gl_FrontFacing 翻背面法线。",
                         "  无光影时本模组自己的着色器就会翻 -> 完全无损（两面都看得见、光照也对）；",
                         "  开光影时要用光影包的着色器，需要光影包自己翻，否则薄片背面光照会反：",
                         "  Photon 把 shaders/program/gbuffers_all_solid.fsh 里的 `#define flat_normal tbn[2]`",
                         "  改成 `#define flat_normal (gl_FrontFacing ? tbn[2] : -tbn[2])` 即可。",
                         "半透明层固定「两份 + 剔除」（不剔除的话正反面片元会各混合一次，颜色明显加深）；",
                         "逐帧发射路径（低于 2 万面的小模型）读的是加载时的配置值，改配置后重新加载该伞生效。")
                .defineEnum("shadersGeometry", ShadersGeometry.SINGLE_CULL);

        OBJ_SMOOTH_ANGLE = b
                .comment("OBJ 模型顶点法线的自动平滑角度（度）。0 = 关闭，直接用 .obj 里的 vn。",
                         "DCS 转出来的 OBJ 大多是**逐面法线**（实测 AH-64D：97.9 万个面 / 259.6 万个互不相同的",
                         "角法线，同一个位置上平均 2.8 个不同法线），照着画曲面就是「一格一格」的硬边。",
                         "设成 60（默认）时按几何重算：同一个顶点（按位置 1e-4 合并，跨顶点拆分也能平滑）上，",
                         "与本面夹角在阈值内的面做角度加权平均，超过阈值的保持硬边。",
                         "30 = 只平滑很平缓的曲面；90 = 几乎全平滑（圆角也会被糊掉）；180 = 完全平均。",
                         "因为法线改成从几何算，它必定与绕序一致 —— 这也是 shadersGeometry=SINGLE_CULL",
                         "（一份几何 + 剔除背面）光照必然正确的前提。改这个值后重新加载该伞生效。")
                .defineInRange("objSmoothAngle", 60.0D, 0.0D, 180.0D);

        b.pop();

        b.push("physics");

        DRAG_BASE = b
                .comment("阻力基础系数。所有拖拽力 = -速度 * dragCoefficient * dragBase * delta")
                .defineInRange("dragBase", 1.0D, 0.01D, 100.0D);

        MAX_IMPULSE_PER_AXIS = b
                .comment("单轴最大线性冲量，防止极端速度下物理体爆炸")
                .defineInRange("maxImpulsePerAxis", 250.0D, 1.0D, 10000.0D);

        MAX_TORQUE_PER_AXIS = b
                .comment("单轴最大角冲量（扭矩上限）")
                .defineInRange("maxTorquePerAxis", 10.0D, 0.1D, 1000.0D);

        LOW_SPEED_GRACE_TICKS = b
                .comment("开伞后宽限 tick 数，在此期间即使速度低于阈值也不会自动切伞")
                .defineInRange("lowSpeedGraceTicks", 10, 0, 200);

        SYNC_HEARTBEAT_TICKS = b
                .comment("服务端向客户端同步伞面方向的最小间隔（tick），越小方向越精准但带宽越高")
                .defineInRange("syncHeartbeatTicks", 5, 1, 100);

        SYNC_DIR_EPSILON_SQ = b
                .comment("方向变化平方和阈值。变化小于此值时跳过同步，减少网络包")
                .defineInRange("syncDirEpsilonSq", 1.0E-4D, 1.0E-8D, 1.0D);

        MAX_DRAG_COEFFICIENT = b
                .comment("平移阻力系数上限。GUI 中 dragCoefficient 的最大值")
                .defineInRange("maxDragCoefficient", 100_000.0D, 0.1D, 1_000_000.0D);

        MAX_ROTATIONAL_DAMP_COEFFICIENT = b
                .comment("旋转阻尼系数上限。GUI 中 rotationalDragCoefficient 的最大值")
                .defineInRange("maxRotationalDampCoefficient", 100_000.0D, 0.1D, 1_000_000.0D);

        b.pop();

        b.push("download");

        ALLOW_PLAYER_DOWNLOAD = b
                .comment("是否允许普通玩家自己用 /parachute list 和 /parachute download 拉服务器伞库。"
                       + "false 时这两个子指令只有 OP（权限等级 2）能用；"
                       + "上传、分发、删除始终需要 OP")
                .define("allowPlayerDownload", true);

        DOWNLOAD_BYTES_PER_TICK = b
                .comment("每个玩家每 tick 最多下发多少字节（0 = 不限）。下载按队列分批发送，"
                       + "用时间换带宽：64 KiB/tick 约等于 1.25 MiB/s，1.4 MB 的伞约 22 tick（1 秒多）传完")
                .defineInRange("bytesPerTick", 65_536, 0, 1_048_576);

        DOWNLOAD_MAX_PENDING_FILES = b
                .comment("单个玩家最多排队多少个待发文件，超了就拒绝并要求改用单把下载，防止刷指令撑爆队列")
                .defineInRange("maxPendingFiles", 512, 1, 8192);

        b.pop();

        SPEC = b.build();
    }

    private ParachuteConfig() {}
}
