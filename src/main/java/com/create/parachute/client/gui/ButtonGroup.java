package com.create.parachute.client.gui;

import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 一组 {@link TextureButton}：统一转发点击、松手与绘制，界面类不用再维护
 * {@code pressedButton} 之类的编号和一堆重复的命中判断。
 *
 * <p>点击时只有第一个命中的按钮被按下；松手时所有按钮都会收到通知（只有按下过的那个会执行动作），
 * 于是"按 A 拖到 B 上松手不会触发 B"这条规律天然成立。</p>
 */
public final class ButtonGroup {

    private final List<TextureButton> buttons = new ArrayList<>();

    public ButtonGroup add(TextureButton button) {
        this.buttons.add(button);
        return this;
    }

    /** 窗口缩放会重跑 init，按钮要重建，先清空 */
    public void clear() {
        this.buttons.clear();
    }

    public boolean mouseClicked(double mouseX, double mouseY, int panelX, int panelY) {
        for (TextureButton button : this.buttons) {
            if (button.mouseClicked(mouseX, mouseY, panelX, panelY)) return true;
        }
        return false;
    }

    public void mouseReleased(double mouseX, double mouseY, int panelX, int panelY) {
        for (TextureButton button : this.buttons) {
            button.mouseReleased(mouseX, mouseY, panelX, panelY);
        }
    }

    /** 取消所有按下状态（例如界面被切走时） */
    public void cancelPresses() {
        for (TextureButton button : this.buttons) {
            button.cancelPress();
        }
    }

    public void render(GuiGraphics graphics, int panelX, int panelY, int mouseX, int mouseY) {
        for (TextureButton button : this.buttons) {
            button.render(graphics, panelX, panelY, mouseX, mouseY);
        }
    }
}
