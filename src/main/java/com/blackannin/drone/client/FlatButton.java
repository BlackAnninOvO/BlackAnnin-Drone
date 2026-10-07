package com.blackannin.drone.client;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

/** 操控台扁平按钮：描边 + 悬停高亮；active=false 时置灰且不可点击；toggled 为选中常亮态（如自动运镜开启）。 */
final class FlatButton {
    private static final int EDGE = 0xFF1E2C38;
    private static final int ACCENT = 0xFF00D9FF;
    private static final int TEXT = 0xFFDDEEFF;
    private static final int DISABLED = 0xFF5A6A78;

    final int x, y, w, h;
    private final Runnable onClick;
    private Component label;
    private boolean active = true;
    private boolean toggled;

    FlatButton(int x, int y, int w, int h, String key, Runnable onClick) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
        this.label = key.isEmpty() ? Component.empty() : Component.translatable(key);
        this.onClick = onClick;
    }

    void setLabel(Component label) {
        this.label = label;
    }

    void setActive(boolean active) {
        this.active = active;
    }

    void setToggled(boolean toggled) {
        this.toggled = toggled;
    }

    void render(GuiGraphics g, Font font, int mouseX, int mouseY) {
        boolean hover = active && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        g.fill(x, y, x + w, y + h, toggled ? 0xFF0E3A4A : hover ? 0xFF16323F : 0xFF101A22);
        g.renderOutline(x, y, w, h, toggled || hover ? ACCENT : EDGE);
        int labelColor = !active ? DISABLED : toggled || hover ? ACCENT : TEXT;
        g.drawCenteredString(font, label, x + w / 2, y + (h - 8) / 2, labelColor);
    }

    boolean click(double mouseX, double mouseY, int button) {
        if (active && button == 0 && mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
            onClick.run();
            return true;
        }
        return false;
    }
}
