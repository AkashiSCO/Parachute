package com.create.parachute.parachute;

import com.create.parachute.ParachuteConfig;
import com.create.parachute.client.assets.ParachuteAssets;
import com.create.parachute.data.ParachuteManager;
import com.create.parachute.network.ClientboundParachuteVelocityPayload;
import com.create.parachute.registry.ModBlockEntities;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3d;

/**
 * 降落伞方块实体 — 驾驶舱式交互的物理拖拽设备。
 *
 * <h2>整体架构</h2>
 * <p>本类同时运行在服务端和客户端，通过精确的角色分工保证物理确定性和视觉平滑性：</p>
 *
 * <h3>服务端 — 物理权威</h3>
 * <ul>
 *   <li>{@link #serverTick(Level, BlockPos, BlockState, ParachuteBlockEntity)}：每 tick 调用，
 *       驱动动画推进和方向同步</li>
 *   <li>{@link #sable$physicsTick(ServerSubLevel, RigidBodyHandle, double)}：物理帧回调，
 *       <b>只</b>在服务端运行。计算世界坐标系拖拽力并施加到 Sable 物理体</li>
 *   <li>{@link #tickServer()}：每 N tick 检查方向是否变化，必要时向客户端发包</li>
 *   <li>{@link #checkRedstone()}：检测红石信号边沿，触发开伞/切伞</li>
 * </ul>
 *
 * <h3>客户端 — 渲染平滑</h3>
 * <ul>
 *   <li>{@link #clientTick(Level, BlockPos, BlockState, ParachuteBlockEntity)}：每 tick 调用，
 *       推进动画并更新渲染方向</li>
 *   <li>{@link #tickClientDirection()}：接收服务端发来的体空间速度方向，用 lerp 平滑 vel，
 *       再用动态 slerp 平滑 renderQuat</li>
 *   <li>{@link #getRenderQuat(float)}：GPU 帧间插值，避免视觉跳变</li>
 * </ul>
 *
 * <h2>坐标系说明</h2>
 * <p><b>世界坐标系</b>：服务端物理计算使用，拖拽力方向 = 世界速度反方向。</p>
 * <p><b>体坐标系 (Local Space)</b>：渲染用，四元数在物理体的局部姿态下取值。
 *    物理体旋转时体空间方向自动跟随，不需要额外追踪物理体姿态。</p>
 * <p><b>模型坐标系</b>：BlockBench 中模型默认朝向 +Y 为模型上方。
 *    renderQuat 将 (0,-1,0) 旋转到 vel 方向；水平朝向由 rotateTo 的 vel 水平分量自然决定
 *    （不再叠加固定 rotateY，避免不对称模型左右镜像）。</p>
 *
 * <h2>数据流</h2>
 * <ol>
 *   <li>物理帧：world velocity → 取反得到 dragDir（世界空间）</li>
 *   <li>物理帧：dragDir × body⁻¹ → localDragDir（体空间）→ 存为 localTargetVel</li>
 *   <li>同步包：localTargetVel → ClientboundParachuteVelocityPayload → 客户端</li>
 *   <li>客户端 tick：localTargetVel → lerp 平滑 → vel</li>
 *   <li>客户端 tick：vel → rotateTo quat → slerp 平滑 → renderQuat</li>
 *   <li>渲染帧：prevRenderQuat + renderQuat → slerp(partialTick) → GPU 管线</li>
 * </ol>
 *
 * @see ParachuteConfig 模组全局可调参数
 * @see ClientboundParachuteVelocityPayload 方向同步网络包
 * @see com.create.parachute.client.ParachuteRenderer 降落伞方块实体渲染器
 */
public class ParachuteBlockEntity extends BlockEntity implements BlockEntitySubLevelActor, net.minecraft.world.MenuProvider {

    // ============================================================
    // 数值边界常量
    // ============================================================

    /** 每 tick 阻力系数下限（0 表示无阻力） */
    private static final double MIN_K = 0.0D;
    /** 切伞速度阈值下限（m/s），设为 0 时永远不会因低速切伞 */
    private static final double MIN_DISCONNECT_SPEED = 0.0D;
    /** 切伞速度阈值上限（m/s） */
    private static final double MAX_DISCONNECT_SPEED = 5.0D;
    /** 开伞/收伞动画总帧数（同 Minecraft 1 tick = 1 帧，共 1 秒） */
    private static final int OPEN_ANIMATION_TICKS = 20;

    // ============================================================
    // 逐块可配置参数（通过 GUI 修改，非全局配置）
    // ============================================================

    /** 平移阻力系数（范围 [0, 100000]），越大速度衰减越快 */
    private volatile double dragCoefficient = 0.0D;
    /** 旋转阻尼系数（范围 [0, 100000]），越大旋转越快停止 */
    private volatile double rotationalDragCoefficient = 0.0D;
    /** 低速自动切伞的速度阈值（m/s），仅在 disconnectOnLowSpeed=true 时生效 */
    private volatile double disconnectSpeedThreshold = 0.0D;
    /** 是否启用低速自动切伞：当物理体速度持续低于阈值时自动收伞 */
    private volatile boolean disconnectOnLowSpeed = false;
    /** 是否启用红石脉冲切伞：已开伞状态下收到红石信号时收伞 */
    private volatile boolean disconnectOnRedstonePulse = true;

    // ============================================================
    // 部署状态
    // ============================================================

    /** 伞是否已展开（true = 开伞，false = 收伞/伞包状态） */
    private volatile boolean deployed;
    /** 自最近一次开伞以来经历的物理 tick 数（用于宽限期判断） */
    private volatile int physicsTickCount;
    /** 上一 tick 的红石信号状态，用于边沿检测 */
    private boolean wasPowered;
    /** 开伞/收伞动画进度 [0, OPEN_ANIMATION_TICKS]，每 tick ±1，服务端和客户端独立推进 */
    private int animationProgress;
    /** 上一 tick 的动画进度，用于帧间插值 */
    private int prevAnimationProgress;

    // ============================================================
    // 方向矢量 — 世界坐标系（服务端物理计算产出）
    // ============================================================

    /** 当前平滑后的世界空间速度反方向（单位向量），每 tick 向 targetVel lerp 靠近 */
    private volatile double velX, velY, velZ;
    /** 上一帧的世界方向，用于 GPU 帧间插值 */
    private double prevVelX, prevVelY = 1.0D, prevVelZ;
    /** 世界空间目标方向 — 服务端物理帧写入，客户端通过同步包接收 */
    private double targetVelX, targetVelY = 1.0D, targetVelZ;

