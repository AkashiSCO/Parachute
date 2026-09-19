package com.create.parachute.client;

import com.create.parachute.client.gui.GuiStyle;
import com.create.parachute.client.gui.ParachutePanelScreen;
import com.create.parachute.client.gui.SpriteField;
import com.create.parachute.client.gui.TextureButton;
import com.create.parachute.network.SyncParachuteConfigPayload;
import com.create.parachute.network.SyncParachuteTransformPayload;
import com.create.parachute.parachute.ParachuteBlockEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 控制器主 GUI（parachute_controller_new.png）：参数调节界面。
 *
 * <p>方块右键和伞包右键都会先打开本界面（{@code targetPos} 非空 = 方块，null = 手持伞包）。
 * 可调阻力系数 / 旋转阻尼 / 切伞速度，红石断开和低速断开开关，保存键写回方块（物品无方块可写）。
 * 蓝色"打开文件夹"键进入选伞界面 {@link ParachuteSelectionScreen}；灰键进入渲染缩放界面
 * （{@link ParachuteTransformScreen} 的缩放模式）；红键切换伞包方块模型的显示/隐藏。</p>
 *
 * <p>按钮与输入框统一走 {@code client.gui} 里的组件，本类只负责数值与业务动作。</p>
 */
public class ParachuteScreen extends ParachutePanelScreen {

    private static final ResourceLocation BACKGROUND = GuiStyle.texture("parachute_controller_new");

    /** 底部三个按钮（红石断开 / 低速断开 / 保存）的位置与尺寸，状态图取自图集同位置 */
    private static final int[][] BOTTOM_BUTTONS = {
            {21, 104, 50, 18},
            {85, 104, 50, 18},
            {154, 104, 18, 18}
    };
    /** 蓝色"打开文件夹"键（进入选伞界面） */
    private static final int FOLDER_X = 189;
    private static final int FOLDER_Y = 101;
    private static final int FOLDER_W = 24;
    private static final int FOLDER_H = 25;
    /** 三个变换模式按钮（三个字段行右侧）：旋转 / 枢轴点 / 整体偏移 */
    private static final int MODE_X = 180;
    private static final int MODE_W = 18;
    private static final int MODE_H = 18;
    private static final int[] MODE_YS = {23, 47, 71};
    /** 模式图标在按钮图集底部：旋转(1,92) 枢轴(19,92) 偏移(37,92)，按下态 v=111；图 17×17 且右移 1px 对齐 */
    private static final int[] MODE_STATE_XS = {1, 19, 37};
    private static final int MODE_STATE_V = 92;
    private static final int MODE_STATE_V_PRESSED = 111;
    private static final int MODE_STATE_W = 17;
    private static final int MODE_STATE_H = 17;

    private SpriteField kField;
    private SpriteField rField;
    private SpriteField vField;
    private double pendingK;
    private double pendingR;
    private double pendingV;
    private boolean pendingLowSpeed = true;
    private boolean pendingRedstone = true;

    public ParachuteScreen(@Nullable BlockPos targetPos) {
        super(Component.translatable("screen.create_parachute.parachute"), BACKGROUND, targetPos);
    }

    @Override
    protected void initPanel() {
        loadConfigFromTarget();
        syncPackVisibleFromWorld();

        this.kField = new SpriteField(this.font, this.panelX, this.panelY, GuiStyle.FIELD_BOX_X, GuiStyle.FIELD_BOX_YS[0], false, value -> this.pendingK = value);
        this.rField = new SpriteField(this.font, this.panelX, this.panelY, GuiStyle.FIELD_BOX_X, GuiStyle.FIELD_BOX_YS[1], false, value -> this.pendingR = value);
        this.vField = new SpriteField(this.font, this.panelX, this.panelY, GuiStyle.FIELD_BOX_X, GuiStyle.FIELD_BOX_YS[2], false, value -> this.pendingV = value);
        this.addRenderableWidget(this.kField.widget());
        this.addRenderableWidget(this.rField.widget());
        this.addRenderableWidget(this.vField.widget());

        this.buttons
                .add(TextureButton.sheet(BOTTOM_BUTTONS[0][0], BOTTOM_BUTTONS[0][1],
                        BOTTOM_BUTTONS[0][2], BOTTOM_BUTTONS[0][3], this::toggleRedstone))
                .add(TextureButton.sheet(BOTTOM_BUTTONS[1][0], BOTTOM_BUTTONS[1][1],
                        BOTTOM_BUTTONS[1][2], BOTTOM_BUTTONS[1][3], this::toggleLowSpeed))
                .add(TextureButton.sheet(BOTTOM_BUTTONS[2][0], BOTTOM_BUTTONS[2][1],
                        BOTTOM_BUTTONS[2][2], BOTTOM_BUTTONS[2][3], this::saveConfig))
                .add(TextureButton.sheet(FOLDER_X, FOLDER_Y, FOLDER_W, FOLDER_H, 25, this::openSelectionScreen))
                .add(lockButton())
                .add(packButton())
                .add(scaleButton())
                .add(modeButton(0, SyncParachuteTransformPayload.MODE_ROTATION))
                .add(modeButton(1, SyncParachuteTransformPayload.MODE_PIVOT))
                .add(modeButton(2, SyncParachuteTransformPayload.MODE_OFFSET));

        this.updateFieldDisplay();
    }

