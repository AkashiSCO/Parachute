package com.create.parachute.client.gui;

import com.create.parachute.client.ParachuteSelectionScreen;
import com.create.parachute.client.ParachuteTransformScreen;
import com.create.parachute.network.SyncParachuteLockPayload;
import com.create.parachute.network.SyncParachutePackPayload;
import com.create.parachute.network.SyncParachuteTransformPayload;
import com.create.parachute.parachute.ParachuteBlock;
import com.create.parachute.parachute.ParachuteBlockEntity;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

/**
 * 伞界面基类：把三个界面逐字重复的东西统一起来。
 *
 * <h2>统一的绘制顺序</h2>
 * <pre>
 * 变暗背景 → 面板底图 → renderBehind（选伞界面的列表/滚动条）
 *          → 面板按钮（悬停/按下/状态图标） → renderFront（文字、锁定图标） → 控件（输入框）
 * </pre>
 * <p>顺序必须这样排：选伞界面底部的"盖住越界行"的底图条会画在按钮位置，所以按钮要在它之后画。</p>
 *
 * <h2>统一的交互路由</h2>
 * <p>{@link ButtonGroup} 负责按钮；命中不到再交给 {@code Screen}（输入框聚焦等），与原来的写法等价。</p>
 */
public abstract class ParachutePanelScreen extends Screen {

    private final ResourceLocation background;
    protected final ButtonGroup buttons = new ButtonGroup();
    /** 目标方块位置；null = 手持伞包（没有方块可写） */
    @Nullable
    protected final BlockPos targetPos;

    protected int panelX;
    protected int panelY;
    /** 是否锁定自摆动（三个界面共享的开关状态） */
    protected boolean pendingLocked;
    /** 伞包方块模型是否显示（本地标志，点击立即生效；与锁定键同一套机制） */
    protected boolean pendingPackVisible = true;

    protected ParachutePanelScreen(Component title, ResourceLocation background, @Nullable BlockPos targetPos) {
        super(title);
        this.background = background;
        this.targetPos = targetPos;
    }

    @Override
    protected final void init() {
        this.panelX = GuiStyle.panelX(this.width);
        this.panelY = GuiStyle.panelY(this.height);
        this.buttons.clear();
        this.initPanel();
    }