    // ============================================================
    // 方向矢量 — 体坐标系（客户端渲染使用）
    // ============================================================

    /**
     * 体坐标系目标方向 — 服务端从世界速度方向经 body⁻¹ 变换得到。
     * <p>物理体旋转时体空间方向会跟着转，无需单独追踪物理体姿态。
     * 发送给客户端后作为平滑和渲染的目标值。</p>
     */
    private double localTargetVelX, localTargetVelY = 1.0D, localTargetVelZ;
    /** 最后一次成功同步到客户端的世界方向值 */
    private double lastSyncedVelX, lastSyncedVelY = 1.0D, lastSyncedVelZ;
    /** 客户端是否已收到至少一次方向同步包 */
    private boolean clientDirectionInitialized;
    /** 服务端是否至少完成一次方向同步 */
    private boolean hasLastSyncedDirection;
    /** 渲染四元数是否已初始化（首次收到方向时直接设置而非 slerp） */
    private boolean renderDirInitialized;

    // ============================================================
    // 渲染状态
    // ============================================================

    /** 当前渲染姿态四元数，每 tick 向目标方向 slerp 靠近 */
    private final Quaternionf renderQuat = new Quaternionf();
    /** 上一帧渲染姿态四元数，用于 GPU 帧间插值 */
    private final Quaternionf prevRenderQuat = new Quaternionf();
    /** 飘动动画相位（弧度），部署状态下每 tick 前进 WOBBLE_FREQUENCY */
    private float wobblePhase;
    /** 上一 tick 的飘动相位，用于帧间插值 */
    private float prevWobblePhase;
    /** 方向同步心跳计数器，每 tick +1，达到阈值后清零并触发同步 */
    private int syncTick;
    /**
     * 伞面染料颜色（ARGB 打包，高 8 位恒为 0xFF）。
     * <p>贴图为白色底 + 渲染时顶点色相乘动态染色（类似皮革盔甲），
     * 不再为每种染料准备一张贴图。未染色时等于 DyeColor.RED 的贴图扩散色。</p>
     */
    private int dyeColorARGB;

    /**
     * 是否已染色：false = 显示 .bbmodel 内嵌的原始贴图（斧头右键恢复）；
     * true = 白色底贴图 + 染料 ARGB 顶点色。
     */
    private boolean dyed;

    /**
     * 当前使用的伞名（对应游戏根目录 {@code parachute/} 下的子文件夹）。
     * 渲染是客户端的事：本机没有这把伞时渲染器自动回退蘑菇伞。
     */
    private String parachuteName = ParachuteManager.DEFAULT_PARACHUTE;

    // ============================================================
    // 变换设置（GUI 设置，渲染器使用）
    // ============================================================

    /** 是否锁定自摆动：true 时伞面三轴自摆动角度强制为 0 */
    private volatile boolean wobbleLocked;
    /** 伞面旋转（度，自摆动坐标系）：X/Y/Z */
    private volatile float rotX, rotY, rotZ;
    /** 枢轴点偏移：模型相对枢轴的位置（格） */
    private volatile float pivotX, pivotY, pivotZ;
    /** 整体偏移（格）：模型整体平移（含放置自带偏移） */
    private volatile float offX, offY, offZ;

    public ParachuteBlockEntity(BlockPos pos, BlockState blockState) {
        super(ModBlockEntities.PARACHUTE_BLOCK_ENTITY.get(), pos, blockState);
        this.dyeColorARGB = 0xFF000000 | net.minecraft.world.item.DyeColor.RED.getTextureDiffuseColor();
    }

    // ============================================================
    // Tick 入口
    // ============================================================

    /**
     * 服务端每 tick 回调，由 {@code BlockEntityTicker} 注册。
     * <p>依次推进动画状态和服务端方向同步逻辑。</p>
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, ParachuteBlockEntity blockEntity) {
        blockEntity.tickAnimation();
        blockEntity.tickServer();
    }

    /**
     * 客户端每 tick 回调，由 {@code BlockEntityTicker} 注册。
     * <p>依次推进动画状态和客户端方向平滑逻辑。</p>
     */
    public static void clientTick(Level level, BlockPos pos, BlockState state, ParachuteBlockEntity blockEntity) {
        blockEntity.tickAnimation();
        blockEntity.tickClientDirection();
    }

    /**
     * 服务端每 tick 执行的核心逻辑。
     * <p>先同步 blockstate 的 deployed 属性与内存状态一致，
     * 然后按心跳间隔检查方向是否变化，必要时向客户端发送同步包。</p>
     * <p>同步间隔由 {@link ParachuteConfig#SYNC_HEARTBEAT_TICKS} 控制（默认 5 tick），
     * 方向变化小于 {@link ParachuteConfig#SYNC_DIR_EPSILON_SQ} 时跳过同步以节省带宽。</p>
     */
    private void tickServer() {
        syncDeployedState();
        if (!this.deployed) return;
        this.syncTick++;
        if (this.syncTick < ParachuteConfig.SYNC_HEARTBEAT_TICKS.get() && !this.shouldSyncDirection()) return;
        this.syncTick = 0;
        this.syncToClient();
    }

    /**
     * 构建方向同步包并发送给所有跟踪当前 chunk 的客户端。
     * <p>包内容是体空间方向（localTargetVel），客户端收到后直接用于渲染朝向计算，
     * 无需再做世界→体变换。</p>
     */
    private void syncToClient() {
        if (this.level == null || this.level.isClientSide) return;
        ClientboundParachuteVelocityPayload payload =
                new ClientboundParachuteVelocityPayload(this.worldPosition,
                        this.localTargetVelX, this.localTargetVelY, this.localTargetVelZ);
        if (this.level instanceof ServerLevel sl) {
            PacketDistributor.sendToPlayersTrackingChunk(sl, sl.getChunkAt(this.worldPosition).getPos(), payload);
            this.lastSyncedVelX = this.velX;
            this.lastSyncedVelY = this.velY;
            this.lastSyncedVelZ = this.velZ;
            this.hasLastSyncedDirection = true;
        }
    }

