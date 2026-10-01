package com.create.parachute.compat.synaxis;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

/**
 * 「坐垫模式伞包」与 Synaxis 控制椅之间的桥。
 *
 * <p>伞包的方块实体继承的是原版 {@code BlockEntity}，而 Synaxis 的控制椅是
 * {@code ControlChairBlockEntity extends OnPhysicsBodyBlockEntity extends NetworkBlockEntity}
 * （状态同步 / UI / 电路外设全在 Synaxis 自己那套底座上），两者无法同时继承。
 * 所以这里采用「幽灵椅子」做法：伞包 BE 内部<b>持有一个真正的 Synaxis 椅子 BE</b>，
 * 由伞包 BE 负责 tick / 存盘 / 开界面，椅子自己的电路外设注册（push 到
 * {@code CimulinkLevelRuntime}）和输入会话逻辑就自然生效。</p>
 *
 * <p>实现类（{@link SynaxisChairBridge}）只通过反射访问 Synaxis 的类，因此
 * <b>没有装 Synaxis 时本接口依然可以安全加载</b>，只是永远不会拿到实例。</p>
 */
public interface SynaxisChairSupport {
    /** 每 tick 驱动椅子（{@code clientSide} 决定调 serverTick 还是 clientTick）。 */
    void tick(boolean clientSide);

    /** 把椅子的 NBT 写进给定标签（伞包 BE 存盘时调用）。 */
    void save(CompoundTag tag, HolderLookup.Provider registries);

    /** 从给定标签恢复椅子状态。 */
    void load(CompoundTag tag, HolderLookup.Provider registries);

    /** 打开椅子的配置界面（服务端调用）。 */
    boolean openSettings(ServerPlayer player);

    /**
     * 幽灵椅子的 «网络/UI 后端»（Synaxis 的 {@code NetworkBlockEntitySupport}）。
     *
     * <p>伞包 BE 通过它实现 Synaxis 的 {@code NetworkBlockEntityAccess}：那个接口除了
     * {@code networkSupport()} 之外的成员全是 default 方法，所以只要把这个后端交出去，
     * Synaxis 的按坐标同步 / 命令 / UI 会话就都能落到幽灵椅子上（双视角与设置面板靠它）。</p>
     *
     * @return 后端对象；幽灵椅子不存在或反射失败时为 {@code null}
     */
    @Nullable
    Object networkSupport();

    /** 拆掉椅子（注销电路外设、清理信号与会话）。 */
    void release();
}
