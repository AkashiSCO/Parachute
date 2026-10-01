package com.create.parachute.compat.synaxis;

import com.create.parachute.ParachuteMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Method;

/**
 * 幽灵椅子：用反射在「坐垫模式伞包」的坐标上养活一个真正的
 * {@code com.verr1.synaxis.content.blocks.controlchair.ControlChairBlockEntity}。
 *
 * <h3>为什么是反射</h3>
 * <p>本模组不把 Synaxis 作为编译期依赖。这里的每个 Synaxis 类型都用字符串类名解析，
 * 所以没装 Synaxis 时这个类照样能被 JVM 加载（只是 {@link #create} 会失败并返回 null）。</p>
 *
 * <h3>它为什么能工作</h3>
 * <ul>
 *   <li>椅子的电路外设是<b>自注册</b>的：{@code ControlChairBlockEntity#refreshPlantRegistration()}
 *       在自己的 tick 里往 {@code CimulinkLevelRuntime} 注册 {@code ControlChairPlantPort}，
 *       不需要谁去扫描接口 —— 只要这个 BE 被 tick、有 level/坐标就行</li>
 *   <li>椅子的输入会话由它自己的 tick 维护（{@code findPilot()} 找驾驶员）</li>
 *   <li>配置界面走 {@code NetworkBlockEntityAccess#openSettings(ServerPlayer)}，UI 是网络下发的</li>
 * </ul>
 *
 * <h3>已知差异</h3>
 * <p>幽灵椅子用 Synaxis 自己的椅子默认方块状态（而不是伞包的方块状态）：椅子的
 * {@code OnPhysicsBodyBlockEntity} 会读 {@code axis_along_first}/{@code flipped} 这类属性，
 * 拿我们的状态会直接抛异常。代价是它眼里的"自己的方块状态"是控制椅而不是伞包。</p>
 */
public final class SynaxisChairBridge implements SynaxisChairSupport {
    private static final String CLASS_CHAIR_BE =
            "com.verr1.synaxis.content.blocks.controlchair.ControlChairBlockEntity";
    private static final String CLASS_BE_TYPES = "com.verr1.synaxis.registry.SynaxisBlockEntities";
    private static final String CLASS_BLOCKS = "com.verr1.synaxis.registry.SynaxisBlocks";
    private static final String ENTRY_NAME = "CONTROL_CHAIR";

    private final Object chair;
    private final Method tick;
    private final Method initialize;
    private final Method networkSupport;
    private final Method remove;
    private final Method invalidate;
    private final Method saveAdditional;
    private final Method loadAdditional;
    private final Method openSettings;
    private boolean initialized;
    private boolean tickErrorLogged;

    /** 反射失败时返回 {@code null}（并只打一条日志），调用方按"没有椅子"处理。 */
    public static SynaxisChairSupport create(Level level, BlockPos pos, BlockState state) {
        try {
            return new SynaxisChairBridge(level, pos, state);
        } catch (Throwable t) {
            ParachuteMod.LOGGER.warn(
                    "[create_parachute] 创建 Synaxis 幽灵控制椅失败（坐垫模式仍可用，但没有控制椅功能）：{}",
                    t.toString());
            return null;
        }
    }