    /**
     * 开伞/收伞动画推进。
     * <p>每 tick 向目标值靠近 1 帧：deployed 时向 OPEN_ANIMATION_TICKS 增长，否则向 0 递减。
     * 到达目标值后停止，无需继续更新。</p>
     * <p>服务端变更时调用 {@link #setChanged()} 触发保存。</p>
     */
    private void tickAnimation() {
        this.prevAnimationProgress = this.animationProgress;
        int target = this.deployed ? OPEN_ANIMATION_TICKS : 0;
        if (this.animationProgress == target) return;
        this.animationProgress += this.animationProgress < target ? 1 : -1;
        if (this.level != null && !this.level.isClientSide) {
            this.setChanged();
        }
    }

    /**
     * 客户端方向平滑更新的核心方法。
     *
     * <h3>处理流程</h3>
     * <ol>
     *   <li>备份当前值用于 GPU 帧间插值</li>
     *   <li>确定目标方向：已部署时取服务端发来的体空间方向（localTargetVel），
     *       未部署时取方块放置面方向</li>
     *   <li>用 configurable lerp 系数平滑 vel 向目标方向过渡</li>
     *   <li>从 vel 构建目标四元数：rotateTo(model-up, vel)，水平朝向随 vel 水平分量自然决定</li>
     *   <li>用动态 slerp 平滑 renderQuat 向目标四元数旋转：
     *       夹角 < 阈值时 变化率 1.0→base，小角度更快追齐避免抖动；
     *       夹角 ≥ 阈值时用固定 base 速率</li>
     *   <li>推进飘动相位（仅在部署状态下）</li>
     * </ol>
     *
     * <h3>为什么用体空间方向？</h3>
     * <p>服务端在物理帧中计算世界速度方向，然后用 body⁻¹ 转为体空间发过来。
     * 因为 BER 渲染发生在物理体的局部姿态堆栈中，renderQuat 在体空间取值，
     * 可以正确响应物理体旋转（例如伞包被活塞推动旋转时，伞面会自动转对方向）。</p>
     *
     * <h3>动态 slerp</h3>
     * <p>当伞面朝向与目标方向夹角小于阈值角时，slerp 系数从 1.0 线性递减到 base。
     * 这保证了小角度偏差能迅速纠正（避免"慢慢蹭到目标位置"的视觉拖沓），
     * 大角度偏差保持平滑过渡。</p>
     * <p>阈值角和 base 值通过 {@link ParachuteConfig#SLERP_ANGLE_THRESHOLD}
     * 和 {@link ParachuteConfig#SLERP_BASE} 可调。</p>
     */
    private void tickClientDirection() {
        if (this.level == null || !this.level.isClientSide) return;

        // 备份当前值用于帧间插值（getRenderVelX/Y/Z 在渲染器中调用）
        this.prevVelX = this.velX;
        this.prevVelY = this.velY;
        this.prevVelZ = this.velZ;

        // 确定目标方向：
        //   已部署 → 服务端发来的体空间方向（已含物理体姿态转换）
        //   未部署 → 方块放置面的法线方向
        double desiredX;
        double desiredY;
        double desiredZ;
        if (this.deployed) {
            desiredX = this.localTargetVelX;
            desiredY = this.localTargetVelY;
            desiredZ = this.localTargetVelZ;
        } else {
            Direction facing = getFacing();
            desiredX = facing.getStepX();
            desiredY = facing.getStepY();
            desiredZ = facing.getStepZ();
        }

        // 平滑过渡到目标方向（lerp 系数可调，默认 0.85）
        double smoothing = ParachuteConfig.CLIENT_DIR_SMOOTHING.get();
        this.velX = Mth.lerp(smoothing, this.velX, desiredX);
        this.velY = Mth.lerp(smoothing, this.velY, desiredY);
        this.velZ = Mth.lerp(smoothing, this.velZ, desiredZ);
        normalizeDirection();

        this.prevRenderQuat.set(this.renderQuat);

        // rotateTo(0,-1,0, vel): 模型 -Y 对齐阻力方向
        // rotateY(-90°): BlockBench 模型默认朝向补偿，仅 modded_entity 需要；
        // bedrock 模型（X 轴语义相反）不加，否则左右镜像。
        Quaternionf target = new Quaternionf().rotateTo(0, -1, 0,
                (float) this.velX, (float) this.velY, (float) this.velZ);
        if (!ParachuteAssets.isBedrock(getParachuteName())) {
            target.mul(new Quaternionf().rotateY((float) Math.toRadians(-90)));
        }

        if (!this.renderDirInitialized) {
            // 首次初始化：直接设置为目标值，跳过 slerp 过渡
            this.renderQuat.set(target);
            this.renderDirInitialized = true;
        } else {
            // 动态 slerp：用四元数点积计算当前与目标的夹角（弧度转度）
            float dot = Math.abs(this.renderQuat.x() * target.x() + this.renderQuat.y() * target.y()
                    + this.renderQuat.z() * target.z() + this.renderQuat.w() * target.w());
            if (dot > 1.0F) dot = 1.0F;
            float angleDeg = (float) Math.toDegrees(Math.acos(dot) * 2.0);

            // 小角度用快速追赶，大角度用基准速率
            float base = ParachuteConfig.SLERP_BASE.get().floatValue();
            float threshold = ParachuteConfig.SLERP_ANGLE_THRESHOLD.get().floatValue();
            float slerp;
            if (angleDeg < threshold) {
                // 夹角 0 → slerp=1.0（瞬间对齐）；夹角=threshold → slerp=base
                slerp = 1.0F - (angleDeg / threshold) * (1.0F - base);
            } else {
                slerp = base;
            }

            // Yaw 阻尼：cos 三角函数。伞面与体空间Y轴夹角越小，阻尼越强
            float velAbsY = Math.abs((float) this.velY);
            float canopyAngle = (float) Math.acos(Math.min(1f, velAbsY));
            float yawThreshRad = (float) Math.toRadians(ParachuteConfig.YAW_DAMP_THRESHOLD.get());
            if (canopyAngle < yawThreshRad) {
                float yawDamp = (float) Math.cos(canopyAngle / yawThreshRad * Math.PI / 2);
                slerp *= 1f - yawDamp * ParachuteConfig.YAW_DAMP_FORCE.get().floatValue();
            }

            this.renderQuat.slerp(target, slerp);
        }

        // 飘动相位：只在部署状态下累加，收伞时归零
        if (this.deployed) {
            this.prevWobblePhase = this.wobblePhase;
            this.wobblePhase += ParachuteConfig.WOBBLE_FREQUENCY.get().floatValue();
        } else {
            this.wobblePhase = 0;
            this.prevWobblePhase = 0;
        }
    }