    /** 子类在这里创建按钮与输入框（窗口缩放会重跑，注意每次重建） */
    protected abstract void initPanel();

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        GuiStyle.drawPanel(graphics, this.background, this.panelX, this.panelY);
        this.renderBehind(graphics, mouseX, mouseY, partialTick);
        this.buttons.render(graphics, this.panelX, this.panelY, mouseX, mouseY);
        this.renderFront(graphics, mouseX, mouseY, partialTick);
        GuiStyle.renderWidgets(graphics, this.renderables, mouseX, mouseY, partialTick);
    }

    /** 面板底图之后、按钮之前绘制（选伞界面的列表区域） */
    protected void renderBehind(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    /** 按钮之后绘制（标题/字段标签/锁定状态图标） */
    protected void renderFront(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.buttons.mouseClicked(mouseX, mouseY, this.panelX, this.panelY)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        this.buttons.mouseReleased(mouseX, mouseY, this.panelX, this.panelY);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 标准点击音 */
    protected void click() {
        GuiStyle.playClick(this.minecraft);
    }

    /** 目标方块实体；没有目标、或在物品上、或未加载时为 null */
    @Nullable
    protected ParachuteBlockEntity targetBlockEntity() {
        if (this.targetPos == null || this.minecraft == null || this.minecraft.level == null) return null;
        return this.minecraft.level.getBlockEntity(this.targetPos) instanceof ParachuteBlockEntity be ? be : null;
    }

    /** 锁定/解锁自摆动：先改本地方块实体（立即生效），再同步服务端 */
    protected void toggleWobbleLock() {
        this.pendingLocked = !this.pendingLocked;
        ParachuteBlockEntity be = targetBlockEntity();
        if (be != null) {
            be.setWobbleLocked(this.pendingLocked);
        }
        if (this.targetPos != null) {
            PacketDistributor.sendToServer(new SyncParachuteLockPayload(this.targetPos, this.pendingLocked));
        }
        click();
    }

    /** 蓝色文件夹键：进入选伞界面 */
    protected void openSelectionScreen() {
        click();
        if (this.minecraft != null) {
            this.minecraft.setScreen(new ParachuteSelectionScreen(this.targetPos));
        }
    }

    /** 变换模式键：进入 controller_3 界面（mode 含 {@link SyncParachuteTransformPayload#MODE_SCALE}） */
    protected void openTransformScreen(int mode) {
        click();
        if (this.minecraft != null) {
            this.minecraft.setScreen(new ParachuteTransformScreen(this.targetPos, mode));
        }
    }

    // ============================================================
    // 两个界面共用的新增按键（两个背景图里坐标一致，所以工厂放在基类）
    // ============================================================

    /**
     * 锁定键：锁定/解锁自摆动。与 {@link #packButton()} 完全对称的一对按钮——
     * 同样的命中框尺寸、同样的状态图标机制（锁定时画图集里的红框挂锁）。
     */
    protected final TextureButton lockButton() {
        return TextureButton.icon(GuiStyle.LOCK_BTN_X, GuiStyle.LOCK_BTN_Y, GuiStyle.LOCK_BTN_W, GuiStyle.LOCK_BTN_H,
                TextureButton.Art.at(GuiStyle.LOCK_ICON_U, GuiStyle.LOCK_ICON_V,
                        GuiStyle.LOCK_ICON_W, GuiStyle.LOCK_ICON_H),
                () -> this.pendingLocked, this::toggleWobbleLock);
    }

    /**
     * 红键：显示/隐藏伞包方块自身的模型。
     *
     * <p>和锁定键<b>完全同一套机制</b>：图标由本地 {@link #pendingPackVisible} 决定，点击即切换、
     * 立刻重画（不依赖服务端回来），有方块时才把新状态发到服务端写进 blockstate。
     * 之前这里直接读方块状态，会出现"点了没反应"（手持伞包时没有方块可读，而且发出去的改动要等 round-trip）。</p>
     */
    protected final TextureButton packButton() {
        return TextureButton.icon(GuiStyle.PACK_BTN_X, GuiStyle.PACK_BTN_Y, GuiStyle.PACK_BTN_W, GuiStyle.PACK_BTN_H,
                TextureButton.Art.at(GuiStyle.PACK_ICON_U, GuiStyle.PACK_ICON_V,
                        GuiStyle.PACK_ICON_W, GuiStyle.PACK_ICON_H),
                () -> !this.pendingPackVisible, this::togglePackVisible);
    }

    /** 灰键：进入渲染缩放界面 */
    protected final TextureButton scaleButton() {
        return TextureButton.art(GuiStyle.SCALE_BTN_X, GuiStyle.SCALE_BTN_Y,
                GuiStyle.SCALE_BTN_W, GuiStyle.SCALE_BTN_H,
                TextureButton.Art.at(GuiStyle.SCALE_STATE_U, GuiStyle.SCALE_STATE_V,
                        GuiStyle.SCALE_STATE_W, GuiStyle.SCALE_STATE_H).offset(1, 0),
                TextureButton.Art.at(GuiStyle.SCALE_STATE_U, GuiStyle.SCALE_STATE_V_PRESSED,
                        GuiStyle.SCALE_STATE_W, GuiStyle.SCALE_STATE_H).offset(1, 0),
                () -> this.openTransformScreen(SyncParachuteTransformPayload.MODE_SCALE));
    }

    /** 伞包方块模型是否显示；打开界面时从方块状态同步一次（物品/无方块时保持 true） */
    protected void syncPackVisibleFromWorld() {
        if (this.targetPos == null || this.minecraft == null || this.minecraft.level == null) {
            this.pendingPackVisible = true;
            return;
        }
        BlockState state = this.minecraft.level.getBlockState(this.targetPos);
        this.pendingPackVisible = !state.hasProperty(ParachuteBlock.PACK) || state.getValue(ParachuteBlock.PACK);
    }

    /** 切换伞包方块模型显示/隐藏：本地立刻换图标，有方块时再写到服务端的 blockstate */
    protected void togglePackVisible() {
        this.pendingPackVisible = !this.pendingPackVisible;
        if (this.targetPos != null) {
            PacketDistributor.sendToServer(new SyncParachutePackPayload(this.targetPos, this.pendingPackVisible));
        }
        click();
    }
}
