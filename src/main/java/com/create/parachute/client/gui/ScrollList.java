package com.create.parachute.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * 选伞界面的滚动列表 + 滑块：把这一整块自定义交互（点击选行、拖滑块、滚轮、把行裁在框内）
 * 从界面类里搬出来，界面类只剩"要显示哪些名字"和"选中了谁"。
 *
 * <p>几何全部沿用原来的 controller_2 贴图坐标（面板内坐标）：列表 x 18..161、y 20..92，
 * 每行 17px；滑块轨道 x 171、y 18、高 76，滑块 5×20。行不够一屏时 {@code maxScroll()} 为 0，
 * 滑块停在顶端且拖不动。</p>
 */
public final class ScrollList {

    /** 列表区域 */
    private static final int LIST_X = 18;
    private static final int LIST_W = 143;
    private static final int LIST_TOP = 20;
    private static final int LIST_BOTTOM = 92;
    private static final int VIEW_H = LIST_BOTTOM - LIST_TOP;
    /** 每行（每把伞一个显示框）的高度 */
    private static final int ROW_H = 17;
    /** 行显示框图形（button 贴图内），选中态用 BOX_HL_V */
    private static final int BOX_U = 18;
    private static final int BOX_V = 46;
    private static final int BOX_UW = 143;
    private static final int BOX_VH = 17;
    private static final int BOX_HL_V = 64;
    /** 滑块轨道与滑块图形 */
    private static final int TRACK_X = 171;
    private static final int TRACK_Y = 18;
    private static final int TRACK_H = 76;
    private static final int SLIDER_U = 0;
    private static final int SLIDER_V = 0;
    private static final int SLIDER_W = 5;
    private static final int SLIDER_H = 20;
    /** 列表热加载检查间隔（毫秒） */
    private static final long REFRESH_INTERVAL_MS = 500L;

    private final List<String> rows = new ArrayList<>();
    private final Runnable selectionSound;
    private String selected = "";
    private int scrollOffset;
    private boolean draggingSlider;
    private long lastRefresh;

    /**
     * @param selectionSound 点击某一行选中时的提示音（滑块拖动不响，与原实现一致）
     */
    public ScrollList(Runnable selectionSound) {
        this.selectionSound = selectionSound;
    }

    /** 只读的行列表 */
    public List<String> rows() {
        return Collections.unmodifiableList(this.rows);
    }

    public String selected() {
        return this.selected;
    }

    public boolean contains(String id) {
        return this.rows.contains(id);
    }

    /** 直接设置选中项（界面初始化、或外部校验后回退时用） */
    public void select(String id) {
        this.selected = id == null ? "" : id;
    }

    /** 全量替换行列表，然后保证选中项有效并滚动到它 */
    public void reload(List<String> fresh, String preferred) {
        this.rows.clear();
        this.rows.addAll(fresh);
        ensureSelected(preferred);
    }

    /**
     * 热加载：每 {@value #REFRESH_INTERVAL_MS} ms 检查一次数据源，变了就替换并保持选中。
     *
     * @return 列表是否发生了变化
     */
    public boolean refreshIfChanged(Supplier<List<String>> source, String preferred) {
        long now = System.currentTimeMillis();
        if (now - this.lastRefresh <= REFRESH_INTERVAL_MS) return false;
        this.lastRefresh = now;
        List<String> fresh = source.get();
        if (fresh.equals(this.rows)) return false;
        reload(fresh, preferred);
        return true;
    }

    /** 选中项不在列表里时回退到 preferred（再不行就第一条），并滚动到它 */
    public void ensureSelected(String preferred) {
        if (!this.rows.contains(this.selected)) {
            this.selected = this.rows.contains(preferred) ? preferred
                    : (this.rows.isEmpty() ? "" : this.rows.get(0));
        }
        scrollToSelection();
    }

    /** 让选中行居中显示 */
    public void scrollToSelection() {
        int idx = this.rows.indexOf(this.selected);
        if (idx < 0) idx = 0;
        int target = idx * ROW_H + ROW_H / 2 - VIEW_H / 2;
        this.scrollOffset = Math.max(0, Math.min(maxScroll(), target));
    }

    private int maxScroll() {
        return Math.max(0, this.rows.size() * ROW_H - VIEW_H);
    }

    // ============================================================
    // 输入
    // ============================================================

    /** 点在行上 → 选中；点在滑块轨道上 → 开始拖拽。命中返回 true */
    public boolean mouseClicked(double mouseX, double mouseY, int panelX, int panelY) {
        int listTop = panelY + LIST_TOP;
        for (int i = 0; i < this.rows.size(); i++) {
            int rowY = listTop + i * ROW_H - this.scrollOffset;
            if (rowY + ROW_H < listTop || rowY >= listTop + VIEW_H) continue;
            if (GuiStyle.isInRect(mouseX, mouseY, panelX + LIST_X, rowY, LIST_W, ROW_H)) {
                this.selected = this.rows.get(i);
                this.selectionSound.run();
                return true;
            }
        }
        if (GuiStyle.isInRect(mouseX, mouseY, panelX + TRACK_X - 3, panelY + TRACK_Y, SLIDER_W + 6, TRACK_H)) {
            this.draggingSlider = true;
            updateScrollFromMouse(mouseY, panelY);
            return true;
        }
        return false;
    }

