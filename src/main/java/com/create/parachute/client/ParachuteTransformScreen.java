package com.create.parachute.client;

import com.create.parachute.ExampleMod;
import com.create.parachute.network.SyncParachuteLockPayload;
import com.create.parachute.network.SyncParachuteTransformPayload;
import com.create.parachute.parachute.ParachuteBlockEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 变换设置界面（parachute_controller_3.png）：同一界面三种模式，只换文本。
 * <ul>
 *   <li>旋转模式：X/Y/Z 旋转（度，自摆动坐标系）</li>
 *   <li>枢轴模式：X/Y/Z 枢轴偏移（模型相对枢轴的位置，格）</li>
 *   <li>偏移模式：X/Y/Z 整体偏移（含放置自带偏移，格）</li>
 * </ul>
 * 界面上的 4 个新按钮（锁定 + 三个模式切换）和文件夹键都可点击；
 * 底部对勾键保存当前 XYZ 值写回目标方块。
 */
public class ParachuteTransformScreen extends Screen {

    private static final int IMAGE_WIDTH = 228;
    private static final int IMAGE_HEIGHT = 128;
    private static final ResourceLocation BG_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "textures/gui/parachute_controller_3.png");
    private static final ResourceLocation BUTTON_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "textures/gui/parachute_controller_button.png");

    /** 三个输入框 */
    private static final int FIELD_X = 72;
    private static final int FIELD_Y0 = 28;
    private static final int FIELD_Y1 = 52;
    private static final int FIELD_Y2 = 76;
    private static final int FIELD_W = 88;
    private static final int FIELD_H = 14;

    /** 底部对勾（保存），状态图在 button 贴图 (154,0)/(154,18) */
    private static final int CONFIRM_X = 154;
    private static final int CONFIRM_Y = 104;
    private static final int CONFIRM_W = 18;
    private static final int CONFIRM_H = 18;

    /** 文件夹键，状态图在 button 贴图 (189,0)/(189,25) */
    private static final int FOLDER_X = 189;
    private static final int FOLDER_Y = 101;
    private static final int FOLDER_W = 24;
    private static final int FOLDER_H = 25;

    /** 锁定按钮（标题栏右侧），状态图 (241,1,15,15) */
    private static final int LOCK_X = 182;
    private static final int LOCK_Y = 4;
    private static final int LOCK_W = 15;
    private static final int LOCK_H = 15;
    private static final int LOCK_U = 241;
    private static final int LOCK_V = 1;
    private static final int LOCK_UW = 15;
    private static final int LOCK_VH = 15;

    /** 三个模式按钮，状态图 (1/19/37, 92)/(111) */
    private static final int MODE_X = 180;
    private static final int MODE_W = 18;
    private static final int MODE_H = 18;
    private static final int MODE_Y0 = 23;
    private static final int MODE_Y1 = 47;
    private static final int MODE_Y2 = 71;
    private static final int[] MODE_STATE_XS = {1, 19, 37};
    private static final int MODE_STATE_V = 92;
    private static final int MODE_STATE_V_PRESSED = 111;
    private static final int MODE_STATE_W = 17;
    private static final int MODE_STATE_H = 17;

    private static final int CONFIRM_BTN = 0;
    private static final int FOLDER_BTN = 1;
    private static final int LOCK_BTN = 2;
    private static final int MODE_BTN_0 = 3;   // 旋转
    private static final int MODE_BTN_1 = 4;   // 枢轴
    private static final int MODE_BTN_2 = 5;   // 偏移

    /** 当前模式：0=旋转 1=枢轴 2=整体偏移 */
    private final int mode;
    /** 目标方块位置；null 表示手持伞包（无方块可写） */
    @Nullable
    private final BlockPos targetPos;

    private EditBox xField;
    private EditBox yField;
    private EditBox zField;
    private float pendingX;
    private float pendingY;
    private float pendingZ;
    private boolean pendingLocked;
    private int pressedButton = -1;
    private int panelX;
    private int panelY;

    public ParachuteTransformScreen(@Nullable BlockPos targetPos, int mode) {
        super(Component.translatable(titleKey(mode)));
        this.targetPos = targetPos;
        this.mode = mode;
    }

    private static String titleKey(int mode) {
        return switch (mode) {
            case SyncParachuteTransformPayload.MODE_PIVOT -> "screen.create_parachute.set_pivot";
            case SyncParachuteTransformPayload.MODE_OFFSET -> "screen.create_parachute.set_offset";
            default -> "screen.create_parachute.set_rotation";
        };
    }

    @Override
    protected void init() {
        this.panelX = (this.width - IMAGE_WIDTH) / 2;
        this.panelY = (this.height - IMAGE_HEIGHT) / 2;
        int x = this.panelX;
        int y = this.panelY;

        this.loadFromTarget();

        this.xField = new EditBox(this.font, x + FIELD_X, y + FIELD_Y0, FIELD_W, FIELD_H, Component.empty());
        this.xField.setFilter(ParachuteTransformScreen::filterNumericSigned);
        this.xField.setResponder(this::onXChanged);
        this.xField.setBordered(false);
        this.addRenderableWidget(this.xField);

        this.yField = new EditBox(this.font, x + FIELD_X, y + FIELD_Y1, FIELD_W, FIELD_H, Component.empty());
        this.yField.setFilter(ParachuteTransformScreen::filterNumericSigned);
        this.yField.setResponder(this::onYChanged);
        this.yField.setBordered(false);
        this.addRenderableWidget(this.yField);

        this.zField = new EditBox(this.font, x + FIELD_X, y + FIELD_Y2, FIELD_W, FIELD_H, Component.empty());
        this.zField.setFilter(ParachuteTransformScreen::filterNumericSigned);
        this.zField.setResponder(this::onZChanged);
        this.zField.setBordered(false);
        this.addRenderableWidget(this.zField);

        this.updateFieldDisplay();
    }

    /** 从目标方块实体读取当前模式对应的 XYZ 值；物品（无方块）用 0 */
    private void loadFromTarget() {
        this.pendingX = 0.0F;
        this.pendingY = 0.0F;
        this.pendingZ = 0.0F;
        this.pendingLocked = false;
        if (this.minecraft != null && this.minecraft.level != null && this.targetPos != null
                && this.minecraft.level.getBlockEntity(this.targetPos) instanceof ParachuteBlockEntity be) {
            this.pendingLocked = be.isWobbleLocked();
            switch (this.mode) {
                case SyncParachuteTransformPayload.MODE_ROTATION -> {
                    this.pendingX = be.getRotX();
                    this.pendingY = be.getRotY();
                    this.pendingZ = be.getRotZ();
                }
                case SyncParachuteTransformPayload.MODE_PIVOT -> {
                    this.pendingX = be.getPivotX();
                    this.pendingY = be.getPivotY();
                    this.pendingZ = be.getPivotZ();
                }
                case SyncParachuteTransformPayload.MODE_OFFSET -> {
                    this.pendingX = be.getOffX();
                    this.pendingY = be.getOffY();
                    this.pendingZ = be.getOffZ();
                }
                default -> {
                }
            }
        }
    }

    private void onXChanged(String text) {
        if (!text.isEmpty()) {
            try {
                this.pendingX = Float.parseFloat(text);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void onYChanged(String text) {
        if (!text.isEmpty()) {
            try {
                this.pendingY = Float.parseFloat(text);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    private void onZChanged(String text) {
        if (!text.isEmpty()) {
            try {
                this.pendingZ = Float.parseFloat(text);
            } catch (NumberFormatException ignored) {
            }
        }
    }

    /** 对勾保存：把当前 XYZ 写回目标方块 */
    private void save() {
        float x = parseFloatSafe(this.xField.getValue(), this.pendingX);
        float y = parseFloatSafe(this.yField.getValue(), this.pendingY);
        float z = parseFloatSafe(this.zField.getValue(), this.pendingZ);
        this.pendingX = x;
        this.pendingY = y;
        this.pendingZ = z;
        if (this.targetPos != null && this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getBlockEntity(this.targetPos) instanceof ParachuteBlockEntity be) {
            switch (this.mode) {
                case SyncParachuteTransformPayload.MODE_ROTATION -> be.setRotation(x, y, z);
                case SyncParachuteTransformPayload.MODE_PIVOT -> be.setPivot(x, y, z);
                case SyncParachuteTransformPayload.MODE_OFFSET -> be.setOffset(x, y, z);
                default -> {
                }
            }
        }
        if (this.targetPos != null) {
            PacketDistributor.sendToServer(new SyncParachuteTransformPayload(this.targetPos, this.mode, x, y, z));
        }
        this.playClickSound();
    }

    private static float parseFloatSafe(String text, float fallback) {
        if (text == null || text.isEmpty()) return fallback;
        try {
            return Float.parseFloat(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 锁定键：切换锁定自摆动 */
    private void toggleLock() {
        this.pendingLocked = !this.pendingLocked;
        if (this.targetPos != null && this.minecraft != null && this.minecraft.level != null
                && this.minecraft.level.getBlockEntity(this.targetPos) instanceof ParachuteBlockEntity be) {
            be.setWobbleLocked(this.pendingLocked);
        }
        if (this.targetPos != null) {
            PacketDistributor.sendToServer(new SyncParachuteLockPayload(this.targetPos, this.pendingLocked));
        }
        this.playClickSound();
    }

    /** 模式按钮：切换到其他模式（同一 controller_3 界面） */
    private void switchMode(int newMode) {
        this.playClickSound();
        if (this.minecraft != null) {
            this.minecraft.setScreen(new ParachuteTransformScreen(this.targetPos, newMode));
        }
    }

    /** 文件夹键：进入选伞界面 */
    private void openSelectionScreen() {
        this.playClickSound();
        if (this.minecraft != null) {
            this.minecraft.setScreen(new ParachuteSelectionScreen(this.targetPos));
        }
    }

    private void updateFieldDisplay() {
        if (!this.xField.isFocused()) {
            String text = String.format("%.2f", this.pendingX);
            if (!this.xField.getValue().equals(text)) this.xField.setValue(text);
        }
        if (!this.yField.isFocused()) {
            String text = String.format("%.2f", this.pendingY);
            if (!this.yField.getValue().equals(text)) this.yField.setValue(text);
        }
        if (!this.zField.isFocused()) {
            String text = String.format("%.2f", this.pendingZ);
            if (!this.zField.getValue().equals(text)) this.zField.setValue(text);
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
        if (isInRect(mouseX, mouseY, x + LOCK_X, y + LOCK_Y, LOCK_W, LOCK_H)) {
            this.pressedButton = LOCK_BTN;
            return true;
        }
        int[] modeYs = {MODE_Y0, MODE_Y1, MODE_Y2};
        for (int i = 0; i < modeYs.length; i++) {
            if (isInRect(mouseX, mouseY, x + MODE_X, y + modeYs[i], MODE_W, MODE_H)) {
                this.pressedButton = MODE_BTN_0 + i;
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        int x = this.panelX;
        int y = this.panelY;

        if (this.pressedButton == CONFIRM_BTN && isInRect(mouseX, mouseY, x + CONFIRM_X, y + CONFIRM_Y, CONFIRM_W, CONFIRM_H)) {
            this.save();
        } else if (this.pressedButton == FOLDER_BTN && isInRect(mouseX, mouseY, x + FOLDER_X, y + FOLDER_Y, FOLDER_W, FOLDER_H)) {
            this.openSelectionScreen();
        } else if (this.pressedButton == LOCK_BTN && isInRect(mouseX, mouseY, x + LOCK_X, y + LOCK_Y, LOCK_W, LOCK_H)) {
            this.toggleLock();
        } else if (this.pressedButton == MODE_BTN_0 && isInRect(mouseX, mouseY, x + MODE_X, y + MODE_Y0, MODE_W, MODE_H)) {
            this.switchMode(SyncParachuteTransformPayload.MODE_ROTATION);
        } else if (this.pressedButton == MODE_BTN_1 && isInRect(mouseX, mouseY, x + MODE_X, y + MODE_Y1, MODE_W, MODE_H)) {
            this.switchMode(SyncParachuteTransformPayload.MODE_PIVOT);
        } else if (this.pressedButton == MODE_BTN_2 && isInRect(mouseX, mouseY, x + MODE_X, y + MODE_Y2, MODE_W, MODE_H)) {
            this.switchMode(SyncParachuteTransformPayload.MODE_OFFSET);
        }
        this.pressedButton = -1;
        return super.mouseReleased(mouseX, mouseY, button);
    }

    // ==================== 渲染 ====================

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        int gx = this.panelX;
        int gy = this.panelY;
        guiGraphics.blit(BG_TEXTURE, gx, gy, 0, 0, IMAGE_WIDTH, IMAGE_HEIGHT, IMAGE_WIDTH, IMAGE_HEIGHT);

        // 对勾（保存）
        boolean confirmHover = isInRect(mouseX, mouseY, gx + CONFIRM_X, gy + CONFIRM_Y, CONFIRM_W, CONFIRM_H);
        if (this.pressedButton == CONFIRM_BTN) {
            guiGraphics.blit(BUTTON_TEXTURE, gx + CONFIRM_X, gy + CONFIRM_Y, CONFIRM_X, 18, CONFIRM_W, CONFIRM_H, 256, 128);
        } else if (confirmHover) {
            guiGraphics.blit(BUTTON_TEXTURE, gx + CONFIRM_X, gy + CONFIRM_Y, CONFIRM_X, 0, CONFIRM_W, CONFIRM_H, 256, 128);
        }

        // 文件夹键
        boolean folderHover = isInRect(mouseX, mouseY, gx + FOLDER_X, gy + FOLDER_Y, FOLDER_W, FOLDER_H);
        if (this.pressedButton == FOLDER_BTN) {
            guiGraphics.blit(BUTTON_TEXTURE, gx + FOLDER_X, gy + FOLDER_Y, FOLDER_X, 25, FOLDER_W, FOLDER_H, 256, 128);
        } else if (folderHover) {
            guiGraphics.blit(BUTTON_TEXTURE, gx + FOLDER_X, gy + FOLDER_Y, FOLDER_X, 0, FOLDER_W, FOLDER_H, 256, 128);
        }

        // 三个模式按钮（状态图右移 1px 对齐）
        int[] modeYs = {MODE_Y0, MODE_Y1, MODE_Y2};
        for (int i = 0; i < modeYs.length; i++) {
            int by = gy + modeYs[i];
            boolean hovered = isInRect(mouseX, mouseY, gx + MODE_X, by, MODE_W, MODE_H);
            boolean pressed = this.pressedButton == MODE_BTN_0 + i;
            if (pressed) {
                guiGraphics.blit(BUTTON_TEXTURE, gx + MODE_X + 1, by, MODE_STATE_XS[i], MODE_STATE_V_PRESSED, MODE_STATE_W, MODE_STATE_H, 256, 128);
            } else if (hovered) {
                guiGraphics.blit(BUTTON_TEXTURE, gx + MODE_X + 1, by, MODE_STATE_XS[i], MODE_STATE_V, MODE_STATE_W, MODE_STATE_H, 256, 128);
            }
        }

        // 锁定按钮：锁定状态决定贴图——锁定显示 button 贴图里的锁定态图标 (241,1,15,15)，未锁定显示背景原图标
        if (this.pendingLocked) {
            guiGraphics.blit(BUTTON_TEXTURE, gx + LOCK_X, gy + LOCK_Y, LOCK_U, LOCK_V, LOCK_UW, LOCK_VH, 256, 128);
        }

        // 标题 + 字段标签（按模式换文本）
        guiGraphics.drawString(this.font, this.title, gx + 21, gy + 3, 0xFFE2E2E2, false);
        guiGraphics.drawString(this.font, Component.translatable(labelKey(this.mode, 0)), gx + 24, gy + 27, 0xFFE2E2E2, false);
        guiGraphics.drawString(this.font, Component.translatable(labelKey(this.mode, 1)), gx + 24, gy + 51, 0xFFE2E2E2, false);
        guiGraphics.drawString(this.font, Component.translatable(labelKey(this.mode, 2)), gx + 24, gy + 75, 0xFFE2E2E2, false);

        // 手动渲染控件（EditBox）
        for (Renderable renderable : this.renderables) {
            renderable.render(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    private static String labelKey(int mode, int axis) {
        String prefix = switch (mode) {
            case SyncParachuteTransformPayload.MODE_PIVOT -> "pivot";
            case SyncParachuteTransformPayload.MODE_OFFSET -> "off";
            default -> "rot";
        };
        return "screen.create_parachute." + prefix + "_" + "xyz".charAt(axis);
    }

    @Override
    public void tick() {
        this.updateFieldDisplay();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 工具 ====================

    private static boolean isInRect(double mx, double my, int bx, int by, int bw, int bh) {
        return mx >= bx && mx < bx + bw && my >= by && my < by + bh;
    }

    /** 允许负数的小数过滤器：数字、一个小数点、开头一个负号 */
    private static boolean filterNumericSigned(String text) {
        if (text.isEmpty()) return true;
        int dots = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '-') {
                if (i != 0) return false;
            } else if (c == '.') {
                if (++dots > 1) return false;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private void playClickSound() {
        if (this.minecraft != null && this.minecraft.getSoundManager() != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }
}