    private SynaxisChairBridge(Level level, BlockPos pos, BlockState state) throws Exception {
        Class<?> chairClass = Class.forName(CLASS_CHAIR_BE);

        // 椅子的注册项：Registrate 的 BlockEntityEntry#get() / BlockEntry#get()
        Object beEntry = Class.forName(CLASS_BE_TYPES).getField(ENTRY_NAME).get(null);
        BlockEntityType<?> chairType = (BlockEntityType<?>) beEntry.getClass().getMethod("get").invoke(beEntry);
        Object blockEntry = Class.forName(CLASS_BLOCKS).getField(ENTRY_NAME).get(null);
        Block chairBlock = (Block) blockEntry.getClass().getMethod("get").invoke(blockEntry);

        this.chair = chairClass
                .getConstructor(BlockEntityType.class, BlockPos.class, BlockState.class)
                .newInstance(chairType, pos, chairBlock.defaultBlockState());

        // level 是 protected/public 字段的 setter；两边都试一遍
        Method setLevel = BlockEntity.class.getDeclaredMethod("setLevel", Level.class);
        setLevel.setAccessible(true);
        setLevel.invoke(this.chair, level);

        // Create 系的 SmartBlockEntity（NetworkBlockEntity 的父类）必须走 initialize() + tick()：
        // initialize() 才会跑 addBehaviours（Synaxis 的网络支持/状态同步就挂在那些 behaviour 上）。
        // 之前直接调 serverTick()/clientTick() 时初始化没做，结果 openSettings 静默返回 false。
        this.initialize = chairClass.getMethod("initialize");
        this.tick = chairClass.getMethod("tick");
        this.networkSupport = chairClass.getMethod("networkSupport");
        this.remove = chairClass.getMethod("remove");
        this.invalidate = chairClass.getMethod("invalidate");
        this.saveAdditional = declared(BlockEntity.class, "saveAdditional", CompoundTag.class, HolderLookup.Provider.class);
        this.loadAdditional = declared(BlockEntity.class, "loadAdditional", CompoundTag.class, HolderLookup.Provider.class);
        // openSettings 是 NetworkBlockEntityAccess 的 default 方法，getMethod 能找到
        this.openSettings = chairClass.getMethod("openSettings", ServerPlayer.class);

        ParachuteMod.LOGGER.info("[create_parachute] Synaxis 幽灵控制椅已创建 @ {}", pos);
    }

    private static Method declared(Class<?> owner, String name, Class<?>... params) throws NoSuchMethodException {
        Method method = owner.getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method;
    }

    @Override
    public void tick(boolean clientSide) {
        try {
            ensureInitialized();
            this.tick.invoke(this.chair);
        } catch (Throwable t) {
            if (!this.tickErrorLogged) {
                this.tickErrorLogged = true;
                ParachuteMod.LOGGER.warn("[create_parachute] Synaxis 幽灵控制椅 tick 失败：{}", t.toString());
            }
        }
    }

    /** Create 系 SmartBlockEntity 的初始化只能做一次（它会注册 behaviours / 网络支持）。 */
    private void ensureInitialized() throws Exception {
        if (!this.initialized) {
            this.initialized = true;
            this.initialize.invoke(this.chair);
        }
    }

    @Override
    @Nullable
    public Object networkSupport() {
        try {
            ensureInitialized();
            return this.networkSupport.invoke(this.chair);
        } catch (Throwable t) {
            ParachuteMod.LOGGER.warn("[create_parachute] 取 Synaxis 幽灵控制椅网络后端失败：{}", t.toString());
            return null;
        }
    }

    @Override
    public void save(CompoundTag tag, HolderLookup.Provider registries) {
        try {
            this.saveAdditional.invoke(this.chair, tag, registries);
        } catch (Throwable t) {
            ParachuteMod.LOGGER.warn("[create_parachute] Synaxis 幽灵控制椅存盘失败：{}", t.toString());
        }
    }

    @Override
    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        try {
            this.loadAdditional.invoke(this.chair, tag, registries);
        } catch (Throwable t) {
            ParachuteMod.LOGGER.warn("[create_parachute] Synaxis 幽灵控制椅读盘失败：{}", t.toString());
        }
    }

    @Override
    public boolean openSettings(ServerPlayer player) {
        try {
            Object result = this.openSettings.invoke(this.chair, player);
            boolean opened = result instanceof Boolean value && value;
            ParachuteMod.LOGGER.info("[create_parachute] openSettings({}) -> {}", player.getGameProfile().getName(), opened);
            return opened;
        } catch (Throwable t) {
            ParachuteMod.LOGGER.warn("[create_parachute] 打开 Synaxis 控制椅界面失败：{}", t.toString());
            return false;
        }
    }

    @Override
    @Nullable
    public Object chairObject() {
        return this.chair;
    }

    @Override
    public void release() {
        // remove()/invalidate() 是 BlockEntity 的生命周期回调，椅子的实现会顺便注销电路外设、
        // 清控制器线缆信号、释放输入会话（比它自己的 destroy() 更安全，不会触发掉落之类）
        try {
            this.remove.invoke(this.chair);
        } catch (Throwable ignored) {
        }
        try {
            this.invalidate.invoke(this.chair);
        } catch (Throwable ignored) {
        }
    }
}
