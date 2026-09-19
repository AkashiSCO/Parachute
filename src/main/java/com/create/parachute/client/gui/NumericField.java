package com.create.parachute.client.gui;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.DoubleConsumer;

/**
 * 数字输入框：把两个控制器界面里重复了 6 遍的写法（无边框 EditBox + 数字过滤器 + 改动回调 +
 * 未聚焦时回填两位小数）收成一处。
 *
 * <p>过滤器规则与原实现一致：只允许数字、最多一个小数点；{@code signed} 时额外允许开头一个负号。
 * 解析统一用 {@link Double#parseDouble}（只认 {@code .}），所以回填也用 {@link Locale#ROOT}
 * 格式化——否则在逗号小数点的语言环境下，显示出来的是 {@code 1,23}，过滤器会把它当非法字符，
 * 数字就再也刷不进去了。</p>
 */
public final class NumericField {

    private static final String FORMAT = "%.2f";

    private final EditBox box;

    /**
     * @param signed   是否允许负数（旋转/枢轴/偏移要，阻力系数等不要）
     * @param onChange 文本能解析成数字时的回调
     */
    public NumericField(Font font, int x, int y, int width, int height, boolean signed, DoubleConsumer onChange) {
        this.box = new EditBox(font, x, y, width, height, Component.empty());
        this.box.setFilter(signed ? NumericField::isValidSigned : NumericField::isValidUnsigned);
        this.box.setResponder(text -> {
            if (text == null || text.isEmpty()) return;
            try {
                onChange.accept(Double.parseDouble(text));
            } catch (NumberFormatException ignored) {
                // 输入过程里的中间态（如 "-"、"1."）不算错，等能解析了再回调
            }
        });
        this.box.setBordered(false);
    }

    /** 底层控件，交给 {@code addRenderableWidget} 注册 */
    public EditBox widget() {
        return this.box;
    }

    /** 解析当前文本；空或非法时返回 {@code fallback} */
    public double value(double fallback) {
        String text = this.box.getValue();
        if (text == null || text.isEmpty()) return fallback;
        try {
            return Double.parseDouble(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 未聚焦时把显示同步成两位小数（聚焦时不动，避免打断输入） */
    public void syncDisplay(double value) {
        if (this.box.isFocused()) return;
        String text = String.format(Locale.ROOT, FORMAT, value);
        if (!this.box.getValue().equals(text)) {
            this.box.setValue(text);
        }
    }

    private static boolean isValidUnsigned(String text) {
        return isValid(text, false);
    }

    private static boolean isValidSigned(String text) {
        return isValid(text, true);
    }

    private static boolean isValid(String text, boolean signed) {
        if (text == null || text.isEmpty()) return true;
        int dots = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (signed && c == '-') {
                if (i != 0) return false;
            } else if (c == '.') {
                if (++dots > 1) return false;
            } else if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }
}
