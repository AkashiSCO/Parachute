package com.create.parachute.parachute;

import com.create.parachute.ParachuteConfig;
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
 *   <li>座位高度来自配置 {@code visual.seatHeight}，两端各自读，改了立刻生效（坐着的玩家下一 tick 就跟着动）</li>
 * </ul>
 */
public class ParachuteSeatEntity extends Entity {

    /** 座位所在的方块位置（同步字段：客户端也要用它算乘客位置） */
    private static final EntityDataAccessor<BlockPos> DATA_SEAT_POS =
            SynchedEntityData.defineId(ParachuteSeatEntity.class, EntityDataSerializers.BLOCK_POS);

    /** 座位高度：座位点相对方块底面的偏移（格），负值 = 沉到方块底面之下，见配置 seatHeight */
    private static double seatHeight() {
        return ParachuteConfig.SEAT_HEIGHT.get();
    }

    public ParachuteSeatEntity(EntityType<?> type, Level level) {
        super(type, level);
        this.noPhysics = true;
    }

    public ParachuteSeatEntity(Level level, BlockPos pos) {
        this(ModEntities.PARACHUTE_SEAT.get(), level);
        this.setSeatPos(pos);
        this.setPos(pos.getX() + 0.5D, pos.getY() + seatHeight(), pos.getZ() + 0.5D);
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
     * <p>用同步字段存，客户端也能拿到（算乘客位置要用）；实体本身可能落在方块下方
     * （seatHeight 为负时），所以不能直接用 {@code blockPosition()} 判断"这个方块还是不是坐垫伞包"。</p>
     */
    public BlockPos seatPos() {
        return this.entityData.get(DATA_SEAT_POS);
    }

    private void setSeatPos(BlockPos pos) {
        this.entityData.set(DATA_SEAT_POS, pos);
    }

    /** 这个方块位置是否已经有座位实体（不管上面有没有人） */
    public static boolean isOccupied(Level level, BlockPos pos) {
        // 实体可能落在方块下方（seatHeight 为负），所以按 seatPos() 比对，而不是靠包围盒
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
     * 乘客位置 = 座位方块中心 + 配置里的座位高度。
     *
     * <p>两端都会调用这里，所以改 {@code visual.seatHeight} 后<b>正在坐着的玩家下一 tick 就跟着动</b>，
     * 不依赖服务端同步实体位置（坐垫实体本身可以不动）。</p>
     */
    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
        if (this.hasPassenger(passenger)) {
            BlockPos pos = this.seatPos();
            moveFunction.accept(passenger, pos.getX() + 0.5D, pos.getY() + seatHeight(), pos.getZ() + 0.5D);
        }
    }

    /** 下马时给到方块上方，避免卡进坐垫方块里 */
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