    /** 拖拽滑块，命中返回 true */
    public boolean mouseDragged(double mouseY, int panelY) {
        if (!this.draggingSlider) return false;
        updateScrollFromMouse(mouseY, panelY);
        return true;
    }

    /** 松手（无论按的是什么，拖拽状态都要清掉） */
    public void mouseReleased() {
        this.draggingSlider = false;
    }

    /** 滚轮：鼠标在面板区域内即可滚动（每格一行） */
    public boolean mouseScrolled(double mouseX, double mouseY, double deltaY, int panelX, int panelY) {
        boolean overPanel = mouseX >= panelX + 1 && mouseX < panelX + 177
                && mouseY >= panelY + 16 && mouseY < panelY + 97;
        if (!overPanel) return false;
        this.scrollOffset = Math.max(0, Math.min(maxScroll(),
                this.scrollOffset - (int) (deltaY * ROW_H)));
        return true;
    }

    private void updateScrollFromMouse(double mouseY, int panelY) {
        double travel = TRACK_H - SLIDER_H;
        double t = clamp((mouseY - (panelY + TRACK_Y + SLIDER_H / 2.0D)) / travel);
        this.scrollOffset = (int) Math.round(t * maxScroll());
    }

    // ============================================================
    // 绘制
    // ============================================================

    /**
     * 画列表：底板 → 行（裁剪在蓝框内）→ 盖住越界行的上下底图条 → 滑块。
     *
     * @param label 行文本映射（选伞界面用它在"本地兜底"的条目后面加来源标记）
     */
    public void render(GuiGraphics graphics, int panelX, int panelY, Font font,
                       ResourceLocation background, Function<String, String> label) {
        // 图层1：列表区域底板
        graphics.blit(background, panelX, panelY + LIST_TOP, 0, LIST_TOP,
                GuiStyle.PANEL_W, VIEW_H, GuiStyle.PANEL_W, GuiStyle.PANEL_H);

        // 图层2：每行一个显示框 + 文本，严格裁在列表范围内
        graphics.enableScissor(panelX + LIST_X, panelY + LIST_TOP,
                panelX + LIST_X + LIST_W, panelY + LIST_TOP + VIEW_H);
        for (int i = 0; i < this.rows.size(); i++) {
            int rowY = panelY + LIST_TOP + i * ROW_H - this.scrollOffset;
            if (rowY + ROW_H < panelY + LIST_TOP || rowY >= panelY + LIST_BOTTOM) continue;

            boolean isSelected = this.rows.get(i).equals(this.selected);
            int boxV = isSelected ? BOX_HL_V : BOX_V;
            graphics.blit(GuiStyle.BUTTON_TEXTURE, panelX + LIST_X, rowY,
                    BOX_U, boxV, BOX_UW, BOX_VH, GuiStyle.TEXTURE_W, GuiStyle.TEXTURE_H);

            String text = GuiStyle.truncate(font, label.apply(this.rows.get(i)), LIST_W - 10);
            graphics.drawCenteredString(font, text, panelX + LIST_X + LIST_W / 2,
                    rowY + (ROW_H - 9) / 2, isSelected ? 0xFFFFFFFF : 0xFFE8E8E8);
        }
        // 文字是缓冲渲染：必须先冲刷（此时 scissor 仍生效），再关 scissor，否则文字会漏到边界外
        graphics.flush();
        graphics.disableScissor();

        // 图层3：上下底图条，盖住滚动越界的显示框
        graphics.blit(background, panelX, panelY, 0, 0,
                GuiStyle.PANEL_W, LIST_TOP, GuiStyle.PANEL_W, GuiStyle.PANEL_H);
        graphics.blit(background, panelX, panelY + LIST_BOTTOM, 0, LIST_BOTTOM,
                GuiStyle.PANEL_W, GuiStyle.PANEL_H - LIST_BOTTOM, GuiStyle.PANEL_W, GuiStyle.PANEL_H);

        // 滑块
        int knobY = TRACK_Y + (int) Math.round(sliderT() * (TRACK_H - SLIDER_H));
        graphics.blit(GuiStyle.BUTTON_TEXTURE, panelX + TRACK_X, panelY + knobY,
                SLIDER_U, SLIDER_V, SLIDER_W, SLIDER_H, GuiStyle.TEXTURE_W, GuiStyle.TEXTURE_H);
    }

    /** 滑块位置 0..1 */
    private double sliderT() {
        int max = maxScroll();
        return max <= 0 ? 0.0D : (double) this.scrollOffset / (double) max;
    }

    private static double clamp(double v) {
        return v < 0.0D ? 0.0D : (v > 1.0D ? 1.0D : v);
    }
}