    /**
     * 判断当前方向与上次同步方向的偏差是否大到需要重新发包。
     * <p>比较标准为欧氏距离平方，阈值由配置控制（默认 1e-4）。
     * 首次同步时无条件返回 true。</p>
     *
     * @return true 如果需要同步
     */
    private boolean shouldSyncDirection() {
        if (!this.hasLastSyncedDirection) return true;
        double dx = this.velX - this.lastSyncedVelX;
        double dy = this.velY - this.lastSyncedVelY;
        double dz = this.velZ - this.lastSyncedVelZ;
        return dx * dx + dy * dy + dz * dz > ParachuteConfig.SYNC_DIR_EPSILON_SQ.get();
    }

    // ============================================================
    // 红石交互
    // ============================================================

    /**
     * 收到红石脉冲时的逻辑。
     * <ul>
     *   <li>未部署 → 触发开伞</li>
     *   <li>已部署 + disconnectOnRedstonePulse=true → 触发收伞</li>
     *   <li>已部署 + disconnectOnRedstonePulse=false → 无操作</li>
     * </ul>
     */
    public void onRedstonePulse() {
        if (!this.deployed) {
            this.setDeployed(true);
            return;
        }
        if (this.disconnectOnRedstonePulse) {
            this.setDeployed(false);
        }
    }

    /**
     * 检测红石信号边沿变化（off→on 触发脉冲）。
     * <p>每 tick 由方块实体逻辑调用一次。</p>
     */
    public void checkRedstone() {
        if (this.level == null || this.level.isClientSide) return;
        boolean powered = this.level.hasNeighborSignal(this.worldPosition);
        if (powered && !this.wasPowered) {
            this.onRedstonePulse();
        }
        this.wasPowered = powered;
    }

    // ============================================================
    // 公开访问器
    // ============================================================

    /** @return 伞是否处于展开状态 */
    public boolean isDeployed() { return this.deployed; }
    /** @return 单块平移阻力系数 */
    public double getDragCoefficient() { return this.dragCoefficient; }
    /** @return 单块旋转阻尼系数 */
    public double getRotationalDragCoefficient() { return this.rotationalDragCoefficient; }
    /** @return 单块低速切伞速度阈值（m/s） */
    public double getDisconnectSpeedThreshold() { return this.disconnectSpeedThreshold; }
    /** @return 是否启用低速自动切伞 */
    public boolean isDisconnectOnLowSpeed() { return this.disconnectOnLowSpeed; }
    /** @return 是否启用红石脉冲切伞 */
    public boolean isDisconnectOnRedstonePulse() { return this.disconnectOnRedstonePulse; }

    public void setDragCoefficient(double k) { this.dragCoefficient = clamp(k, MIN_K, ParachuteConfig.MAX_DRAG_COEFFICIENT.get()); markDirty(); }
    public void setRotationalDragCoefficient(double k) { this.rotationalDragCoefficient = clamp(k, MIN_K, ParachuteConfig.MAX_ROTATIONAL_DAMP_COEFFICIENT.get()); markDirty(); }
    public void setDisconnectSpeedThreshold(double v) { this.disconnectSpeedThreshold = clamp(v, MIN_DISCONNECT_SPEED, MAX_DISCONNECT_SPEED); markDirty(); }

    /** 在 GUI 中微调阻力系数（每次 +delta 或 -delta） */
    public void adjustDragCoefficient(double delta) { setDragCoefficient(this.dragCoefficient + delta); }
    /** 在 GUI 中微调旋转阻尼系数 */
    public void adjustRotationalDragCoefficient(double delta) { setRotationalDragCoefficient(this.rotationalDragCoefficient + delta); }
    /** 在 GUI 中微调切伞速度阈值 */
    public void adjustDisconnectSpeedThreshold(double delta) { setDisconnectSpeedThreshold(this.disconnectSpeedThreshold + delta); }

    /** @return 当前伞面染料颜色（ARGB 打包，高 8 位为 0xFF），渲染器用于顶点染色 */
    public int getDyeColorARGB() { return this.dyeColorARGB; }

    /** @return 是否已染色（false = 显示原始贴图） */
    public boolean isDyed() { return this.dyed; }

    /** 根据 DyeItem 设置颜色并标记为已染色。直接取染料的标准贴图扩散色，由渲染器顶点染色实现 */
    public void setDyeColor(net.minecraft.world.item.DyeColor color) {
        this.dyed = true;
        this.dyeColorARGB = 0xFF000000 | color.getTextureDiffuseColor();
        markDirty();
    }

    /** 清除染色，恢复 .bbmodel 原始贴图（斧头右键调用） */
    public void clearDye() {
        this.dyed = false;
        this.dyeColorARGB = 0xFF000000 | net.minecraft.world.item.DyeColor.RED.getTextureDiffuseColor();
        markDirty();
    }

    /** @return 当前使用的伞名（parachute/ 下的文件夹名） */
    public String getParachuteName() { return this.parachuteName; }

    /** @return 是否锁定自摆动 */
    public boolean isWobbleLocked() { return this.wobbleLocked; }

    /** 设置锁定自摆动 */
    public void setWobbleLocked(boolean locked) { this.wobbleLocked = locked; markDirty(); }

    /** @return 伞面旋转 X（度） */
    public float getRotX() { return this.rotX; }
    /** @return 伞面旋转 Y（度） */
    public float getRotY() { return this.rotY; }
    /** @return 伞面旋转 Z（度） */
    public float getRotZ() { return this.rotZ; }

    /** 设置伞面旋转（度） */
    public void setRotation(float x, float y, float z) {
        this.rotX = x; this.rotY = y; this.rotZ = z; markDirty();
    }

    /** @return 枢轴偏移 X（格） */
    public float getPivotX() { return this.pivotX; }
    /** @return 枢轴偏移 Y（格） */
    public float getPivotY() { return this.pivotY; }
    /** @return 枢轴偏移 Z（格） */
    public float getPivotZ() { return this.pivotZ; }

