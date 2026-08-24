package com.create.parachute.client;

import com.create.parachute.ExampleMod;
import com.create.parachute.client.assets.ParachuteAssets;
import com.create.parachute.data.ParachuteManager;
import com.create.parachute.network.SyncParachuteSelectionPayload;
import com.create.parachute.parachute.ParachuteBlockEntity;
import com.create.parachute.parachute.ParachutePackItem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 伞选择界面：可滚动列表（滚轮 + 拖滚动条），任意数量的伞都能选。
 *
 * <p>显示 {@code parachute/} 文件夹里的所有伞；未选择时默认蘑菇伞；
 * 选中的伞名通过网络包写入方块实体 NBT 或手持伞包的物品 NBT。
 * 列表随文件夹热加载自动更新。</p>
 */
public class ParachuteFolderScreen extends Screen {

    private static final int PANEL_W = 240;
    private static final int PANEL_H = 210;
    private static final int ROW_H = 20;
    private static final int LIST_TOP = 26;
    private static final int LIST_BOTTOM_MARGIN = 34;
    private static final int[] PALETTE = {
            0xFFB3312C, 0xFFEB8844, 0xFFEDC53A, 0xFF87D360, 0xFF2639A5,
            0xFFC354CD, 0xFF4C7F99, 0xFF6E4C2C, 0xFF1E1B1B, 0xFFF0F0F0,
            0xFFF0A1A1, 0xFF5D5D5D, 0xFF969696, 0xFF7E3DC5, 0xFF6689D3,
            0xFF3B511A
    };

    /** 目标方块位置；null 表示选择写入手持伞包物品 */
    @Nullable
    private final BlockPos targetPos;
    private final List<String> ids = new ArrayList<>();
    private String selected = "";
    private int panelX;
    private int panelY;
    private int scrollOffset;
    private int listHeight;
    private boolean draggingScrollbar;
    private long lastListUpdate;

    public ParachuteFolderScreen(@Nullable BlockPos targetPos) {
        super(Component.translatable("screen.create_parachute.folder"));
        this.targetPos = targetPos;
    }

