package com.create.parachute.client;

import com.create.parachute.ExampleMod;
import com.create.parachute.network.SyncParachuteConfigPayload;
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
 * 控制器主 GUI（parachute_controller_new.png）：参数调节界面。
 *
 * <p>方块右键和伞包右键都会先打开本界面（{@code targetPos} 非空 = 方块，null = 手持伞包）。
 * 可调阻力系数 / 旋转阻尼 / 切伞速度，红石断开和低速断开开关，保存键写回方块（物品无方块可写）。
 * 蓝色"打开文件夹"键进入选伞界面 {@link ParachuteSelectionScreen}。</p>
 */
public class ParachuteScreen extends Screen {

    private static final int IMAGE_WIDTH = 228;
    private static final int IMAGE_HEIGHT = 128;
    private static final ResourceLocation BG_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "textures/gui/parachute_controller_new.png");
    private static final ResourceLocation BUTTON_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(ExampleMod.MOD_ID, "textures/gui/parachute_controller_button.png");

    private static final int REDSTONE_BTN = 0;
    private static final int LOW_SPEED_BTN = 1;
    private static final int SAVE_BTN = 2;
    private static final int FOLDER_BTN = 3;
    private static final int LOCK_BTN = 4;
    private static final int ROT_BTN = 5;
    private static final int PIVOT_BTN = 6;
    private static final int OFFSET_BTN = 7;

    /** 底部三个按钮（红石断开 / 低速断开 / 保存），悬停按下状态图在 button 贴图 (x, 0/18) */
    private static final int[][] BUTTONS = {
            {21, 104, 50, 18},
            {85, 104, 50, 18},
            {154, 104, 18, 18}
    };

    /** 蓝色"打开文件夹"键（进入选伞界面），状态图在 button 贴图 (189,0)/(189,25) */
    private static final int[] FOLDER_BUTTON = {189, 101, 24, 25};

    /** 锁定按钮（标题栏右侧），点击切换锁定自摆动；状态图在 button 贴图 (241,1,15,15) */
    private static final int LOCK_X = 182;
    private static final int LOCK_Y = 4;
    private static final int LOCK_W = 15;
    private static final int LOCK_H = 15;
    private static final int LOCK_U = 241;
    private static final int LOCK_V = 1;
    private static final int LOCK_UW = 15;
    private static final int LOCK_VH = 15;

    /** 三个变换模式按钮（三个字段行右侧）：旋转 / 枢轴点 / 整体偏移，进入 controller_3 界面 */
    private static final int MODE_X = 180;
    private static final int MODE_W = 18;
    private static final int MODE_H = 18;
    private static final int ROT_Y = 23;
    private static final int PIVOT_Y = 47;
    private static final int OFFSET_Y = 71;
    /** 模式按钮状态图在 button 贴图底部：旋转(1,92) 枢轴(19,92) 偏移(37,92)，按下态 v=111 */
    private static final int[] MODE_STATE_XS = {1, 19, 37};
    private static final int MODE_STATE_V = 92;
    private static final int MODE_STATE_V_PRESSED = 111;
    private static final int MODE_STATE_W = 17;
    private static final int MODE_STATE_H = 17;

    /** 目标方块位置；null 表示手持伞包（物品无方块参数可写） */
    @Nullable
    private final BlockPos targetPos;

    private EditBox kField;
    private EditBox rField;
    private EditBox vField;
    private double pendingK;
    private double pendingR;
    private double pendingV;
    private boolean pendingLowSpeed = true;
    private boolean pendingRedstone = true;
    private boolean pendingLocked;
    private int pressedButton = -1;
    private int panelX;
    private int panelY;

    public ParachuteScreen(@Nullable BlockPos targetPos) {
        super(Component.translatable("screen.create_parachute.parachute"));
        this.targetPos = targetPos;
    }

    @Override
    protected void init() {
        this.panelX = (this.width - IMAGE_WIDTH) / 2;
        this.panelY = (this.height - IMAGE_HEIGHT) / 2;
        int x = this.panelX;
        int y = this.panelY;

        loadConfigFromTarget();

        this.kField = new EditBox(this.font, x + 72, y + 28, 88, 14, Component.empty());
        this.kField.setFilter(ParachuteScreen::filterNumeric);
        this.kField.setResponder(this::onKTextChanged);
        this.kField.setBordered(false);
        this.addRenderableWidget(this.kField);

        this.rField = new EditBox(this.font, x + 72, y + 52, 88, 14, Component.empty());
        this.rField.setFilter(ParachuteScreen::filterNumeric);
        this.rField.setResponder(this::onRTextChanged);
        this.rField.setBordered(false);
        this.addRenderableWidget(this.rField);

        this.vField = new EditBox(this.font, x + 72, y + 76, 88, 14, Component.empty());
        this.vField.setFilter(ParachuteScreen::filterNumeric);
        this.vField.setResponder(this::onVTextChanged);
        this.vField.setBordered(false);
        this.addRenderableWidget(this.vField);

        this.updateFieldDisplay();
    }

    /** 从目标方块实体读取参数；物品（无方块）用默认值 */
    private void loadConfigFromTarget() {
        this.pendingK = 0.0D;
        this.pendingR = 0.0D;
        this.pendingV = 0.0D;
        this.pendingLowSpeed = false;
        this.pendingRedstone = true;
        this.pendingLocked = false;
        if (this.minecraft != null && this.minecraft.level != null && this.targetPos != null) {
            if (this.minecraft.level.getBlockEntity(this.targetPos) instanceof ParachuteBlockEntity be) {
                this.pendingK = be.getDragCoefficient();
                this.pendingR = be.getRotationalDragCoefficient();
                this.pendingV = be.getDisconnectSpeedThreshold();
                this.pendingLowSpeed = be.isDisconnectOnLowSpeed();
                this.pendingRedstone = be.isDisconnectOnRedstonePulse();
                this.pendingLocked = be.isWobbleLocked();
            }
        }
    }

    private void onKTextChanged(String text) {
        if (text.isEmpty()) return;
        try {
            this.pendingK = Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
        }
    }

    private void onRTextChanged(String text) {
        if (text.isEmpty()) return;
        try {
            this.pendingR = Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
        }
    }

    private void onVTextChanged(String text) {
        if (text.isEmpty()) return;
        try {
            this.pendingV = Double.parseDouble(text);
        } catch (NumberFormatException ignored) {
        }
    }

    private void saveConfig() {
        double k = parseDoubleSafe(this.kField.getValue());
        double r = parseDoubleSafe(this.rField.getValue());
        double v = parseDoubleSafe(this.vField.getValue());
        if (!Double.isNaN(k)) this.pendingK = k;
        if (!Double.isNaN(r)) this.pendingR = r;
        if (!Double.isNaN(v)) this.pendingV = v;
        this.sendConfig();
        this.playClickSound();
    }

    /** 参数/开关写回目标方块（物品无方块可写，静默跳过） */
    private void sendConfig() {
        if (this.targetPos == null) return;
        PacketDistributor.sendToServer(new SyncParachuteConfigPayload(
                this.targetPos,
                (int) Math.round(this.pendingK * 100.0D),
                (int) Math.round(this.pendingR * 100.0D),
                (int) Math.round(this.pendingV * 100.0D),
                this.pendingLowSpeed,
                this.pendingRedstone
        ));
    }

    /** 蓝色文件夹键：进入选伞界面（同一目标） */
    private void openSelectionScreen() {
        this.playClickSound();
        if (this.minecraft != null) {
            this.minecraft.setScreen(new ParachuteSelectionScreen(this.targetPos));
        }
    }

    /** 锁定键：切换锁定自摆动（整个伞固定，不跟随速度方向、不摆动） */
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

    /** 变换模式键：进入 controller_3 界面设置旋转 / 枢轴 / 偏移 */
    private void openTransformScreen(int mode) {
        this.playClickSound();
        if (this.minecraft != null) {
            this.minecraft.setScreen(new ParachuteTransformScreen(this.targetPos, mode));
        }
    }

    private static double parseDoubleSafe(String text) {
        if (text == null || text.isEmpty()) return Double.NaN;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private void updateFieldDisplay() {
        if (!this.kField.isFocused()) {
            String text = formatCoeff(this.pendingK);
            if (!this.kField.getValue().equals(text)) {
                this.kField.setValue(text);
            }
        }
        if (!this.rField.isFocused()) {
            String text = formatCoeff(this.pendingR);
            if (!this.rField.getValue().equals(text)) {
                this.rField.setValue(text);
            }
        }
        if (!this.vField.isFocused()) {
            String text = formatCoeff(this.pendingV);
            if (!this.vField.getValue().equals(text)) {
                this.vField.setValue(text);
            }
        }
    }

    @Override
    public void tick() {
        this.updateFieldDisplay();
    }

    // ==================== 输入 ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        int x = this.panelX;
        int y = this.panelY;

        for (int i = 0; i < BUTTONS.length; i++) {
            int[] b = BUTTONS[i];
            if (isInRect(mouseX, mouseY, x + b[0], y + b[1], b[2], b[3])) {
                this.pressedButton = i;
                return true;
            }
        }
        int[] fb = FOLDER_BUTTON;
        if (isInRect(mouseX, mouseY, x + fb[0], y + fb[1], fb[2], fb[3])) {
            this.pressedButton = FOLDER_BTN;
            return true;
        }
        // 锁定按钮（标题栏右侧）
        if (isInRect(mouseX, mouseY, x + LOCK_X, y + LOCK_Y, LOCK_W, LOCK_H)) {
            this.pressedButton = LOCK_BTN;
            return true;
        }
        // 三个变换模式按钮（三个字段行右侧）
        if (isInRect(mouseX, mouseY, x + MODE_X, y + ROT_Y, MODE_W, MODE_H)) {
            this.pressedButton = ROT_BTN;
            return true;
        }
        if (isInRect(mouseX, mouseY, x + MODE_X, y + PIVOT_Y, MODE_W, MODE_H)) {
            this.pressedButton = PIVOT_BTN;
            return true;
        }
        if (isInRect(mouseX, mouseY, x + MODE_X, y + OFFSET_Y, MODE_W, MODE_H)) {
            this.pressedButton = OFFSET_BTN;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        int x = this.panelX;
        int y = this.panelY;

        if (this.pressedButton == REDSTONE_BTN && isInRect(mouseX, mouseY, x + 21, y + 104, 50, 18)) {
            this.pendingRedstone = !this.pendingRedstone;
            this.sendConfig();
            this.playClickSound();
        } else if (this.pressedButton == LOW_SPEED_BTN && isInRect(mouseX, mouseY, x + 85, y + 104, 50, 18)) {
            this.pendingLowSpeed = !this.pendingLowSpeed;
            this.sendConfig();
            this.playClickSound();
        } else if (this.pressedButton == SAVE_BTN && isInRect(mouseX, mouseY, x + 154, y + 104, 18, 18)) {
            this.saveConfig();
        } else if (this.pressedButton == FOLDER_BTN) {
            int[] fb = FOLDER_BUTTON;
            if (isInRect(mouseX, mouseY, x + fb[0], y + fb[1], fb[2], fb[3])) {
                this.openSelectionScreen();
            }
        } else if (this.pressedButton == LOCK_BTN && isInRect(mouseX, mouseY, x + LOCK_X, y + LOCK_Y, LOCK_W, LOCK_H)) {
            this.toggleLock();
        } else if (this.pressedButton == ROT_BTN && isInRect(mouseX, mouseY, x + MODE_X, y + ROT_Y, MODE_W, MODE_H)) {
            this.openTransformScreen(SyncParachuteTransformPayload.MODE_ROTATION);
        } else if (this.pressedButton == PIVOT_BTN && isInRect(mouseX, mouseY, x + MODE_X, y + PIVOT_Y, MODE_W, MODE_H)) {
            this.openTransformScreen(SyncParachuteTransformPayload.MODE_PIVOT);
        } else if (this.pressedButton == OFFSET_BTN && isInRect(mouseX, mouseY, x + MODE_X, y + OFFSET_Y, MODE_W, MODE_H)) {
            this.openTransformScreen(SyncParachuteTransformPayload.MODE_OFFSET);
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

        // 底部三按钮：悬停/按下用 button 贴图状态图
        for (int i = 0; i < BUTTONS.length; i++) {
            int[] b = BUTTONS[i];
            int bx = gx + b[0];
            int by = gy + b[1];
            boolean hovered = isInRect(mouseX, mouseY, bx, by, b[2], b[3]);
            boolean pressed = this.pressedButton == i;
            if (pressed) {
                guiGraphics.blit(BUTTON_TEXTURE, bx, by, b[0], 18, b[2], b[3], 256, 128);
            } else if (hovered) {
                guiGraphics.blit(BUTTON_TEXTURE, bx, by, b[0], 0, b[2], b[3], 256, 128);
            }
        }

        // 蓝色文件夹键：悬停/按下用 button 贴图状态图
        boolean folderHover = isInRect(mouseX, mouseY, gx + FOLDER_BUTTON[0], gy + FOLDER_BUTTON[1], FOLDER_BUTTON[2], FOLDER_BUTTON[3]);
        if (this.pressedButton == FOLDER_BTN) {
            guiGraphics.blit(BUTTON_TEXTURE, gx + FOLDER_BUTTON[0], gy + FOLDER_BUTTON[1], FOLDER_BUTTON[0], 25, FOLDER_BUTTON[2], FOLDER_BUTTON[3], 256, 128);
        } else if (folderHover) {
            guiGraphics.blit(BUTTON_TEXTURE, gx + FOLDER_BUTTON[0], gy + FOLDER_BUTTON[1], FOLDER_BUTTON[0], 0, FOLDER_BUTTON[2], FOLDER_BUTTON[3], 256, 128);
        }

        // 三个变换模式按钮：悬停/按下用 button 贴图底部状态图（旋转/枢轴/偏移），状态图右移 1px 对齐
        int[] modeYs = {ROT_Y, PIVOT_Y, OFFSET_Y};
        for (int i = 0; i < modeYs.length; i++) {
            int by = gy + modeYs[i];
            boolean hovered = isInRect(mouseX, mouseY, gx + MODE_X, by, MODE_W, MODE_H);
            boolean pressed = this.pressedButton == (ROT_BTN + i);
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

        // 标题 + 字段标签 + 开关文字（注意颜色必须带 FF alpha，否则文字透明）
        guiGraphics.drawString(this.font, this.title, gx + 21, gy + 3, 0xFFE2E2E2, false);
        guiGraphics.drawString(this.font, Component.translatable("screen.create_parachute.drag"), gx + 24, gy + 27, 0xFFE2E2E2, false);
        guiGraphics.drawString(this.font, Component.translatable("screen.create_parachute.rot_drag"), gx + 24, gy + 51, 0xFFE2E2E2, false);
        guiGraphics.drawString(this.font, Component.translatable("screen.create_parachute.disconnect_speed"), gx + 24, gy + 75, 0xFFE2E2E2, false);

        String redstoneText = this.pendingRedstone ? "screen.create_parachute.on" : "screen.create_parachute.off";
        String lowSpeedText = this.pendingLowSpeed ? "screen.create_parachute.on" : "screen.create_parachute.off";
        guiGraphics.drawString(this.font, Component.translatable("screen.create_parachute.toggle_redstone"), gx + 27, gy + 108, 0xFFE2E2E2, false);
        guiGraphics.drawString(this.font, Component.translatable(redstoneText), gx + 51, gy + 108, 0xFFE2E2E2, false);
        guiGraphics.drawString(this.font, Component.translatable("screen.create_parachute.toggle_low_speed"), gx + 91, gy + 108, 0xFFE2E2E2, false);
        guiGraphics.drawString(this.font, Component.translatable(lowSpeedText), gx + 115, gy + 108, 0xFFE2E2E2, false);

        // 手动渲染控件（EditBox）
        for (Renderable renderable : this.renderables) {
            renderable.render(guiGraphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    // ==================== 工具 ====================

    private static boolean isInRect(double mx, double my, int bx, int by, int bw, int bh) {
        return mx >= bx && mx < bx + bw && my >= by && my < by + bh;
    }

    private static boolean filterNumeric(String text) {
        if (text.isEmpty()) return true;
        int dots = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '.') {
                if (++dots > 1) return false;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static String formatCoeff(double value) {
        return String.format("%.2f", value);
    }

    private void playClickSound() {
        if (this.minecraft != null && this.minecraft.getSoundManager() != null) {
            this.minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }
}