    /** 变换模式键：命中框 18×18，状态图 17×17 右移 1px（沿用原对齐方式） */
    private TextureButton modeButton(int index, int mode) {
        return TextureButton.art(MODE_X, MODE_YS[index], MODE_W, MODE_H,
                TextureButton.Art.at(MODE_STATE_XS[index], MODE_STATE_V, MODE_STATE_W, MODE_STATE_H).offset(1, 0),
                TextureButton.Art.at(MODE_STATE_XS[index], MODE_STATE_V_PRESSED, MODE_STATE_W, MODE_STATE_H).offset(1, 0),
                () -> this.openTransformScreen(mode));
    }

    /** 从目标方块实体读取参数；物品（无方块）用默认值 */
    private void loadConfigFromTarget() {
        this.pendingK = 0.0D;
        this.pendingR = 0.0D;
        this.pendingV = 0.0D;
        this.pendingLowSpeed = false;
        this.pendingRedstone = true;
        this.pendingLocked = false;
        ParachuteBlockEntity be = targetBlockEntity();
        if (be != null) {
            this.pendingK = be.getDragCoefficient();
            this.pendingR = be.getRotationalDragCoefficient();
            this.pendingV = be.getDisconnectSpeedThreshold();
            this.pendingLowSpeed = be.isDisconnectOnLowSpeed();
            this.pendingRedstone = be.isDisconnectOnRedstonePulse();
            this.pendingLocked = be.isWobbleLocked();
        }
    }

    /** 红石断开开关 */
    private void toggleRedstone() {
        this.pendingRedstone = !this.pendingRedstone;
        this.sendConfig();
        click();
    }

    /** 低速断开开关 */
    private void toggleLowSpeed() {
        this.pendingLowSpeed = !this.pendingLowSpeed;
        this.sendConfig();
        click();
    }

    /** 对勾保存：先把输入框的值收进来，再整包写回 */
    private void saveConfig() {
        this.pendingK = this.kField.value(this.pendingK);
        this.pendingR = this.rField.value(this.pendingR);
        this.pendingV = this.vField.value(this.pendingV);
        this.sendConfig();
        click();
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

    private void updateFieldDisplay() {
        this.kField.syncDisplay(this.pendingK);
        this.rField.syncDisplay(this.pendingR);
        this.vField.syncDisplay(this.pendingV);
    }

    @Override
    public void tick() {
        this.updateFieldDisplay();
    }

    @Override
    protected void renderBehind(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.kField.render(graphics);
        this.rField.render(graphics);
        this.vField.render(graphics);
    }

    @Override
    protected void renderFront(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int gx = this.panelX;
        int gy = this.panelY;

        GuiStyle.drawLabel(graphics, this.font, this.title, gx + 21, gy + 3);
        GuiStyle.drawLabel(graphics, this.font, Component.translatable("screen.create_parachute.drag"), gx + 24, gy + 27);
        GuiStyle.drawLabel(graphics, this.font, Component.translatable("screen.create_parachute.rot_drag"), gx + 24, gy + 51);
        GuiStyle.drawLabel(graphics, this.font, Component.translatable("screen.create_parachute.disconnect_speed"), gx + 24, gy + 75);

        String redstoneText = this.pendingRedstone ? "screen.create_parachute.on" : "screen.create_parachute.off";
        String lowSpeedText = this.pendingLowSpeed ? "screen.create_parachute.on" : "screen.create_parachute.off";
        GuiStyle.drawLabel(graphics, this.font, Component.translatable("screen.create_parachute.toggle_redstone"), gx + 27, gy + 108);
        GuiStyle.drawLabel(graphics, this.font, Component.translatable(redstoneText), gx + 51, gy + 108);
        GuiStyle.drawLabel(graphics, this.font, Component.translatable("screen.create_parachute.toggle_low_speed"), gx + 91, gy + 108);
        GuiStyle.drawLabel(graphics, this.font, Component.translatable(lowSpeedText), gx + 115, gy + 108);
    }
}