    @Override
    protected void init() {
        this.panelX = (this.width - PANEL_W) / 2;
        this.panelY = (this.height - PANEL_H) / 2;

        this.selected = initialSelection();
        this.reloadList();

        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.create_parachute.folder_confirm"),
                        b -> this.confirm())
                .bounds(this.panelX + 44, this.panelY + PANEL_H - 26, 72, 18)
                .build());
        // 打开文件夹键：用系统文件管理器打开 parachute 文件夹
        this.addRenderableWidget(Button.builder(
                        Component.translatable("screen.create_parachute.folder_button"),
                        b -> this.openFolder())
                .bounds(this.panelX + 124, this.panelY + PANEL_H - 26, 72, 18)
                .build());
    }

    private void reloadList() {
        ParachuteAssets.forceRefresh();
        this.ids.clear();
        this.ids.addAll(ParachuteAssets.listIds());
        if (!this.ids.contains(this.selected)) {
            this.selected = this.ids.contains(ParachuteManager.DEFAULT_PARACHUTE)
                    ? ParachuteManager.DEFAULT_PARACHUTE
                    : (this.ids.isEmpty() ? "" : this.ids.get(0));
        }
        this.listHeight = this.ids.size() * ROW_H;
        this.clampScroll();
    }

    private void clampScroll() {
        int viewH = PANEL_H - LIST_TOP - LIST_BOTTOM_MARGIN;
        int max = Math.max(0, this.listHeight - viewH);
        if (this.scrollOffset > max) this.scrollOffset = max;
        if (this.scrollOffset < 0) this.scrollOffset = 0;
    }

    private String initialSelection() {
        if (this.minecraft != null && this.minecraft.level != null && this.targetPos != null) {
            if (this.minecraft.level.getBlockEntity(this.targetPos) instanceof ParachuteBlockEntity be) {
                return be.getParachuteName();
            }
        }
        if (this.minecraft != null && this.minecraft.player != null) {
            ItemStack stack = this.minecraft.player.getMainHandItem();
            if (!(stack.getItem() instanceof ParachutePackItem)) {
                stack = this.minecraft.player.getOffhandItem();
            }
            String name = ParachutePackItem.getParachuteName(stack);
            if (!name.isEmpty()) return name;
        }
        return ParachuteManager.DEFAULT_PARACHUTE;
    }

    private void confirm() {
        if (!this.selected.isEmpty()) {
            PacketDistributor.sendToServer(new SyncParachuteSelectionPayload(this.targetPos, this.selected));
        }
        // 确认后回到控制器 GUI（保持选择流程不退出）
        if (this.minecraft != null) {
            this.minecraft.setScreen(new ParachuteScreen(this.targetPos));
        }
    }

    /** 打开文件夹：用系统文件管理器打开游戏目录下的 parachute 文件夹 */
    private void openFolder() {
        if (this.minecraft == null) return;
        try {
            File dir = new File(this.minecraft.gameDirectory, "parachute");
            if (!dir.isDirectory()) {
                dir.mkdirs();
            }
            String os = System.getProperty("os.name", "").toLowerCase();
            if (os.contains("win")) {
                new ProcessBuilder("explorer", dir.getAbsolutePath()).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("open", dir.getAbsolutePath()).start();
            } else {
                new ProcessBuilder("xdg-open", dir.getAbsolutePath()).start();
            }
        } catch (Exception e) {
            ExampleMod.LOGGER.warn("无法打开伞文件夹: {}", e.toString());
        }
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int viewH = PANEL_H - LIST_TOP - LIST_BOTTOM_MARGIN;
        int max = Math.max(0, this.listHeight - viewH);
        this.scrollOffset = (int) Math.max(0, Math.min(max, this.scrollOffset - deltaY * 3));
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int viewH = PANEL_H - LIST_TOP - LIST_BOTTOM_MARGIN;
        int listX = this.panelX + 8;
        int listW = PANEL_W - 26;
        int listY = this.panelY + LIST_TOP;
        // 滚动条拖拽
        if (this.listHeight > viewH) {
            int barX = this.panelX + PANEL_W - 12;
            if (mouseX >= barX && mouseX < barX + 4 && mouseY >= listY && mouseY < listY + viewH) {
                this.draggingScrollbar = true;
                this.updateScrollFromMouse(mouseY);
                return true;
            }
        }
        // 行点击
        for (int i = 0; i < this.ids.size(); i++) {
            int rowY = listY + i * ROW_H - this.scrollOffset;
            if (rowY + ROW_H < listY || rowY >= listY + viewH) continue;
            if (mouseX >= listX && mouseX < listX + listW && mouseY >= rowY && mouseY < rowY + ROW_H) {
                this.selected = this.ids.get(i);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingScrollbar) {
            this.updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.draggingScrollbar = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    private void updateScrollFromMouse(double mouseY) {
        int viewH = PANEL_H - LIST_TOP - LIST_BOTTOM_MARGIN;
        int max = Math.max(0, this.listHeight - viewH);
        float ratio = (float) ((mouseY - (this.panelY + LIST_TOP)) / viewH);
        this.scrollOffset = (int) Math.max(0, Math.min(max, ratio * max));
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 热加载：列表自动跟随 parachute/ 文件夹变化
        long now = System.currentTimeMillis();
        if (now - this.lastListUpdate > 500) {
            this.lastListUpdate = now;
            List<String> fresh = ParachuteAssets.listIds();
            if (!fresh.equals(this.ids)) {
                this.ids.clear();
                this.ids.addAll(fresh);
                if (!this.ids.contains(this.selected)) {
                    this.selected = this.ids.contains(ParachuteManager.DEFAULT_PARACHUTE)
                            ? ParachuteManager.DEFAULT_PARACHUTE
                            : (this.ids.isEmpty() ? "" : this.ids.get(0));
                }
                this.listHeight = this.ids.size() * ROW_H;
                this.clampScroll();
            }
        }

        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        int gx = this.panelX;
        int gy = this.panelY;

        // 面板：浅灰底 + 亮边框
        guiGraphics.fill(gx - 2, gy - 2, gx + PANEL_W + 2, gy + PANEL_H + 2, 0xFF9A9AB0);
        guiGraphics.fill(gx - 1, gy - 1, gx + PANEL_W + 1, gy + PANEL_H + 1, 0xFF6A6A80);
        guiGraphics.fill(gx, gy, gx + PANEL_W, gy + PANEL_H, 0xFF2A2A35);

        guiGraphics.drawCenteredString(this.font, this.title, gx + PANEL_W / 2, gy + 5, 0xFFFFFFFF);
        guiGraphics.drawCenteredString(this.font,
                Component.translatable("screen.create_parachute.folder_count", this.ids.size()),
                gx + PANEL_W / 2, gy + 15, 0xFFAAAAAA);

        // 列表可视区域
        int listX = gx + 8;
        int listW = PANEL_W - 26;
        int listY = gy + LIST_TOP;
        int viewH = PANEL_H - LIST_TOP - LIST_BOTTOM_MARGIN;
        guiGraphics.fill(listX - 2, listY - 2, listX + listW + 2, listY + viewH + 2, 0xFF101018);

        for (int i = 0; i < this.ids.size(); i++) {
            int rowY = listY + i * ROW_H - this.scrollOffset;
            if (rowY + ROW_H < listY || rowY >= listY + viewH) continue;
            String id = this.ids.get(i);
            boolean isSel = id.equals(this.selected);
            boolean hovered = mouseX >= listX && mouseX < listX + listW && mouseY >= rowY && mouseY < rowY + ROW_H;

            if (isSel) {
                guiGraphics.fill(listX, rowY, listX + listW, rowY + ROW_H, 0xFF3A5BB8);
            } else if (hovered) {
                guiGraphics.fill(listX, rowY, listX + listW, rowY + ROW_H, 0xFF4A4A5E);
            } else {
                guiGraphics.fill(listX, rowY, listX + listW, rowY + ROW_H, 0xFF343441);
            }
            guiGraphics.fill(listX, rowY + ROW_H - 1, listX + listW, rowY + ROW_H, 0xFF22222A);
            int color = PALETTE[Math.floorMod(i, PALETTE.length)];
            guiGraphics.fill(listX + 2, rowY + 3, listX + 8, rowY + 9, 0xFF000000);
            guiGraphics.fill(listX + 3, rowY + 4, listX + 7, rowY + 8, color);
            guiGraphics.drawString(this.font, id, listX + 12, rowY + 5, isSel ? 0xFFFFFFFF : 0xFFE8E8E8, true);
        }

        // 滚动条
        if (this.listHeight > viewH) {
            int barX = gx + PANEL_W - 12;
            int trackH = viewH;
            int thumbH = Math.max(16, trackH * viewH / this.listHeight);
            int thumbY = listY + (trackH - thumbH) * this.scrollOffset / Math.max(1, this.listHeight - viewH);
            guiGraphics.fill(barX, listY, barX + 4, listY + trackH, 0xFF101018);
            guiGraphics.fill(barX, thumbY, barX + 4, thumbY + thumbH, 0xFF8A8AA0);
        }

        guiGraphics.drawCenteredString(this.font,
                Component.translatable("screen.create_parachute.folder_hint", ParachuteManager.DEFAULT_PARACHUTE),
                gx + PANEL_W / 2, gy + PANEL_H - 42, 0xFFC0C0C0);

        // 手动渲染控件（不调用 super.render()，避免其重画背景盖住面板）
        for (Renderable renderable : this.renderables) {
            renderable.render(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
