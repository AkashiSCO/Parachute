package com.create.parachute.parachute;

import com.create.parachute.registry.ModEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * 坐垫伞包的"座位"实体：和机械动力坐垫同一套做法 —— 方块本身只是外观，真正让人坐上去的是
 * 一个不可见的小实体（原版没有任何"通用座位"，玩家只能骑实体）。
 *
 * <ul>
 *   <li>{@link #sitDown}：在方块位置生成座位并让玩家骑上去（一个方块只能坐一个人）</li>
 *   <li>方块被破坏 / 玩家离开 → 实体自己消失（{@link #tick()} 与 {@link #removePassenger}）</li>
 *   <li>Shift 下马是原版骑乘机制（客户端发下马包 → {@code stopRiding} → {@link #removePassenger}）</li>
 *   <li>不落盘（{@code noSave}），重进世界时座位由玩家重新右键生成，不会留下幽灵实体</li>
 *   <li>座位点 = 枢轴点的世界位置（方块中心 + 悬挂偏移 + 整体偏移，见 {@link #seatPoint()}）——
 *       坐在枢轴上；调「枢轴点」是把模型在枢轴上滑动，调「整体偏移」才是连座位一起挪。
 *       两端各自读，改了立刻生效（坐着的玩家下一 tick 就跟着动）</li>
 * </ul>
 */
public class ParachuteSeatEntity extends Entity {

    /** 座位所在的方块位置（同步字段：客户端也要用它算乘客位置） */
    private static final EntityDataAccessor<BlockPos> DATA_SEAT_POS =
            SynchedEntityData.defineId(ParachuteSeatEntity.class, EntityDataSerializers.BLOCK_POS);

    public ParachuteSeatEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public ParachuteSeatEntity(Level level, BlockPos pos) {
        this(ModEntities.PARACHUTE_SEAT.get(), level);
        this.setSeatPos(pos);
        this.setPos(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D);
    }

    /**
     * 让 {@code passenger} 坐到 {@code pos} 这个坐垫伞包上。
     *
     * @return 成功坐上返回 true；这个方块已经有人坐着 / 骑乘被拒返回 false
     */
    public static boolean sitDown(Level level, BlockPos pos, Entity passenger) {
        if (level.isClientSide || isOccupied(level, pos)) {
            return false;
        }
        ParachuteSeatEntity seat = new ParachuteSeatEntity(level, pos);
        if (!level.addFreshEntity(seat)) {
            return false;
        }
        if (!passenger.startRiding(seat, true)) {
            seat.discard();
            return false;
        }
        return true;
    }

    /**
     * 座位对应的方块位置。
     *
     * <p>用同步字段存，客户端也能拿到（算乘客位置要用）；枢轴偏移可以把座位点挪出方块之外，
     * 所以不能直接用 {@code blockPosition()} 判断"这个方块还是不是坐垫伞包"。</p>
     */
    public BlockPos seatPos() {
        return this.entityData.get(DATA_SEAT_POS);
    }

    private void setSeatPos(BlockPos pos) {
        this.entityData.set(DATA_SEAT_POS, pos);
    }

    /** 这个方块位置是否已经有座位实体（不管上面有没有人） */
    public static boolean isOccupied(Level level, BlockPos pos) {
        // 座位点可能被枢轴偏移挪走，所以按 seatPos() 比对，而不是靠包围盒
        for (ParachuteSeatEntity seat : level.getEntitiesOfClass(ParachuteSeatEntity.class,
                new AABB(pos).inflate(2.0D))) {
            if (seat.isAlive() && pos.equals(seat.seatPos())) {
                return true;
            }
        }
        return false;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SEAT_POS, BlockPos.ZERO);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    /** 一个坐垫只坐一个人 */
    @Override
    protected boolean canAddPassenger(Entity passenger) {
        return this.getPassengers().isEmpty();
    }

    @Override
    protected boolean canRide(Entity passenger) {
        return passenger instanceof LivingEntity;
    }

    /**
     * 座位点 = <b>枢轴点的世界位置</b>（旋转中心）：
     * 方块中心 + 悬挂偏移（放置面方向 × {@code getAttachOffset()}）+ 整体偏移（{@code OffX/Y/Z}）。
     *
     * <p>注意这里<b>不含</b> {@code PivotX/Y/Z}：枢轴偏移是"枢轴相对模型的位移"，施加在旋转之后，
     * 调它只会让<b>模型</b>在枢轴上滑动，枢轴点（= 玩家坐的位置）待在原地 —— 想让整把伞连座位一起
     * 挪，用整体偏移（它在旋转之前施加，枢轴和模型一起走）。</p>
     *
     * <p>先有枢轴点、再有乘坐：坐在枢轴上。这个点与伞面朝向无关（它是旋转前的 pose 原点），
     * 所以这里不用管旋转，式子两边完全一致（F3+B 里那个黄/白小方块画的就是这个点）。</p>
     *
     * <p>两端都各自从方块实体读（这些值在 BE 的同步标签里，客户端也有），所以<b>改完立刻生效</b>：
     * 坐着的玩家下一 tick 就跟着挪；方块实体不在（比如方块刚被换掉）时退回方块中心。</p>
     */
    private Vec3 seatPoint() {
        BlockPos pos = this.seatPos();
        double x = pos.getX() + 0.5D;
        double y = pos.getY() + 0.5D;
        double z = pos.getZ() + 0.5D;
        if (this.level().getBlockEntity(pos) instanceof ParachuteBlockEntity be) {
            double dist = be.getAttachOffset();
            x += be.getFacingOffsetX() * dist;
            y += be.getFacingOffsetY() * dist;
            z += be.getFacingOffsetZ() * dist;
            x += be.getOffX();
            y += be.getOffY();
            z += be.getOffZ();
        }
        return new Vec3(x, y, z);
    }

    /**
     * 乘客位置 = {@link #seatPoint()}。
     *
     * <p>两端都会调用这里，所以改枢轴偏移后<b>正在坐着的玩家下一 tick 就跟着动</b>，
     * 不依赖服务端同步实体位置（坐垫实体本身可以不动）。</p>
     *
     * <p><b>坐标要写"座位所在方块自己的坐标系"，两种情况下都是这一行：</b></p>
     * <ul>
     *   <li>普通方块：{@code seatPos()} 是世界坐标，乘客直接落在世界坐标上；</li>
     *   <li>物理子世界（Sable）：方块活在保留区坐标里（例：x≈20481032，物理体只改自己的位姿、
     *       方块坐标不动），{@code seatPos()} 就是保留区坐标。本实体类型登记在
     *       {@code sable:retain_in_sub_level} 里，Sable 会把这类实体当成"船的一部分"按同样坐标
     *       存档/同步，并在 {@code Entity.positionRider(Entity)} 的 TAIL 上（{@code kickRidingEntity}）
     *       把乘客的坐标从子世界本地坐标换算成世界坐标 —— 所以这里交给它算就行。</li>
     * </ul>
     *
     * <p>踩过的坑：在这里自己写 {@code sable$setPlotPosition}，会让座位实体的世界坐标每 tick
     * 被 Sable 改写，乘客拿到的值就变成"世界坐标却被 Sable 当成子世界本地坐标"，被换算到天边
     * （表现为乱弹）；反过来自己换算成世界坐标则会和服务端的物理位姿打架。</p>
     */
    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
        if (!this.hasPassenger(passenger)) {
            return;
        }
        Vec3 point = seatPoint();
        // 减掉乘客自己的"骑乘附着点"（玩家是 (0, 0.6, 0)）：这样落在枢轴点上的是乘客的
        // 座位/髋部（原版 positionRider 也是这个算法），而不是脚底 —— 之前直接写点，
        // 人就高出枢轴点约 0.6 格。不同生物这个值不同，用 API 取，别写死。
        Vec3 attachment = passenger.getVehicleAttachmentPoint(this);
        moveFunction.accept(passenger, point.x - attachment.x, point.y - attachment.y, point.z - attachment.z);
    }

    /**
     * 和 Create 的 {@code SeatEntity} 一致：位置变了对齐碰撞盒（原版 {@code setPos} 是按最小角摆盒子的）。
     */
    @Override
    public void setPos(double x, double y, double z) {
        super.setPos(x, y, z);
        AABB box = this.getBoundingBox();
        this.setBoundingBox(box.move(new Vec3(x, y, z).subtract(box.getCenter())));
    }

    /**
     * 和 Create 的 {@code SeatEntity} 一致：座位自己的速度永远是零 —— 位置只由骑乘和物理体决定，
     * 免得被别的实体推着走。
     */
    @Override
    public void setDeltaMovement(Vec3 movement) {
    }

    /** 下马时给到方块上方，避免卡进坐垫方块里（物理体上返回的是该方块坐标系里的点，Sable 会换算） */
    @Override
    public Vec3 getDismountLocationForPassenger(LivingEntity passenger) {
        BlockPos pos = this.seatPos();
        return new Vec3(this.getX(), pos.getY() + 1.0D, this.getZ());
    }

    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            return;
        }
        // 没人坐了，或者对应方块已经不是坐垫伞包（被破坏/被扳手换回普通伞包/被藏起来）→ 消失
        if (this.getPassengers().isEmpty() || !isSeatBlock(this.level(), this.seatPos())) {
            this.discard();
        }
    }

    @Override
    protected void removePassenger(Entity passenger) {
        super.removePassenger(passenger);
        if (this.getPassengers().isEmpty()) {
            this.discard();
        }
    }

    /** 这个位置还是"坐垫伞包"吗（方块类型不变、SEAT 仍为 true） */
    private static boolean isSeatBlock(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        return state.getBlock() instanceof ParachuteBlock && state.getValue(ParachuteBlock.SEAT);
    }

    @Nullable
    @Override
    public net.minecraft.world.item.ItemStack getPickResult() {
        return null;
    }
}
