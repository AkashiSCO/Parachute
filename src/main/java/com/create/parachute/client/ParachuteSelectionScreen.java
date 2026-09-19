package com.create.parachute.client;

import com.create.parachute.ParachuteMod;
import com.create.parachute.client.assets.ParachuteAssets;
import com.create.parachute.client.gui.GuiStyle;
import com.create.parachute.client.gui.ParachutePanelScreen;
import com.create.parachute.client.gui.ScrollList;
import com.create.parachute.client.gui.TextureButton;
import com.create.parachute.data.ParachuteManager;
import com.create.parachute.network.SyncParachuteSelectionPayload;
import com.create.parachute.parachute.ParachuteBlockEntity;
import com.create.parachute.parachute.ParachutePackItem;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;
import org.jetbrains.annotations.Nullable;

import java.io.File;

/**
 * 选伞界面（parachute_controller_2.png），由控制器主 GUI 的蓝色文件夹键打开。
 *
 * <p>列表与滑块交给 {@link ScrollList}（点击选行、拖拽、滚轮、热加载刷新），本类只负责：
 * 从 {@link ParachuteAssets} 取伞名、把选中的伞写回目标、给"本地兜底"的条目加来源标记、
 * 以及用系统文件管理器打开 parachute 文件夹。</p>
 */
public class ParachuteSelectionScreen extends ParachutePanelScreen {

    private static final ResourceLocation BACKGROUND = GuiStyle.texture("parachute_controller_2");

    /** 确定键（对勾）：把选中的伞写入目标 */
    private static final int CONFIRM_X = 154;
    private static final int CONFIRM_Y = 104;
    private static final int CONFIRM_W = 18;
    private static final int CONFIRM_H = 18;
    /** 打开文件夹键 */
    private static final int FOLDER_X = 189;
    private static final int FOLDER_Y = 101;
    private static final int FOLDER_W = 24;
    private static final int FOLDER_H = 25;

    /** 「本地兜底」标记文本（懒加载，避免每帧拼一遍） */
    private static String localTag;

    private final ScrollList list = new ScrollList(this::click);

    public ParachuteSelectionScreen(@Nullable BlockPos targetPos) {
        super(Component.translatable("screen.create_parachute.folder"), BACKGROUND, targetPos);
    }

    private static String localTag() {
        if (localTag == null) {
            localTag = " · " + Component.translatable("screen.create_parachute.source.local").getString();
        }
        return localTag;
    }

    @Override
    protected void initPanel() {
        ParachuteAssets.forceRefresh();
        this.list.reload(ParachuteAssets.listIds(), ParachuteManager.DEFAULT_PARACHUTE);
        this.list.select(initialSelection());
        this.list.scrollToSelection();

        this.buttons
                .add(TextureButton.sheet(CONFIRM_X, CONFIRM_Y, CONFIRM_W, CONFIRM_H, this::confirm))
                .add(TextureButton.sheet(FOLDER_X, FOLDER_Y, FOLDER_W, FOLDER_H, 25, this::openFolder));
    }

    /** 初始选中：目标方块实体 → 手持伞包 → 默认伞 → 第一条 */
    private String initialSelection() {
        if (this.minecraft != null && this.minecraft.level != null && this.targetPos != null
                && this.minecraft.level.getBlockEntity(this.targetPos) instanceof ParachuteBlockEntity be) {
            String name = be.getParachuteName();
            if (this.list.contains(name)) return name;
        }
        if (this.minecraft != null && this.minecraft.player != null) {
            ItemStack stack = this.minecraft.player.getMainHandItem();
            if (!(stack.getItem() instanceof ParachutePackItem)) {
                stack = this.minecraft.player.getOffhandItem();
            }
            String name = ParachutePackItem.getParachuteName(stack);
            if (this.list.contains(name)) return name;
        }
        if (this.list.contains(ParachuteManager.DEFAULT_PARACHUTE)) return ParachuteManager.DEFAULT_PARACHUTE;
        return this.list.rows().isEmpty() ? "" : this.list.rows().get(0);
    }

    /** 确定：把选中的伞写入目标，界面保持打开 */
    private void confirm() {
        String selected = this.list.selected();
        if (selected.isEmpty()) return;
        PacketDistributor.sendToServer(new SyncParachuteSelectionPayload(this.targetPos, selected));
        click();
    }

    /** 文件夹键：用系统文件管理器打开游戏目录下的 parachute 文件夹 */
    private void openFolder() {
        click();
        if (this.minecraft == null) return;
        try {
            File dir = new File(this.minecraft.gameDirectory, ParachuteManager.FOLDER_NAME);
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
            ParachuteMod.LOGGER.warn("无法打开伞文件夹: {}", e.toString());
        }
    }

    /** 行文本：连服务器时，服务器文件夹里没有、只好用本地那份的条目标上来源 */
    private String rowLabel(String id) {
        return ParachuteAssets.isLocalFallback(id) ? id + localTag() : id;
    }

    @Override
    public void tick() {
        this.list.refreshIfChanged(ParachuteAssets::listIds, ParachuteManager.DEFAULT_PARACHUTE);
    }

    // ==================== 输入 ====================

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.list.mouseClicked(mouseX, mouseY, this.panelX, this.panelY)) return true;
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.list.mouseDragged(mouseY, this.panelY)) return true;
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseReleased(mouseX, mouseY, button);
        this.list.mouseReleased();
        return handled;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaX, double deltaY) {
        if (this.list.mouseScrolled(mouseX, mouseY, deltaY, this.panelX, this.panelY)) return true;
        return super.mouseScrolled(mouseX, mouseY, deltaX, deltaY);
    }

    // ==================== 渲染 ====================

    @Override
    protected void renderBehind(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        this.list.render(graphics, this.panelX, this.panelY, this.font, BACKGROUND, this::rowLabel);
    }

    @Override
    protected void renderFront(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        GuiStyle.drawLabel(graphics, this.font, this.title, this.panelX + 21, this.panelY + 3);
    }
}
