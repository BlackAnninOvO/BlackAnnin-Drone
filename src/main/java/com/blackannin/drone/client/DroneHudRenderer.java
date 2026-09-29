package com.blackannin.drone.client;

import com.blackannin.drone.entity.DroneEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;

/**
 * 操控台遥测绘制：罗盘刻度带、人工地平仪与右侧读数。
 * 无状态（全部为静态方法，仅读取无人机快照），便于单独维护与复用。
 */
final class DroneHudRenderer {
    private static final int ACCENT = 0xFF00D9FF;
    private static final int TEXT = 0xFFDDEEFF;
    private static final int DIM = 0xFF8FA3B8;
    private static final int OK = 0xFF3DE08A;
    private static final int PANEL_EDGE = 0xFF1E2C38;
    /** 罗盘刻度带显示的角度跨度（度） */
    private static final int SPAN_DEGREES = 120;

    private DroneHudRenderer() {
    }

    /**
     * 罗盘刻度带：以当前航向为中心显示 ±60° 的刻度，N/E/S/W 为主刻度。
     *
     * <p>航向采用世界罗盘约定：0°=北、90°=东、180°=南、270°=西。
     * Minecraft 偏航与世界方向的换算为 yaw 0°=南、90°=西、±180°=北、-90°=东，
     * 故 航向 = yaw + 180°（曾被写成 -yaw + 180°，导致面朝西显示 E）。</p>
     */
    static void compass(GuiGraphics g, net.minecraft.client.gui.Font font, DroneEntity drone, int x, int y, int w) {
        float yaw = drone != null ? drone.getYRot() : 0.0F;
        float heading = Mth.wrapDegrees(yaw + 180.0F);
        int cx = x + w / 2;
        double scale = w / (double) SPAN_DEGREES;
        g.fill(x, y + 12, x + w, y + 13, PANEL_EDGE);
        // 刻度锚定在 15° 的绝对网格上，窗口随航向滑动（固定窗口会在面朝南时半幅空白）
        int base = Math.round(heading / 15.0F) * 15;
        for (int k = -4; k <= 4; k++) {
            int deg = base + k * 15;
            int lx = cx + (int) ((deg - heading) * scale);
            if (lx < x + 2 || lx > x + w - 2) {
                continue;
            }
            int norm = ((deg % 360) + 360) % 360;
            boolean major = norm % 90 == 0;
            g.fill(lx, y + (major ? 6 : 9), lx + 1, y + 13, major ? TEXT : DIM);
            if (major) {
                String label = switch (norm) {
                    case 0 -> "N";
                    case 90 -> "E";
                    case 180 -> "S";
                    default -> "W";
                };
                g.drawCenteredString(font, label, Mth.clamp(lx, x + 6, x + w - 6), y, TEXT);
            }
        }
        g.fill(cx - 1, y + 2, cx + 1, y + 15, ACCENT);
    }

    /** 人工地平线：低头时机头压向地面，地平线在仪中上移；抬头反之 */
    static void horizon(GuiGraphics g, net.minecraft.client.gui.Font font, DroneEntity drone, int x, int y, int w, int h) {
        int midY = y + h / 2;
        float pitch = drone != null ? drone.getXRot() : 0.0F;
        int horizonY = Mth.clamp(midY - (int) (pitch * 0.8F), y + 4, y + h - 4);
        g.fill(x, y, x + w, horizonY, 0xFF1B4F72);
        g.fill(x, horizonY, x + w, y + h, 0xFF5C4023);
        g.fill(x, horizonY - 1, x + w, horizonY + 1, ACCENT);
        // 中央准星
        g.fill(x + w / 2 - 8, midY, x + w / 2 - 3, midY + 1, TEXT);
        g.fill(x + w / 2 + 4, midY, x + w / 2 + 9, midY + 1, TEXT);
        g.fill(x + w / 2, midY - 8, x + w / 2 + 1, midY - 3, TEXT);
        g.fill(x + w / 2, midY + 4, x + w / 2 + 1, midY + 9, TEXT);
        g.renderOutline(x, y, w, h, PANEL_EDGE);
        // 俯仰读数放地平仪内部左下角，避免与底部按键提示行重叠
        g.drawString(font, String.format("PITCH %.0f°", -pitch), x + 4, y + h - 11, 0xFF9FB8C8);
    }

    /** 右侧遥测：中英文标签 + 数值右对齐 */
    static void telemetry(GuiGraphics g, net.minecraft.client.gui.Font font, DroneEntity drone, int x, int y, int valueRight) {
        Minecraft mc = Minecraft.getInstance();
        boolean online = drone != null;
        int row = 0;
        String mode = online && drone.isManuallyControlled()
                ? Component.translatable("gui.blackannin_drone.drone_control.val_manual").getString()
                : Component.translatable("gui.blackannin_drone.drone_control.val_follow").getString();
        int modeColor = online && drone.isManuallyControlled() ? ACCENT : OK;
        row(g, font, x, y, row++, "tel_mode", mode, modeColor, valueRight);
        Vec3 p = online ? drone.position() : Vec3.ZERO;
        row(g, font, x, y, row++, "tel_alt", online ? String.format("%.1f m", p.y) : "--", TEXT, valueRight);
        double dist = online ? drone.distanceTo(mc.player) : 0;
        row(g, font, x, y, row++, "tel_dist", online ? String.format("%.1f m", dist) : "--", TEXT, valueRight);
        double hSpd = online ? Math.sqrt(drone.getDeltaMovement().x * drone.getDeltaMovement().x
                + drone.getDeltaMovement().z * drone.getDeltaMovement().z) * 20 : 0;
        row(g, font, x, y, row++, "tel_hspd", online ? String.format("%.1f m/s", hSpd) : "--", TEXT, valueRight);
        double vSpd = online ? drone.getDeltaMovement().y * 20 : 0;
        row(g, font, x, y, row++, "tel_vspd", online ? String.format("%+.1f m/s", vSpd) : "--", TEXT, valueRight);
        int hdg = online ? Math.round(((Mth.wrapDegrees(drone.getYRot() + 180.0F)) + 360.0F) % 360.0F) : 0;
        row(g, font, x, y, row++, "tel_hdg", online ? String.format("%d°", hdg) : "--", TEXT, valueRight);
        row(g, font, x, y, row++, "tel_pos", online ? String.format("%d %d %d",
                (int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z)) : "--", TEXT, valueRight);
    }

    private static void row(GuiGraphics g, net.minecraft.client.gui.Font font, int x, int y, int row, String labelKey, String value, int color, int valueRight) {
        int yy = y + row * 11;
        g.drawString(font, Component.translatable("gui.blackannin_drone.drone_control." + labelKey), x, yy, DIM);
        g.drawString(font, value, valueRight - font.width(value), yy, color);
    }

}
