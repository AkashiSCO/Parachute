package com.create.parachute.client.gui;

import com.create.parachute.ParachuteMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 三个伞界面（控制器 / 变换设置 / 选伞）共用的面板约定与绘制工具。
 *
 * <p>所有界面都是同一张 {@value #PANEL_W}×{@value #PANEL_H} 的贴图居中显示，按钮的状态图统一来自
 * {@link #BUTTON_TEXTURE}，文字统一用 {@link #LABEL_COLOR}（注意必须带 FF alpha，否则整段文字透明）。
 * 把尺寸、命中判定、点击音、文字绘制集中在这里，界面类就只剩各自的业务逻辑。</p>
 */
public final class GuiStyle {

    /** 面板贴图尺寸（三个界面一致） */
    public static final int PANEL_W = 228;
    public static final int PANEL_H = 128;
    /** 按钮状态图集尺寸 */
    public static final int TEXTURE_W = 256;
    public static final int TEXTURE_H = 128;
    /** 面板文字颜色（不透明） */
    public static final int LABEL_COLOR = 0xFFE2E2E2;
    /** 按钮状态图集（悬停 v=0、按下 v=18、模式键图标 v=92/111 都在这一张里） */
    public static final ResourceLocation BUTTON_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(ParachuteMod.MOD_ID, "textures/gui/parachute_controller_button.png");
    /**
     * 输入框精灵图集：从 controller_3 背景里抠出来的独立贴图，这样输入框是"图层"而不是背景的一部分，
     * 想画几个、画在哪都行。布局：标签小框 (0,0,42,17)、输入大框 (48,0,91,17)。
     */
    public static final ResourceLocation FIELD_TEXTURE =
            ResourceLocation.fromNamespaceAndPath(ParachuteMod.MOD_ID, "textures/gui/parachute_controller_field.png");
    /** 输入框精灵图集尺寸 */
    public static final int FIELD_TEX_W = 144;
    public static final int FIELD_TEX_H = 32;

    // ---- 新增按键的位置（controller_new 与 controller_3 两个背景里坐标完全一致）----
    /**
     * 锁定键（标题栏右侧，和伞包键并排的一对）。
     * 背景里是绿框的"未锁定"挂锁，"已锁定"的红色图标在按钮图集 (241,0,15,16)。
     */
    public static final int LOCK_BTN_X = 182;
    public static final int LOCK_BTN_Y = 3;
    public static final int LOCK_BTN_W = 15;
    public static final int LOCK_BTN_H = 16;
    public static final int LOCK_ICON_U = 241;
    public static final int LOCK_ICON_V = 0;
    public static final int LOCK_ICON_W = 15;
    public static final int LOCK_ICON_H = 16;
    /**
     * 红键：显示/隐藏伞包方块自身模型，与锁定键完全对称——同样 15×16、同样 y=3，
     * 状态图标在按钮图集 (241,16,15,16)（带红斜杠的伞 = 已隐藏）。
     */
    public static final int PACK_BTN_X = 202;
    public static final int PACK_BTN_Y = 3;
    public static final int PACK_BTN_W = 15;
    public static final int PACK_BTN_H = 16;
    public static final int PACK_ICON_U = 241;
    public static final int PACK_ICON_V = 16;
    public static final int PACK_ICON_W = 15;
    public static final int PACK_ICON_H = 16;
    /** 灰键：进入渲染缩放界面（在 R 键右侧），悬停/按下图 (55,92)/(55,111)，17×17 且右移 1px */
    public static final int SCALE_BTN_X = 200;
    public static final int SCALE_BTN_Y = 23;
    public static final int SCALE_BTN_W = 18;
    public static final int SCALE_BTN_H = 18;
    public static final int SCALE_STATE_U = 55;
    public static final int SCALE_STATE_V = 92;
    public static final int SCALE_STATE_V_PRESSED = 111;
    public static final int SCALE_STATE_W = 17;
    public static final int SCALE_STATE_H = 17;
    /** 输入框所在行（面板内坐标，输入大框左上角） */
    public static final int FIELD_BOX_X = 69;
    public static final int[] FIELD_BOX_YS = {24, 48, 72};

    private GuiStyle() {
    }

    /** {@code textures/gui/<name>.png} */
    public static ResourceLocation texture(String name) {
        return ResourceLocation.fromNamespaceAndPath(ParachuteMod.MOD_ID, "textures/gui/" + name + ".png");
    }

    /** 面板左上角 X（居中） */
    public static int panelX(int screenWidth) {
        return (screenWidth - PANEL_W) / 2;
    }

    /** 面板左上角 Y（居中） */
    public static int panelY(int screenHeight) {
        return (screenHeight - PANEL_H) / 2;
    }

    /** 左闭右开的矩形命中判定 */
    public static boolean isInRect(double mouseX, double mouseY, int x, int y, int w, int h) {
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /** 画面板底图 */
    public static void drawPanel(GuiGraphics graphics, ResourceLocation background, int panelX, int panelY) {
        graphics.blit(background, panelX, panelY, 0, 0, PANEL_W, PANEL_H, PANEL_W, PANEL_H);
    }

    /** 按 {@link TextureButton.Art} 从按钮图集里画一块状态图 */
    public static void drawArt(GuiGraphics graphics, int x, int y, TextureButton.Art art) {
        graphics.blit(BUTTON_TEXTURE, x, y, art.u(), art.v(), art.w(), art.h(), TEXTURE_W, TEXTURE_H);
    }

    /** 从输入框图集里画一块精灵（标签小框 / 输入大框） */
    public static void drawFieldSprite(GuiGraphics graphics, int x, int y, int u, int v, int w, int h) {
        graphics.blit(FIELD_TEXTURE, x, y, u, v, w, h, FIELD_TEX_W, FIELD_TEX_H);
    }

    /**
     * 把面板 y=45..96 这段抹平成背景自己的纹理。
     *
     * <p>缩放模式只用第一行输入框，而背景里画了三行框；面板内部的底纹是 1px 棋盘（周期 2），
     * 所以从 y=41..46 这段干净背景按 6 行（偶数，相位不变）一块块盖过去即可无缝。
     * <b>上界必须停在 96</b>：底部灰条的顶线在 y=97/98，盖过就少一条线。
     * 等背景图里多余的框被擦掉后，这个方法可以直接删。</p>
     */
    public static void blankFieldRows(GuiGraphics graphics, ResourceLocation background, int panelX, int panelY) {
        for (int y = 45; y < 97; y += 6) {
            int height = Math.min(6, 97 - y);
            graphics.blit(background, panelX + 18, panelY + y, 18, 41, 150, height, PANEL_W, PANEL_H);
        }
    }

    /** 面板文字（统一颜色） */
    public static void drawLabel(GuiGraphics graphics, Font font, Component text, int x, int y) {
        graphics.drawString(font, text, x, y, LABEL_COLOR, false);
    }

    /** 面板文字（统一颜色） */
    public static void drawLabel(GuiGraphics graphics, Font font, String text, int x, int y) {
        graphics.drawString(font, text, x, y, LABEL_COLOR, false);
    }

    /** 按像素宽度截断并加省略号 */
    public static String truncate(Font font, String text, int maxWidth) {
        return font.width(text) > maxWidth ? font.plainSubstrByWidth(text, maxWidth) + "..." : text;
    }

    /**
     * 手动渲染 {@code addRenderableWidget} 加进来的控件。
     * <p>这几个界面都不调用 {@code super.render}（绘制顺序要自己排），所以控件要显式刷一遍。</p>
     */
    public static void renderWidgets(GuiGraphics graphics, List<Renderable> widgets,
                                     int mouseX, int mouseY, float partialTick) {
        for (Renderable renderable : widgets) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    /** 标准 UI 点击音 */
    public static void playClick(@Nullable Minecraft minecraft) {
        if (minecraft != null && minecraft.getSoundManager() != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
        }
    }
}
