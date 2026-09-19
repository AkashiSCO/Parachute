package com.create.parachute.client;

import com.create.parachute.client.gui.GuiStyle;
import com.create.parachute.client.gui.ParachutePanelScreen;
import com.create.parachute.client.gui.SpriteField;
import com.create.parachute.client.gui.TextureButton;
import com.create.parachute.network.SyncParachuteTransformPayload;
import com.create.parachute.parachute.ParachuteBlockEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 变换设置界面（parachute_controller_3.png）：同一界面多种模式，只换文本和输入框数量。
 * <ul>
 *   <li>旋转模式：X/Y/Z 旋转（度，自摆动坐标系）</li>
 *   <li>枢轴模式：X/Y/Z 枢轴偏移（模型相对枢轴的位置，格）</li>
 *   <li>偏移模式：X/Y/Z 整体偏移（含放置自带偏移，格）</li>
 *   <li>缩放模式：只有一个输入框，整体缩放系数（以枢轴点为中心；背景里多余的框由
 *       {@link GuiStyle#blankFieldRows} 现场抹平，所以看起来只有一行）</li>
 * </ul>
 * 界面上的按钮（锁定 / 伞包显示隐藏 / 四个模式 / 文件夹）都可点击；
 * 底部对勾键保存当前值写回目标方块。
 */
public class ParachuteTransformScreen extends ParachutePanelScreen {

    private static final ResourceLocation BACKGROUND = GuiStyle.texture("parachute_controller_3");

    /** 底部对勾（保存），状态图取自图集同位置 */
    private static final int CONFIRM_X = 154;
    private static final int CONFIRM_Y = 104;
    private static final int CONFIRM_W = 18;
    private static final int CONFIRM_H = 18;
    /** 文件夹键 */
    private static final int FOLDER_X = 189;
    private static final int FOLDER_Y = 101;
    private static final int FOLDER_W = 24;
    private static final int FOLDER_H = 25;
    /** 三个模式按钮：命中框 18×18，图标 17×17 右移 1px */
    private static final int MODE_X = 180;
    private static final int MODE_W = 18;
    private static final int MODE_H = 18;
    private static final int[] MODE_YS = {23, 47, 71};
    private static final int[] MODE_STATE_XS = {1, 19, 37};
    private static final int MODE_STATE_V = 92;
    private static final int MODE_STATE_V_PRESSED = 111;
    private static final int MODE_STATE_W = 17;
    private static final int MODE_STATE_H = 17;
    private static final int[] MODES = {
            SyncParachuteTransformPayload.MODE_ROTATION,
            SyncParachuteTransformPayload.MODE_PIVOT,
            SyncParachuteTransformPayload.MODE_OFFSET
    };

    /** 当前模式，含 {@link SyncParachuteTransformPayload#MODE_SCALE} */
    private final int mode;
    private final boolean scaleMode;

    /** 缩放模式只用一个输入框，其余模式三个 */
    private final SpriteField[] fields;
    private float pendingX;
    private float pendingY;
    private float pendingZ;

    public ParachuteTransformScreen(@Nullable BlockPos targetPos, int mode) {
        super(Component.translatable(titleKey(mode)), BACKGROUND, targetPos);
        this.mode = mode;
        this.scaleMode = mode == SyncParachuteTransformPayload.MODE_SCALE;
        this.fields = new SpriteField[this.scaleMode ? 1 : 3];
    }

    private static String titleKey(int mode) {
        return switch (mode) {
            case SyncParachuteTransformPayload.MODE_PIVOT -> "screen.create_parachute.set_pivot";
            case SyncParachuteTransformPayload.MODE_OFFSET -> "screen.create_parachute.set_offset";
            case SyncParachuteTransformPayload.MODE_SCALE -> "screen.create_parachute.set_scale";
            default -> "screen.create_parachute.set_rotation";
        };
    }

    /** 每行输入框左边的标签文本 */
    private String labelKey(int axis) {
        if (this.scaleMode) return "screen.create_parachute.scale";
        String prefix = switch (this.mode) {
            case SyncParachuteTransformPayload.MODE_PIVOT -> "pivot";
            case SyncParachuteTransformPayload.MODE_OFFSET -> "off";
            default -> "rot";
        };
        return "screen.create_parachute." + prefix + "_" + "xyz".charAt(axis);
    }

    @Override
    protected void initPanel() {
        loadFromTarget();
        syncPackVisibleFromWorld();

        for (int i = 0; i < this.fields.length; i++) {
            final int axis = i;
            this.fields[i] = new SpriteField(this.font, this.panelX, this.panelY,
                    GuiStyle.FIELD_BOX_X, GuiStyle.FIELD_BOX_YS[i], true,
                    value -> onFieldChanged(axis, (float) value));
            this.addRenderableWidget(this.fields[i].widget());
        }

        this.buttons
                .add(TextureButton.sheet(CONFIRM_X, CONFIRM_Y, CONFIRM_W, CONFIRM_H, this::save))
                .add(TextureButton.sheet(FOLDER_X, FOLDER_Y, FOLDER_W, FOLDER_H, 25, this::openSelectionScreen))
                .add(lockButton())
                .add(packButton())
                .add(scaleButton())
                .add(modeButton(0))
                .add(modeButton(1))
                .add(modeButton(2));

        this.updateFieldDisplay();
    }

    private void onFieldChanged(int axis, float value) {
        if (this.scaleMode) {
            this.pendingX = value;
            return;
        }
        switch (axis) {
            case 0 -> this.pendingX = value;
            case 1 -> this.pendingY = value;
            default -> this.pendingZ = value;
        }
    }

    /** 模式键：点了就切到同一个 controller_3 界面的另一个模式 */
    private TextureButton modeButton(int index) {
        return TextureButton.art(MODE_X, MODE_YS[index], MODE_W, MODE_H,
                TextureButton.Art.at(MODE_STATE_XS[index], MODE_STATE_V, MODE_STATE_W, MODE_STATE_H).offset(1, 0),
                TextureButton.Art.at(MODE_STATE_XS[index], MODE_STATE_V_PRESSED, MODE_STATE_W, MODE_STATE_H).offset(1, 0),
                () -> this.openTransformScreen(MODES[index]));
    }

    /** 从目标方块实体读取当前模式对应的值；物品（无方块）用默认值 */
    private void loadFromTarget() {
        this.pendingX = this.scaleMode ? 1.0F : 0.0F;
        this.pendingY = 0.0F;
        this.pendingZ = 0.0F;
        this.pendingLocked = false;
        ParachuteBlockEntity be = targetBlockEntity();
        if (be == null) return;
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
            case SyncParachuteTransformPayload.MODE_SCALE -> this.pendingX = be.getRenderScale();
            default -> {
            }
        }
    }

    /** 对勾保存：把当前值写回目标方块 */
    private void save() {
        this.pendingX = (float) this.fields[0].value(this.pendingX);
        if (!this.scaleMode) {
            this.pendingY = (float) this.fields[1].value(this.pendingY);
            this.pendingZ = (float) this.fields[2].value(this.pendingZ);
        }
        ParachuteBlockEntity be = targetBlockEntity();
        if (be != null) {
            switch (this.mode) {
                case SyncParachuteTransformPayload.MODE_ROTATION -> be.setRotation(this.pendingX, this.pendingY, this.pendingZ);
                case SyncParachuteTransformPayload.MODE_PIVOT -> be.setPivot(this.pendingX, this.pendingY, this.pendingZ);
                case SyncParachuteTransformPayload.MODE_OFFSET -> be.setOffset(this.pendingX, this.pendingY, this.pendingZ);
                case SyncParachuteTransformPayload.MODE_SCALE -> be.setRenderScale(this.pendingX);
                default -> {
                }
            }
        }
        if (this.targetPos != null) {
            PacketDistributor.sendToServer(new SyncParachuteTransformPayload(
                    this.targetPos, this.mode, this.pendingX, this.pendingY, this.pendingZ));
        }
        click();
    }

    private void updateFieldDisplay() {
        for (int i = 0; i < this.fields.length; i++) {
            this.fields[i].syncDisplay(i == 0 ? this.pendingX : (i == 1 ? this.pendingY : this.pendingZ));
        }
    }

    @Override
    public void tick() {
        this.updateFieldDisplay();
    }

    @Override
    protected void renderBehind(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (this.scaleMode) {
            // 缩放模式只用第一行：把背景里第 2、3 行的框现场抹平（等背景擦干净后可删）
            GuiStyle.blankFieldRows(graphics, BACKGROUND, this.panelX, this.panelY);
        }
        for (SpriteField field : this.fields) {
            field.render(graphics);
        }
    }

    @Override
    protected void renderFront(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int gx = this.panelX;
        int gy = this.panelY;

        GuiStyle.drawLabel(graphics, this.font, this.title, gx + 21, gy + 3);
        for (int i = 0; i < this.fields.length; i++) {
            GuiStyle.drawLabel(graphics, this.font, Component.translatable(labelKey(i)),
                    gx + 24, gy + GuiStyle.FIELD_BOX_YS[i] + 3);
        }
    }
}
