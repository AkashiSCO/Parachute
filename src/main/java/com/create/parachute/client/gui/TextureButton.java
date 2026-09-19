package com.create.parachute.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import org.jetbrains.annotations.Nullable;

import java.util.function.BooleanSupplier;

/**
 * 面板按钮：把所有界面里原本各写一遍的交互规律收拢到一处。
 *
 * <h2>交互语义（规范化后的唯一一套）</h2>
 * <ol>
 *   <li>{@link #mouseClicked}：落在命中框内 → 标记按下并吃掉这次点击（返回 true），此时什么也不做</li>
 *   <li>{@link #mouseReleased}：<b>按下过</b>且松手时仍在框内 → 执行动作；否则取消（拖出去松手不触发）</li>
 *   <li>{@link #render}：只在悬停或按下时叠状态图；都不满足就不画，露出底图自带的常态外观</li>
 * </ol>
 *
 * <p>命中框用<b>面板内坐标</b>，绘制时由调用方传入面板原点，界面缩放/移动都不用改按钮本身。</p>
 *
 * <p>三种按钮形态：</p>
 * <ul>
 *   <li>{@link #sheet}：状态图就是图集里与按钮同位置的两块（悬停 v=0、按下 v=18）——底部三个按钮、
 *       对勾、文件夹键都是这种</li>
 *   <li>{@link #art}：状态图的尺寸/位置与命中框不同（三个变换模式键：图 17×17、命中框 18×18、图右移 1px）</li>
 *   <li>{@link #icon}：常驻状态图标（锁定键），只按状态画图，没有悬停/按下反馈</li>
 * </ul>
 */
public final class TextureButton {

    /**
     * 状态图：按钮图集里的区域 {@code (u,v,w,h)} 加上相对命中框左上角的偏移 {@code (dx,dy)}。
     */
    public record Art(int u, int v, int w, int h, int dx, int dy) {

        /** 与命中框对齐的状态图 */
        public static Art at(int u, int v, int w, int h) {
            return new Art(u, v, w, h, 0, 0);
        }

        /** 图相对命中框偏移 */
        public Art offset(int dx, int dy) {
            return new Art(this.u, this.v, this.w, this.h, dx, dy);
        }
    }

    private final int x;
    private final int y;
    private final int width;
    private final int height;
    @Nullable
    private final Art hoverArt;
    @Nullable
    private final Art pressedArt;
    @Nullable
    private final Art stateIcon;
    @Nullable
    private final BooleanSupplier stateIconVisible;
    private final Runnable action;
    private boolean pressed;

    private TextureButton(int x, int y, int width, int height,
                          @Nullable Art hoverArt, @Nullable Art pressedArt,
                          @Nullable Art stateIcon, @Nullable BooleanSupplier stateIconVisible,
                          Runnable action) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.hoverArt = hoverArt;
        this.pressedArt = pressedArt;
        this.stateIcon = stateIcon;
        this.stateIconVisible = stateIconVisible;
        this.action = action;
    }

    /**
     * 状态图与按钮同位置（悬停 {@code v=0}、按下 {@code v=18}，尺寸同命中框）。
     * 这是绝大多数按钮的形态：图集按底图坐标排版，所以按钮在面板里的 x 就是它的 u。
     * <p><b>只适用 18 高的按钮</b>（底部三键、对勾）。更高的按钮按下态 v 不在 18，
     * 要用 {@link #sheet(int, int, int, int, int, Runnable)} 显式给。</p>
     */
    public static TextureButton sheet(int x, int y, int width, int height, Runnable action) {
        return sheet(x, y, width, height, 18, action);
    }

    /**
     * 同上，但按下态用指定的 v。
     * <p>文件夹键就是这种：图集里悬停精灵在 v=0、按下精灵在 v=25（两个精灵各 24 高），
     * 按下若也取 v=18 会跨在两个精灵中间，拼出"上下两个框"的错图。</p>
     */
    public static TextureButton sheet(int x, int y, int width, int height, int pressedV, Runnable action) {
        return new TextureButton(x, y, width, height,
                Art.at(x, 0, width, height), Art.at(x, pressedV, width, height), null, null, action);
    }

    /** 自定状态图（尺寸/偏移与命中框不同时用） */
    public static TextureButton art(int x, int y, int width, int height,
                                    @Nullable Art hoverArt, @Nullable Art pressedArt, Runnable action) {
        return new TextureButton(x, y, width, height, hoverArt, pressedArt, null, null, action);
    }

    /** 常驻状态图标：{@code visible} 为真时画 {@code icon}，不参与悬停/按下换图 */
    public static TextureButton icon(int x, int y, int width, int height,
                                     Art icon, BooleanSupplier visible, Runnable action) {
        return new TextureButton(x, y, width, height, null, null, icon, visible, action);
    }

    /** 落在命中框内则标记按下并吃掉这次点击 */
    public boolean mouseClicked(double mouseX, double mouseY, int panelX, int panelY) {
        if (!contains(mouseX, mouseY, panelX, panelY)) return false;
        this.pressed = true;
        return true;
    }

    /** 按下过且松手时仍在框内才执行动作；返回是否真的执行了 */
    public boolean mouseReleased(double mouseX, double mouseY, int panelX, int panelY) {
        boolean wasPressed = this.pressed;
        this.pressed = false;
        if (!wasPressed || !contains(mouseX, mouseY, panelX, panelY)) return false;
        this.action.run();
        return true;
    }

    /** 取消按下状态（松手时统一清理） */
    public void cancelPress() {
        this.pressed = false;
    }

    /** 是否命中该按钮 */
    public boolean contains(double mouseX, double mouseY, int panelX, int panelY) {
        return GuiStyle.isInRect(mouseX, mouseY, panelX + this.x, panelY + this.y, this.width, this.height);
    }

    /** 按状态画状态图（无状态变化则不画） */
    public void render(GuiGraphics graphics, int panelX, int panelY, int mouseX, int mouseY) {
        if (this.stateIcon != null) {
            if (this.stateIconVisible != null && this.stateIconVisible.getAsBoolean()) {
                draw(graphics, panelX, panelY, this.stateIcon);
            }
            return;
        }
        Art art = null;
        if (this.pressed) {
            art = this.pressedArt;
        } else if (contains(mouseX, mouseY, panelX, panelY)) {
            art = this.hoverArt;
        }
        if (art != null) {
            draw(graphics, panelX, panelY, art);
        }
    }

    private void draw(GuiGraphics graphics, int panelX, int panelY, Art art) {
        GuiStyle.drawArt(graphics, panelX + this.x + art.dx(), panelY + this.y + art.dy(), art);
    }
}