    /** 设置枢轴点偏移（格） */
    public void setPivot(float x, float y, float z) {
        this.pivotX = x; this.pivotY = y; this.pivotZ = z; markDirty();
    }

    /** @return 整体偏移 X（格） */
    public float getOffX() { return this.offX; }
    /** @return 整体偏移 Y（格） */
    public float getOffY() { return this.offY; }
    /** @return 整体偏移 Z（格） */
    public float getOffZ() { return this.offZ; }

    /** 设置整体偏移（格） */
    public void setOffset(float x, float y, float z) {
        this.offX = x; this.offY = y; this.offZ = z; markDirty();
    }

    /** 设置伞名；空值回退默认（蘑菇伞） */
    public void setParachuteName(String name) {
        if (name == null || name.isEmpty()) {
            this.parachuteName = ParachuteManager.DEFAULT_PARACHUTE;
        } else {
            this.parachuteName = name;
        }
        markDirty();
    }

    /** 根据 DyeItem 设置颜色。直接取染料的标准贴图扩散色，由渲染器顶点染色实现 */
    public void setDisconnectOnLowSpeed(boolean enabled) { this.disconnectOnLowSpeed = enabled; markDirty(); }
    public void toggleDisconnectOnLowSpeed() { setDisconnectOnLowSpeed(!this.disconnectOnLowSpeed); }
    public void setDisconnectOnRedstonePulse(boolean enabled) { this.disconnectOnRedstonePulse = enabled; markDirty(); }
    public void toggleDisconnectOnRedstonePulse() { setDisconnectOnRedstonePulse(!this.disconnectOnRedstonePulse); }

    /**
     * 切换部署状态。
     * <p>开伞时重置物理 tick 计数器（启动宽限期）。
     * 收伞时清空所有方向状态和初始化标记，确保下次开伞时从干净状态开始。</p>
     *
     * @param deployed true = 开伞，false = 收伞
     */
    public void setDeployed(boolean deployed) {
        if (this.deployed == deployed) return;
        this.deployed = deployed;
        if (this.deployed) {
            this.physicsTickCount = 0;
        } else {
            this.velX = this.velY = this.velZ = 0;
            this.targetVelX = 0.0D;
            this.targetVelY = 1.0D;
            this.targetVelZ = 0.0D;
            this.localTargetVelX = 0.0D;
            this.localTargetVelY = 1.0D;
            this.localTargetVelZ = 0.0D;
            this.clientDirectionInitialized = false;
            this.hasLastSyncedDirection = false;
            this.renderDirInitialized = false;
        }
        markDirty();
    }

    // ============================================================
    // Sable 物理 tick — 仅服务端
    // ============================================================

    /**
     * Sable 物理框架每帧回调。
     * <p><b>只</b>在服务端运行，计算并施加拖拽力/阻尼扭矩。</p>
     *
     * <h3>平移阻力</h3>
     * <ol>
     *   <li>从物理体读取当前世界空间速度</li>
     *   <li>速度取反 → 世界空间拖拽方向（阻力始终与速度反向）</li>
     *   <li>乘 dragBase × dragCoefficient × delta → 拖拽力大小</li>
     *   <li>世界力 → 体空间冲量（transformNormalInverse）→ 沿伞包连接点施加</li>
     *   <li>每轴冲量受 maxImpulsePerAxis 限制，防止极端速度下物理爆炸</li>
     * </ol>
     *
     * <h3>旋转阻尼</h3>
     * <ol>
     *   <li>从物理体读取当前世界空间角速度</li>
     *   <li>转体空间，乘 -dragBase × rotationalDragCoefficient × delta → 扭矩</li>
     *   <li>每轴扭矩受 maxTorquePerAxis 限制</li>
     *   <li>施加为角冲量</li>
     * </ol>
     *
     * <h3>速度方向记录</h3>
     * <p>世界速度反方向归一化后存入 velX/Y/Z，同时经 body⁻¹ 转为体空间
     * 存入 localTargetVel，供后续同步包发送给客户端。</p>
     *
     * @param subLevel  Sable 物理子世界引用
     * @param bodyHandle 关联的刚体句柄
     * @param delta      物理帧时间步（秒）
     */
    @Override
    public void sable$physicsTick(ServerSubLevel subLevel, RigidBodyHandle bodyHandle, double delta) {
        if (!this.deployed || this.level == null || !bodyHandle.isValid()) return;

        // 世界坐标系的速度矢量
        Vector3d velocity = bodyHandle.getLinearVelocity(new Vector3d());
        double speed = velocity.length();

        // 伞面方向 = 世界速度的反方向，取反后归一化得到单位向量
        Vector3d worldDragDir = new Vector3d(-velocity.x, -velocity.y, -velocity.z);
        if (worldDragDir.lengthSquared() > 1.0E-6D) {
            worldDragDir.normalize();
            this.velX = worldDragDir.x;
            this.velY = worldDragDir.y;
            this.velZ = worldDragDir.z;
        } else {
            // 速度为零时退化到方块放置面方向
            Direction facing = getFacing();
            this.velX = facing.getStepX();
            this.velY = facing.getStepY();
            this.velZ = facing.getStepZ();
            worldDragDir.set(this.velX, this.velY, this.velZ);
        }

        // 体坐标系方向 = 世界方向 × body⁻¹（将世界空间的向量变换到物理体局部坐标系）
        // 视觉渲染在 BER 的体空间堆栈中取值，需要体相对朝向
        Vector3d localDragDir = subLevel.logicalPose()
                .transformNormalInverse(new Vector3d(worldDragDir), new Vector3d());
        if (localDragDir.lengthSquared() > 1.0E-6D) {
            localDragDir.normalize();
        }
        this.localTargetVelX = localDragDir.x;
        this.localTargetVelY = localDragDir.y;
        this.localTargetVelZ = localDragDir.z;

        // 低速切伞检测：
        //   开头有 LOW_SPEED_GRACE_TICKS 的宽限期防止一开伞就切
        //   宽限期过后且速度低于阈值且开关开启时才触发
        if (this.disconnectOnLowSpeed && this.physicsTickCount > ParachuteConfig.LOW_SPEED_GRACE_TICKS.get() && speed < this.disconnectSpeedThreshold) {
            this.setDeployed(false);
            return;
        }
        this.physicsTickCount++;

        // === 平移阻力 ===
        // force = -velocity × dragBase × dragCoefficient × delta
        double dragBase = ParachuteConfig.DRAG_BASE.get();
        double scale = -dragBase * this.dragCoefficient * delta;
        Vector3d worldDragForce = new Vector3d(velocity).mul(scale);
        // 世界空间力 → 体空间冲量
        Vector3d localImpulse = subLevel.logicalPose()
                .transformNormalInverse(worldDragForce, new Vector3d());
        clampVec3d(localImpulse, ParachuteConfig.MAX_IMPULSE_PER_AXIS.get());

        // 冲量施加点：伞包连接点（方块中心 + facing × offset）
        Direction facing = getFacing();
        double offset = getAttachOffset();
        Vector3d localBlockPos = new Vector3d(
                this.worldPosition.getX() + 0.5D + facing.getStepX() * offset,
                this.worldPosition.getY() + 0.5D + facing.getStepY() * offset,
                this.worldPosition.getZ() + 0.5D + facing.getStepZ() * offset);
        bodyHandle.applyImpulseAtPoint(localBlockPos, localImpulse);

        // === 旋转阻尼 ===
        // 对物理体局部坐标系的角速度施加阻力，让旋转逐渐停止
        Vector3d globalAngVel = bodyHandle.getAngularVelocity(new Vector3d());
        Vector3d localAngVel = subLevel.logicalPose()
                .transformNormalInverse(globalAngVel, new Vector3d());
        Vector3d torque = new Vector3d(localAngVel).mul(-dragBase * this.rotationalDragCoefficient * delta);
        clampVec3d(torque, ParachuteConfig.MAX_TORQUE_PER_AXIS.get());
        bodyHandle.applyAngularImpulse(torque);
    }

