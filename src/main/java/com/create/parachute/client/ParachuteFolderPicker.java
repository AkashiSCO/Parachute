package com.create.parachute.client;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * 伞选择入口：打开游戏内可滚动伞列表（任意数量的伞都能选）。
 * 不再尝试系统文件对话框（本环境 AWT 为 headless，JFileChooser/FileDialog 不可用）。
 */
public final class ParachuteFolderPicker {

    private ParachuteFolderPicker() {
    }

    /**
     * 打开伞选择界面（游戏内滚动列表）。
     *
     * @param targetPos 非空 = 写入该方块实体；null = 写入手持伞包物品
     */
    public static void openPicker(@Nullable BlockPos targetPos) {
        Minecraft.getInstance().setScreen(new ParachuteFolderScreen(targetPos));
    }
}
