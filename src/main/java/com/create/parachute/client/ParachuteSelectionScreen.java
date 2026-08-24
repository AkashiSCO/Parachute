package com.create.parachute.client;

import com.create.parachute.ExampleMod;
import com.create.parachute.client.assets.ParachuteAssets;
import com.create.parachute.data.ParachuteManager;
import com.create.parachute.network.SyncParachuteSelectionPayload;
import com.create.parachute.parachute.ParachuteBlockEntity;
import com.create.parachute.parachute.ParachutePackItem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 选伞界面（parachute_controller_2.png），由控制器主 GUI 的蓝色文件夹键打开。
 *
 * <p><b>每把伞一个显示框</b>（灰框图形，button 贴图 (18,46,143,17)），N 把伞 N 个框，
 * 纵向堆叠、在蓝框范围（y=20..92）内上下滚动；左右卡在红框两端（x=18..159）。
 * 点击某个显示框选中该伞，确定键（对勾）把选中伞写入目标并停留在本界面；
 * 滑块（button 贴图 (0,0,5,20)）沿黄框轨道（x=171..175,y=18..94）控制滚动；
 * 文件夹键用系统文件管理器打开 parachute 文件夹。</p>
 */
public class ParachuteSelectionScreen extends Screen {

    private static final int IMAGE_WIDTH = 228;
    private static final int IMAGE_HEIGHT = 128;
    private static final ResourceLocation BG_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "textures/gui/parachute_controller_2.png");
    private static final ResourceLocation BUTTON_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "textures/gui/parachute_controller_button.png");

    /** 显示框图形在 button 贴图内的区域（用户标记：红框 (18,46)-(161,63)） */
    private static final int BOX_U = 18;
    private static final int BOX_V = 46;
    private static final int BOX_UW = 143;
    private static final int BOX_VH = 17;
    /** 选中态白色高亮显示框在 button 贴图内的区域（用户标记：黄框 (18,64)-(161,81)） */
    private static final int BOX_HL_U = 18;
    private static final int BOX_HL_V = 64;

    /** 列表区域：左右卡红框两端，上下在蓝框范围内滚动 */
    private static final int LIST_X = 18;
    private static final int LIST_W = 143;
    private static final int LIST_TOP = 20;
    private static final int LIST_BOTTOM = 92;
    private static final int VIEW_H = LIST_BOTTOM - LIST_TOP;
    /** 每行（每把伞一个显示框）的高度 */
    private static final int ROW_H = 17;

    /** 滑块轨道：黄框 x=171..175, y=18..94；滑块图形在 button 贴图 (0,0,5,20) */
    private static final int TRACK_X = 171;
    private static final int TRACK_Y = 18;
    private static final int TRACK_H = 76;
    private static final int SLIDER_U = 0;
    private static final int SLIDER_V = 0;
    private static final int SLIDER_W = 5;
    private static final int SLIDER_H = 20;

    /** 确定键（对勾图标），悬停/按下状态图在 button 贴图 (154,0)/(154,18) */
    private static final int CONFIRM_X = 154;
    private static final int CONFIRM_Y = 104;
    private static final int CONFIRM_W = 18;
    private static final int CONFIRM_H = 18;

    /** 打开文件夹键（文件夹图标），状态图在 button 贴图 (189,0)/(189,25) */
    private static final int FOLDER_X = 189;
    private static final int FOLDER_Y = 101;
    private static final int FOLDER_W = 24;
    private static final int FOLDER_H = 25;

    private static final int CONFIRM_BTN = 0;
    private static final int FOLDER_BTN = 1;

    /** 目标方块位置；null 表示写入手持伞包物品 */
    @Nullable
    private final BlockPos targetPos;

    private final List<String> ids = new ArrayList<>();
    private String selected = "";
    /** 列表滚动偏移（像素），0..maxScroll */
    private int scrollOffset;
    private boolean draggingSlider;
    private int pressedButton = -1;
    private long lastListUpdate;
    private int panelX;
    private int panelY;

    public ParachuteSelectionScreen(@Nullable BlockPos targetPos) {
        super(Component.translatable("screen.create_parachute.folder"));
        this.targetPos = targetPos;
    }

    @Override
    protected void init() {
        this.panelX = (this.width - IMAGE_WIDTH) / 2;
        this.panelY = (this.height - IMAGE_HEIGHT) / 2;
        this.reloadList();
        this.selected = this.initialSelection();
        this.alignScrollToSelection();
    }

    private String initialSelection() {
        if (this.minecraft != null && this.minecraft.level != null && this.targetPos != null) {
            if (this.minecraft.level.getBlockEntity(this.targetPos) instanceof ParachuteBlockEntity be) {
                String name = be.getParachuteName();
                if (this.ids.contains(name)) return name;
            }
        }
        if (this.minecraft != null && this.minecraft.player != null) {
            ItemStack stack = this.minecraft.player.getMainHandItem();
            if (!(stack.getItem() instanceof ParachutePackItem)) {
                stack = this.minecraft.player.getOffhandItem();
            }
            String name = ParachutePackItem.getParachuteName(stack);
            if (this.ids.contains(name)) return name;
        }
        return this.ids.contains(ParachuteManager.DEFAULT_PARACHUTE)
                ? ParachuteManager.DEFAULT_PARACHUTE
                : (this.ids.isEmpty() ? "" : this.ids.get(0));
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
        this.alignScrollToSelection();
    }

    /** 列表随 parachute/ 文件夹热加载自动刷新 */
    private void refreshListIfChanged() {
        long now = System.currentTimeMillis();
        if (now - this.lastListUpdate <= 500) return;
        this.lastListUpdate = now;
        List<String> fresh = ParachuteAssets.listIds();
        if (fresh.equals(this.ids)) return;
        this.ids.clear();
        this.ids.addAll(fresh);
        if (!this.ids.contains(this.selected)) {
            this.selected = this.ids.contains(ParachuteManager.DEFAULT_PARACHUTE)
                    ? ParachuteManager.DEFAULT_PARACHUTE
                    : (this.ids.isEmpty() ? "" : this.ids.get(0));
        }
        this.alignScrollToSelection();
    }

    private int maxScroll() {
        return Math.max(0, this.ids.size() * ROW_H - VIEW_H);
    }

    /** 滚动到让选中行居中显示 */
    private void alignScrollToSelection() {
        int idx = this.ids.indexOf(this.selected);
        if (idx < 0) idx = 0;
        int target = idx * ROW_H + ROW_H / 2 - VIEW_H / 2;
        this.scrollOffset = Math.max(0, Math.min(maxScroll(), target));
    }

    /** 滑块位置 0..1 */
    private double sliderT() {
        int max = maxScroll();
        return max <= 0 ? 0.0D : (double) this.scrollOffset / (double) max;
    }

    /** 确定：把选中的伞写入目标，界面保持打开 */
    private void confirm() {
        if (this.selected.isEmpty()) return;
        PacketDistributor.sendToServer(new SyncParachuteSelectionPayload(this.targetPos, this.selected));
        this.playClickSound();
    }

    /** 文件夹键：用系统文件管理器打开游戏目录下的 parachute 文件夹 */
    private void openFolder() {
        this.playClickSound();
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

    // ==================== 输入 ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = this.panelX;
        int y = this.panelY;

        if (isInRect(mouseX, mouseY, x + CONFIRM_X, y + CONFIRM_Y, CONFIRM_W, CONFIRM_H)) {
            this.pressedButton = CONFIRM_BTN;
            return true;
        }
        if (isInRect(mouseX, mouseY, x + FOLDER_X, y + FOLDER_Y, FOLDER_W, FOLDER_H)) {
            this.pressedButton = FOLDER_BTN;
            return true;
        }
        // 点击显示框选中对应伞
        int listTop = y + LIST_TOP;
        for (int i = 0; i < this.ids.size(); i++) {
            int rowY = listTop + i * ROW_H - this.scrollOffset;
            if (rowY + ROW_H < listTop || rowY >= listTop + VIEW_H) continue;
            if (mouseX >= x + LIST_X && mouseX < x + LIST_X + LIST_W && mouseY >= rowY && mouseY < rowY + ROW_H) {
                this.selected = this.ids.get(i);
                this.playClickSound();
                return true;
            }
        }
        // 滑块拖拽（命中区略微放宽便于抓取）
        if (mouseX >= x + TRACK_X - 3 && mouseX < x + TRACK_X + SLIDER_W + 3
                && mouseY >= y + TRACK_Y && mouseY < y + TRACK_Y + TRACK_H) {
            this.draggingSlider = true;
            this.updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.draggingSlider) {
            this.updateScrollFromMouse(mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        int x = this.panelX;
        int y = this.panelY;

        if (this.pressedButton == CONFIRM_BTN && isInRect(mouseX, mouseY, x + CONFIRM_X, y + CONFIRM_Y, CONFIRM_W, CONFIRM_H)) {
            this.confirm();
        } else if (this.pressedButton == FOLDER_BTN && isInRect(mouseX, mouseY, x + FOLDER_X, y + FOLDER_Y, FOLDER_W, FOLDER_H)) {
            this.openFolder();
        }
        this.pressedButton = -1;
        this.draggingSlider = false;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        int x = this.panelX;
        int y = this.panelY;
        // 滚轮：鼠标在面板区域内即可滚动列表（每格滚动一行）
        boolean overPanel = mouseX >= x + 1 && mouseX < x + 177
                && mouseY >= y + 16 && mouseY < y + 97;
        if (overPanel) {
            this.scrollOffset = Math.max(0, Math.min(maxScroll(),
                    this.scrollOffset - (int) (deltaY * ROW_H)));
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    private void updateScrollFromMouse(double mouseY) {
        int y = this.panelY;
        double travel = TRACK_H - SLIDER_H;
        double t = clamp((mouseY - (y + TRACK_Y + SLIDER_H / 2.0D)) / travel);
        this.scrollOffset = (int) Math.round(t * maxScroll());
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        int gx = this.panelX;
        int gy = this.panelY;

        // 图层1（最底）：背景中部（蓝框上界20 ~ 下界92），列表的底板
        guiGraphics.blit(BG_TEXTURE, gx, gy + LIST_TOP, 0, LIST_TOP, IMAGE_WIDTH, VIEW_H, IMAGE_WIDTH, IMAGE_HEIGHT);

        // 图层2：每把伞一个显示框 + 文本。
        // scissor 第三四参是 right/bottom 边缘坐标（不是宽高！），
        // 把列表严格裁在蓝框范围（x 18..161, y 20..92）内，显示框绝不会出边界。
        guiGraphics.enableScissor(gx + LIST_X, gy + LIST_TOP, gx + LIST_X + LIST_W, gy + LIST_TOP + VIEW_H);
        for (int i = 0; i < this.ids.size(); i++) {
            int rowY = gy + LIST_TOP + i * ROW_H - this.scrollOffset;
            if (rowY + ROW_H < gy + LIST_TOP || rowY >= gy + LIST_BOTTOM) continue;

            boolean isSel = this.ids.get(i).equals(this.selected);
            // 选中行用 button 贴图里的白色高亮显示框，否则用普通显示框
            int boxV = isSel ? BOX_HL_V : BOX_V;
            guiGraphics.blit(BUTTON_TEXTURE, gx + LIST_X, rowY, BOX_U, boxV, BOX_UW, BOX_VH, 256, 128);

            String name = this.ids.get(i);
            int nameMaxW = LIST_W - 10;
            if (this.font.width(name) > nameMaxW) {
                name = this.font.plainSubstrByWidth(name, nameMaxW) + "...";
            }
            guiGraphics.drawCenteredString(this.font, name,
                    gx + LIST_X + LIST_W / 2, rowY + (ROW_H - 9) / 2, isSel ? 0xFFFFFFFF : 0xFFE8E8E8);
        }
        // 文字是缓冲渲染：必须先冲刷（此时 scissor 仍生效），再关 scissor，否则文字会漏到边界外
        guiGraphics.flush();
        guiGraphics.disableScissor();

        // 图层3（最上）：背景上条(0..20) + 下条(92..128)，盖住滚动越界的显示框；其余控件同层
        guiGraphics.blit(BG_TEXTURE, gx, gy, 0, 0, IMAGE_WIDTH, LIST_TOP, IMAGE_WIDTH, IMAGE_HEIGHT);
        guiGraphics.blit(BG_TEXTURE, gx, gy + LIST_BOTTOM, 0, LIST_BOTTOM, IMAGE_WIDTH, IMAGE_HEIGHT - LIST_BOTTOM, IMAGE_WIDTH, IMAGE_HEIGHT);

        // 滑块
        int knobY = TRACK_Y + (int) Math.round(this.sliderT() * (TRACK_H - SLIDER_H));
        guiGraphics.blit(BUTTON_TEXTURE, gx + TRACK_X, gy + knobY, SLIDER_U, SLIDER_V, SLIDER_W, SLIDER_H, 256, 128);

        // 确定键（对勾）：悬停/按下用 button 贴图内的状态图
        boolean confirmHover = isInRect(mouseX, mouseY, gx + CONFIRM_X, gy + CONFIRM_Y, CONFIRM_W, CONFIRM_H);
        if (this.pressedButton == CONFIRM_BTN) {
            guiGraphics.blit(BUTTON_TEXTURE, gx + CONFIRM_X, gy + CONFIRM_Y, CONFIRM_X, 18, CONFIRM_W, CONFIRM_H, 256, 128);
        } else if (confirmHover) {
            guiGraphics.blit(BUTTON_TEXTURE, gx + CONFIRM_X, gy + CONFIRM_Y, CONFIRM_X, 0, CONFIRM_W, CONFIRM_H, 256, 128);
        }

        // 打开文件夹键：悬停/按下用 button 贴图内的状态图
        boolean folderHover = isInRect(mouseX, mouseY, gx + FOLDER_X, gy + FOLDER_Y, FOLDER_W, FOLDER_H);
        if (this.pressedButton == FOLDER_BTN) {
            guiGraphics.blit(BUTTON_TEXTURE, gx + FOLDER_X, gy + FOLDER_Y, FOLDER_X, 25, FOLDER_W, FOLDER_H, 256, 128);
        } else if (folderHover) {
            guiGraphics.blit(BUTTON_TEXTURE, gx + FOLDER_X, gy + FOLDER_Y, FOLDER_X, 0, FOLDER_W, FOLDER_H, 256, 128);
        }

        // 标题
        guiGraphics.drawString(this.font, this.title, gx + 21, gy + 3, 0xFFE2E2E2, false);

        for (Renderable renderable : this.renderables) {
            renderable.render(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public void tick() {
        this.refreshListIfChanged();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 工具 ====================

    private static boolean isInRect(double mx, double my, int bx, int by, int bw, int bh) {
        return mx >= bx && mx < bx + bw && my >= by && my < by + bh;
    }

    private static double clamp(double v) {
        return v < 0.0D ? 0.0D : (v > 1.0D ? 1.0D : v);
    }

    private void playClickSound() {
        if (this.minecraft != null && this.minecraft.getSoundManager() != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }
}
