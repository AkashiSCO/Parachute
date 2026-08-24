package com.create.parachute.parachute;

import com.create.parachute.registry.ModMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.item.ItemStack;

public class ParachuteMenu extends AbstractContainerMenu {
    public static final int BTN_K_MINUS = 0;
    public static final int BTN_K_PLUS = 1;
    public static final int BTN_V_MINUS = 2;
    public static final int BTN_V_PLUS = 3;
    public static final int BTN_TOGGLE_LOW_SPEED = 4;
    public static final int BTN_TOGGLE_REDSTONE_BREAK = 5;
    public static final int BTN_R_MINUS = 6;
    public static final int BTN_R_PLUS = 7;
    public static final int BTN_SAVE = 8;

    private final ParachuteBlockEntity blockEntity;
    private final ContainerLevelAccess access;

    private final DataSlot dragCoefficient = DataSlot.standalone();
    private final DataSlot rotationalDragCoefficient = DataSlot.standalone();
    private final DataSlot disconnectSpeed = DataSlot.standalone();
    private final DataSlot disconnectOnLowSpeed = DataSlot.standalone();
    private final DataSlot disconnectOnRedstone = DataSlot.standalone();

    public ParachuteMenu(int containerId, Inventory inventory, BlockPos blockPos) {
        this(containerId, inventory,
                inventory.player.level().getBlockEntity(blockPos) instanceof ParachuteBlockEntity be ? be : null);
    }

    public ParachuteMenu(int containerId, Inventory inventory, ParachuteBlockEntity blockEntity) {
        super(ModMenus.PARACHUTE_MENU.get(), containerId);
        if (blockEntity == null) {
            throw new IllegalStateException("Parachute block entity not found");
        }

        this.blockEntity = blockEntity;
        this.access = ContainerLevelAccess.create(blockEntity.getLevel(), blockEntity.getBlockPos());

        this.addDataSlot(this.dragCoefficient);
        this.addDataSlot(this.rotationalDragCoefficient);
        this.addDataSlot(this.disconnectSpeed);
        this.addDataSlot(this.disconnectOnLowSpeed);
        this.addDataSlot(this.disconnectOnRedstone);

        this.syncFromBlockEntity();
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        return this.access.evaluate((level, pos) -> {
            switch (id) {
                case BTN_K_MINUS -> this.blockEntity.adjustDragCoefficient(-1.0D);
                case BTN_K_PLUS -> this.blockEntity.adjustDragCoefficient(1.0D);
                case BTN_V_MINUS -> this.blockEntity.adjustDisconnectSpeedThreshold(-0.05D);
                case BTN_V_PLUS -> this.blockEntity.adjustDisconnectSpeedThreshold(0.05D);
                case BTN_TOGGLE_LOW_SPEED -> this.blockEntity.toggleDisconnectOnLowSpeed();
                case BTN_TOGGLE_REDSTONE_BREAK -> this.blockEntity.toggleDisconnectOnRedstonePulse();
                case BTN_R_MINUS -> this.blockEntity.adjustRotationalDragCoefficient(-1.0D);
                case BTN_R_PLUS -> this.blockEntity.adjustRotationalDragCoefficient(1.0D);
                case BTN_SAVE -> {
                    this.blockEntity.setChanged();
                    player.closeContainer();
                }
                default -> {
                    return false;
                }
            }
            this.syncFromBlockEntity();
            return true;
        }, false);
    }

    private void syncFromBlockEntity() {
        this.dragCoefficient.set((int) Math.round(this.blockEntity.getDragCoefficient() * 100.0D));
        this.rotationalDragCoefficient.set((int) Math.round(this.blockEntity.getRotationalDragCoefficient() * 100.0D));
        this.disconnectSpeed.set((int) Math.round(this.blockEntity.getDisconnectSpeedThreshold() * 100.0D));
        this.disconnectOnLowSpeed.set(this.blockEntity.isDisconnectOnLowSpeed() ? 1 : 0);
        this.disconnectOnRedstone.set(this.blockEntity.isDisconnectOnRedstonePulse() ? 1 : 0);
    }

    public double getDragCoefficient() {
        return this.dragCoefficient.get() / 100.0D;
    }

    public double getRotationalDragCoefficient() {
        return this.rotationalDragCoefficient.get() / 100.0D;
    }

    public double getDisconnectSpeedThreshold() {
        return this.disconnectSpeed.get() / 100.0D;
    }

    public boolean isDisconnectOnLowSpeed() {
        return this.disconnectOnLowSpeed.get() != 0;
    }

    public boolean isDisconnectOnRedstone() {
        return this.disconnectOnRedstone.get() != 0;
    }

    public BlockPos getBlockPos() {
        return this.blockEntity.getBlockPos();
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(this.access, player, this.blockEntity.getBlockState().getBlock());
    }

    public void updateLocalConfig(double drag, double rotDrag, double speed, boolean lowSpeed, boolean redstone) {
        this.blockEntity.setDragCoefficient(drag);
        this.blockEntity.setRotationalDragCoefficient(rotDrag);
        this.blockEntity.setDisconnectSpeedThreshold(speed);
        this.blockEntity.setDisconnectOnLowSpeed(lowSpeed);
        this.blockEntity.setDisconnectOnRedstonePulse(redstone);
        this.syncFromBlockEntity();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        return ItemStack.EMPTY;
    }
}
