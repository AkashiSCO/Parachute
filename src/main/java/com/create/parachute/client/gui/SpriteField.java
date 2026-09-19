package com.create.parachute.client.gui;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.Font;

import java.util.function.DoubleConsumer;

/**
 * 输入框控件：**框是画出来的图层，不再烤在背景里**。
 *
 * <p>框的美术来自 {@link GuiStyle#FIELD_TEXTURE}（从原来的 controller_3 背景抠出来的独立贴图），
 * 标签小框 + 输入大框各一块精灵；文本用无边框 {@link EditBox} 叠在上面。于是：
 * 想在哪个界面、哪一行显示几个输入框都只是"画几个控件"的事，跟背景完全解耦。</p>
 *
 * <p>坐标以<b>输入大框左上角</b>为基准（面板内坐标），标签框固定画在它左边 48px 处
 * （和原来背景里的排版一致）。</p>
 */
public final class SpriteField {

    /** 标签小框在 field 图集里的位置与尺寸 */
    private static final int LABEL_U = 0;
    private static final int LABEL_V = 0;
    private static final int LABEL_W = 42;
    private static final int LABEL_H = 17;
    /** 输入大框在 field 图集里的位置与尺寸 */
    private static final int BOX_U = 48;
    private static final int BOX_V = 0;
    private static final int BOX_W = 91;
    private static final int BOX_H = 17;
    /** 标签框相对输入大框的偏移（沿用原背景排版） */
    private static final int LABEL_DX = -48;
    private static final int LABEL_DY = 0;
    /** 文本区相对输入大框左上角 */
    private static final int TEXT_DX = 3;
    private static final int TEXT_DY = 4;
    private static final int TEXT_W = 88;
    private static final int TEXT_H = 14;

    private final NumericField field;
    private final int panelX;
    private final int panelY;
    private final int x;
    private final int y;

    /**
     * @param font     字体
     * @param panelX   面板原点 X（EditBox 需要绝对屏幕坐标，所以这里一起收进来）
     * @param panelY   面板原点 Y
     * @param x        输入大框左上角 X（<b>面板内</b>坐标）
     * @param y        输入大框左上角 Y（<b>面板内</b>坐标）
     * @param signed   是否允许负数
     * @param onChange 文本能解析成数字时的回调
     */
    public SpriteField(Font font, int panelX, int panelY, int x, int y, boolean signed, DoubleConsumer onChange) {
        this.panelX = panelX;
        this.panelY = panelY;
        this.x = x;
        this.y = y;
        this.field = new NumericField(font, panelX + x + TEXT_DX, panelY + y + TEXT_DY,
                TEXT_W, TEXT_H, signed, onChange);
    }

    /** 底层输入框，交给 {@code addRenderableWidget} 注册（文本最后画，盖在框上） */
    public EditBox widget() {
        return this.field.widget();
    }

    /** 解析当前文本；空/非法返回 fallback */
    public double value(double fallback) {
        return this.field.value(fallback);
    }

    /** 未聚焦时把显示刷成两位小数 */
    public void syncDisplay(double value) {
        this.field.syncDisplay(value);
    }

    /** 画框（标签小框 + 输入大框），在面板底图之后、按钮之前调用 */
    public void render(GuiGraphics graphics) {
        GuiStyle.drawFieldSprite(graphics, this.panelX + this.x + LABEL_DX, this.panelY + this.y + LABEL_DY,
                LABEL_U, LABEL_V, LABEL_W, LABEL_H);
        GuiStyle.drawFieldSprite(graphics, this.panelX + this.x, this.panelY + this.y, BOX_U, BOX_V, BOX_W, BOX_H);
    }
}