    // ============================================================
    // GUI（驾驶舱菜单）
    // ============================================================

    @Override
    public Component getDisplayName() {
        return Component.translatable("screen.create_parachute.parachute");
    }

    @Override
    public AbstractContainerMenu createMenu(int containerId, Inventory inventory, Player player) {
        return new ParachuteMenu(containerId, inventory, this);
    }

    // ============================================================
    // 持久化和同步
    // ============================================================

    /**
     * NBT 保存：将所有配置、部署状态、动画进度写入磁盘。
     * <p>方向矢量（vel/locaTargetVel）不在此处保存——它们由同步包实时传输。</p>
     */
    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putDouble("DragCoefficient", this.dragCoefficient);
        tag.putDouble("RotationalDragCoefficient", this.rotationalDragCoefficient);
        tag.putDouble("DisconnectSpeedThreshold", this.disconnectSpeedThreshold);
        tag.putBoolean("DisconnectOnLowSpeed", this.disconnectOnLowSpeed);
        tag.putBoolean("DisconnectOnRedstonePulse", this.disconnectOnRedstonePulse);
        tag.putBoolean("Deployed", this.deployed);
        tag.putInt("AnimationProgress", this.animationProgress);
        tag.putBoolean("Dyed", this.dyed);
        tag.putInt("DyeColor", this.dyeColorARGB);
        tag.putString("ParachuteName", this.parachuteName);
        tag.putBoolean("WobbleLocked", this.wobbleLocked);
        tag.putFloat("RotX", this.rotX);
        tag.putFloat("RotY", this.rotY);
        tag.putFloat("RotZ", this.rotZ);
        tag.putFloat("PivotX", this.pivotX);
        tag.putFloat("PivotY", this.pivotY);
        tag.putFloat("PivotZ", this.pivotZ);
        tag.putFloat("OffX", this.offX);
        tag.putFloat("OffY", this.offY);
        tag.putFloat("OffZ", this.offZ);
    }

    /**
     * NBT 加载：从磁盘恢复配置和状态。
     * <p>动画进度兼容旧存档：如果 tag 中没有 AnimationProgress 字段，
     * 根据 Deployed 的值推断初始状态。</p>
     */
    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        this.dragCoefficient = clamp(tag.getDouble("DragCoefficient"), MIN_K, ParachuteConfig.MAX_DRAG_COEFFICIENT.get());
        this.rotationalDragCoefficient = clamp(tag.getDouble("RotationalDragCoefficient"), MIN_K, ParachuteConfig.MAX_ROTATIONAL_DAMP_COEFFICIENT.get());
        this.disconnectSpeedThreshold = clamp(tag.getDouble("DisconnectSpeedThreshold"), MIN_DISCONNECT_SPEED, MAX_DISCONNECT_SPEED);
        this.disconnectOnLowSpeed = tag.getBoolean("DisconnectOnLowSpeed");
        this.disconnectOnRedstonePulse = tag.getBoolean("DisconnectOnRedstonePulse");
        readCommonTag(tag);
    }

    /**
     * 构建客户端 BE 同步标签（chunk 加载或方块更新时发送）。
     * <p>包含方向矢量、部署状态、动画进度和全部可调参数，
     * 确保客户端重新进入 chunk 时能立即恢复正确显示。</p>
     */
    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        CompoundTag tag = super.getUpdateTag(registries);
        tag.putDouble("VelX", this.velX);
        tag.putDouble("VelY", this.velY);
        tag.putDouble("VelZ", this.velZ);
        tag.putDouble("LocalVelX", this.localTargetVelX);
        tag.putDouble("LocalVelY", this.localTargetVelY);
        tag.putDouble("LocalVelZ", this.localTargetVelZ);
        tag.putBoolean("Deployed", this.deployed);
        tag.putInt("AnimationProgress", this.animationProgress);
        tag.putDouble("DragCoefficient", this.dragCoefficient);
        tag.putDouble("RotationalDragCoefficient", this.rotationalDragCoefficient);
        tag.putDouble("DisconnectSpeedThreshold", this.disconnectSpeedThreshold);
        tag.putBoolean("DisconnectOnLowSpeed", this.disconnectOnLowSpeed);
        tag.putBoolean("DisconnectOnRedstone", this.disconnectOnRedstonePulse);
        tag.putBoolean("Dyed", this.dyed);
        tag.putInt("DyeColor", this.dyeColorARGB);
        tag.putString("ParachuteName", this.parachuteName);
        // 变换 + 锁定设置：缺省时客户端 readCommonTag 会回退默认，导致重载丢失（如锁定变未锁定）
        tag.putBoolean("WobbleLocked", this.wobbleLocked);
        tag.putFloat("RotX", this.rotX);
        tag.putFloat("RotY", this.rotY);
        tag.putFloat("RotZ", this.rotZ);
        tag.putFloat("PivotX", this.pivotX);
        tag.putFloat("PivotY", this.pivotY);
        tag.putFloat("PivotZ", this.pivotZ);
        tag.putFloat("OffX", this.offX);
        tag.putFloat("OffY", this.offY);
        tag.putFloat("OffZ", this.offZ);
        return tag;
    }

    /**
     * 客户端接收 BE 同步标签。
     * <p>世界方向存入 targetVel 用于后续 lerp 平滑，体空间方向直接用于渲染。
     * 兼容旧版同步标签中缺少 LocalVelX 字段的情况。</p>
     */
    @Override
    public void handleUpdateTag(CompoundTag tag, HolderLookup.Provider registries) {
        super.handleUpdateTag(tag, registries);
        // 体坐标系方向 = 渲染用（优先取 LocalVel，兼容旧标签用 Vel）
        if (tag.contains("LocalVelX")) {
            this.localTargetVelX = tag.getDouble("LocalVelX");
            this.localTargetVelY = tag.getDouble("LocalVelY");
            this.localTargetVelZ = tag.getDouble("LocalVelZ");
            // 客户端 vel 始终为体空间，不要被世界空间的 VelX 污染
            if (!this.clientDirectionInitialized) {
                this.velX = this.localTargetVelX;
                this.velY = this.localTargetVelY;
                this.velZ = this.localTargetVelZ;
                this.prevVelX = this.velX;
                this.prevVelY = this.velY;
                this.prevVelZ = this.velZ;
            }
        } else {
            this.velX = tag.getDouble("VelX");
            this.velY = tag.getDouble("VelY");
            this.velZ = tag.getDouble("VelZ");
        }
        readCommonTag(tag);
        if (tag.contains("DragCoefficient")) {
            this.dragCoefficient = clamp(tag.getDouble("DragCoefficient"), MIN_K, ParachuteConfig.MAX_DRAG_COEFFICIENT.get());
            this.rotationalDragCoefficient = clamp(tag.getDouble("RotationalDragCoefficient"), MIN_K, ParachuteConfig.MAX_ROTATIONAL_DAMP_COEFFICIENT.get());
            this.disconnectSpeedThreshold = clamp(tag.getDouble("DisconnectSpeedThreshold"), MIN_DISCONNECT_SPEED, MAX_DISCONNECT_SPEED);
            this.disconnectOnLowSpeed = tag.getBoolean("DisconnectOnLowSpeed");
            this.disconnectOnRedstonePulse = tag.getBoolean("DisconnectOnRedstone");
        }
    }

    /** @return 标准 MC 方块实体更新包 */
    @Nullable
    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    // ============================================================
    // 渲染器用 getter（GPU 帧间插值）
    // ============================================================

    /** @return 当前世界空间速度方向 X */
    public double getVelX() { return this.velX; }
    /** @return 当前世界空间速度方向 Y */
    public double getVelY() { return this.velY; }
    /** @return 当前世界空间速度方向 Z */
    public double getVelZ() { return this.velZ; }

    /**
     * 伞面当前姿态四元数（帧间插值）。
     * <p>在 BER 渲染时通过 partialTick 插值 prevRenderQuat 和 renderQuat，
     * 获得当前帧的精确旋转姿态。</p>
     *
     * @param partialTick GPU 帧插值系数 [0, 1)
     * @return 插值后的四元数
     */
    public Quaternionf getRenderQuat(float partialTick) {
        return new Quaternionf(this.prevRenderQuat).slerp(this.renderQuat, partialTick);
    }

    /**
     * 飘动相位（帧间插值）。
     *
     * @param partialTick GPU 帧插值系数 [0, 1)
     * @return 插值后的飘动相位
     */
    public float getWobblePhase(float partialTick) {
        return Mth.lerp(partialTick, this.prevWobblePhase, this.wobblePhase);
    }

    /** @return 帧间插值后的当前速度方向 X */
    public double getRenderVelX(float partialTick) { return Mth.lerp(partialTick, this.prevVelX, this.velX); }
    /** @return 帧间插值后的当前速度方向 Y */
    public double getRenderVelY(float partialTick) { return Mth.lerp(partialTick, this.prevVelY, this.velY); }
    /** @return 帧间插值后的当前速度方向 Z */
    public double getRenderVelZ(float partialTick) { return Mth.lerp(partialTick, this.prevVelZ, this.velZ); }

    /**
     * 开伞动画进度的帧间插值 [0, 1]。
     * <p>0 = 完全收伞，1 = 完全开伞。渲染器用此值控制伞面模型骨骼动画。</p>
     */
    public float getRenderOpenRatio(float partialTick) {
        return Mth.clamp(Mth.lerp(partialTick, this.prevAnimationProgress, this.animationProgress) / (float) OPEN_ANIMATION_TICKS, 0.0F, 1.0F);
    }

    /**
     * 速度同步包到达客户端时的回调。
     * <p>参数 vx/vy/vz 是体坐标系方向（服务端已从世界方向转换），
     * 直接存入 localTargetVel 供渲染使用。</p>
     * <p>首次调用时同时初始化 prev 和 current 值，避免从 (0,0,0) 突变。</p>
     */
    public void setClientVel(double vx, double vy, double vz) {
        this.localTargetVelX = vx;
        this.localTargetVelY = vy;
        this.localTargetVelZ = vz;
        if (!this.clientDirectionInitialized) {
            this.prevVelX = vx;
            this.prevVelY = vy;
            this.prevVelZ = vz;
            this.velX = vx;
            this.velY = vy;
            this.velZ = vz;
            this.clientDirectionInitialized = true;
        }
    }

    /**
     * 锁定时的固定朝向四元数：按方块放置面方向悬挂（不跟随速度方向、不摆动）。
     * 与未部署时的方向一致，避免锁定后模型回落到 BlockBench 原始朝向（头朝下）。
     */
    public Quaternionf getLockedQuat() {
        Direction facing = getFacing();
        Quaternionf q = new Quaternionf().rotateTo(0, -1, 0,
                facing.getStepX(), facing.getStepY(), facing.getStepZ());
        if (!ParachuteAssets.isBedrock(getParachuteName())) {
            q.mul(new Quaternionf().rotateY((float) Math.toRadians(-90)));
        }
        return q;
    }

    // ============================================================
    // 工具方法
    // ============================================================

    /**
     * 获取当前方块状态中存储的放置面方向。
     * <p>如果世界为空或当前 block 没有 FACING 属性，退化返回 {@link Direction#UP}。</p>
     */
    private Direction getFacing() {
        if (this.level == null) return Direction.UP;
        BlockState state = this.getBlockState();
        if (state.hasProperty(ParachuteBlock.FACING)) {
            return state.getValue(ParachuteBlock.FACING);
        }
        return Direction.UP;
    }

    /** @return 放置面方向 X 分量（用于计算伞包连接点偏移） */
    public float getFacingOffsetX() { return (float) getFacing().getStepX(); }
    /** @return 放置面方向 Y 分量 */
    public float getFacingOffsetY() { return (float) getFacing().getStepY(); }
    /** @return 放置面方向 Z 分量 */
    public float getFacingOffsetZ() { return (float) getFacing().getStepZ(); }

    /**
     * 伞包连接点相对于方块中心的偏移量。
     * <p>负值表示沿放置面反方向（如 facing=UP 时 offset=-0.25 表示伞包在方块下方 0.25 格）。</p>
     */
    public float getAttachOffset() { return -0.25f; }

    /** 标记方块实体需要保存（触发 NBT 写入 + 客户端同步），等同于 {@link BlockEntity#setChanged()} */
    private void markDirty() { this.setChanged(); }

    /**
     * 同步 blockstate 中 deployed 属性与内存状态一致。
     * <p>只在服务端运行。防止物理帧直接改 private field 导致 blockstate 不同步。</p>
     */
    private void syncDeployedState() {
        if (this.level == null || this.level.isClientSide) return;
        BlockState state = this.getBlockState();
        if (state.hasProperty(ParachuteBlock.DEPLOYED) && state.getValue(ParachuteBlock.DEPLOYED) != this.deployed) {
            this.level.setBlock(this.worldPosition, state.setValue(ParachuteBlock.DEPLOYED, this.deployed), Block.UPDATE_CLIENTS);
        }
    }

    /** 数值钳位：确保 value ∈ [min, max] */
    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 向量各轴钳位到 [-maxAbs, maxAbs]，防止物理冲量溢出 */
    private static void clampVec3d(Vector3d v, double maxAbs) {
        v.x = Math.max(-maxAbs, Math.min(maxAbs, v.x));
        v.y = Math.max(-maxAbs, Math.min(maxAbs, v.y));
        v.z = Math.max(-maxAbs, Math.min(maxAbs, v.z));
    }

    /** 整数钳位 */
    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 读取 loadAdditional / handleUpdateTag 公共字段 */
    private void readCommonTag(CompoundTag tag) {
        this.deployed = tag.getBoolean("Deployed");
        this.animationProgress = tag.contains("AnimationProgress")
                ? clampInt(tag.getInt("AnimationProgress"), 0, OPEN_ANIMATION_TICKS)
                : clampInt(tag.getBoolean("Deployed") ? OPEN_ANIMATION_TICKS : 0, 0, OPEN_ANIMATION_TICKS);
        this.prevAnimationProgress = this.animationProgress;
        this.dyed = tag.getBoolean("Dyed");
        // 染料颜色：新档为 ARGB int，旧档为字符串后缀（"" / "_blue" / "_yellow_green"），做迁移
        if (tag.contains("DyeColor", net.minecraft.nbt.Tag.TAG_INT)) {
            this.dyeColorARGB = tag.getInt("DyeColor");
        } else if (tag.contains("DyeColor", net.minecraft.nbt.Tag.TAG_STRING)) {
            this.dyeColorARGB = dyeColorFromLegacySuffix(tag.getString("DyeColor"));
        }
        // 伞名：缺省回退蘑菇伞
        this.parachuteName = tag.contains("ParachuteName")
                ? tag.getString("ParachuteName")
                : ParachuteManager.DEFAULT_PARACHUTE;
        if (this.parachuteName.isEmpty()) {
            this.parachuteName = ParachuteManager.DEFAULT_PARACHUTE;
        }
        // 变换设置
        this.wobbleLocked = tag.getBoolean("WobbleLocked");
        this.rotX = tag.getFloat("RotX");
        this.rotY = tag.getFloat("RotY");
        this.rotZ = tag.getFloat("RotZ");
        this.pivotX = tag.getFloat("PivotX");
        this.pivotY = tag.getFloat("PivotY");
        this.pivotZ = tag.getFloat("PivotZ");
        this.offX = tag.getFloat("OffX");
        this.offY = tag.getFloat("OffY");
        this.offZ = tag.getFloat("OffZ");
    }

    /**
     * 旧存档迁移：将旧版字符串颜色后缀转换为 ARGB 染料色。
     * <p>旧格式："" = 默认红，"_yellow_green" = 黄绿（对应 LIME 染料），其余为 "_" + 染料序列名。</p>
     */
    private static int dyeColorFromLegacySuffix(String suffix) {
        if (suffix == null || suffix.isEmpty()) {
            return 0xFF000000 | net.minecraft.world.item.DyeColor.RED.getTextureDiffuseColor();
        }
        String name = suffix.startsWith("_") ? suffix.substring(1) : suffix;
        if ("yellow_green".equals(name)) {
            name = "lime";
        }
        for (net.minecraft.world.item.DyeColor c : net.minecraft.world.item.DyeColor.values()) {
            if (c.getSerializedName().equals(name)) {
                return 0xFF000000 | c.getTextureDiffuseColor();
            }
        }
        return 0xFF000000 | net.minecraft.world.item.DyeColor.RED.getTextureDiffuseColor();
    }

    /**
     * 归一化 velX/Y/Z 为单位向量。
     * <p>长度为 0 时退化到 (0, 1, 0)，表示默认向上方向。</p>
     */
    private void normalizeDirection() {
        double lengthSq = this.velX * this.velX + this.velY * this.velY + this.velZ * this.velZ;
        if (lengthSq <= 1.0E-6D) {
            this.velX = 0.0D;
            this.velY = 1.0D;
            this.velZ = 0.0D;
            return;
        }
        double invLength = 1.0D / Math.sqrt(lengthSq);
        this.velX *= invLength;
        this.velY *= invLength;
        this.velZ *= invLength;
    }
}
