package com.blackannin.drone.client;

import com.blackannin.drone.BlackAnninsDrone;
import com.blackannin.drone.ClientConfig;
import com.blackannin.drone.entity.DroneEntity;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL45;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * 推流画面 OSD 叠加层：仅出现在发给 OBS 的推流帧上，玩家自己的屏幕永远看不到。
 *
 * <p>两种显示模式（{@code spoutOsdPro} 实时切换）：
 * <b>CLEAN（纯净）</b>——底部遥测条 + 小准星；
 * <b>PRO（专业）</b>——航向刻度带、高度刻度尺、人工地平线、Home 方向指示、
 * 坐标/俯仰/速度/模式分布全屏 + 加粗放大十字准星，布局参照真实无人机 FPV 界面。</p>
 *
 * <p>实现为纯 CPU 像素合成：把 saveTarget 纹理下载到 {@link NativeImage}，
 * 用 {@link OsdFont} 位图字体与矩形 alpha 混合后上传。刻意不使用 font.drawInBatch /
 * Tesselator / BufferUploader——共享 bufferSource 的 flush 时机不可控，会把内容
 * 泄漏到玩家画面（见 AI_PROMPT 附录 A 参考 2）；像素合成在结构上不可能污染玩家画面。</p>
 *
 * <p>绑定管理：刻意用原始 GL 调用（glActiveTexture + glBindTexture）并记录/还原原状态，
 * 不经过 GlStateManager 的缓存——Iris/Sodium 存在绕过缓存的原始绑定，缓存可能失真。
 * 每次合成后用 DSA（glGetTextureSubImage）读回一枚探针像素自校验，
 * 并做 glGetError 巡检；任何异常只跳过本次 OSD，不影响推流。</p>
 */
final class StreamOsd {
    /** 推流帧内实际画面区域（blitToSave 等比缩放后的位置与尺寸） */
    record PictureRect(int x, int y, int w, int h) {
    }

    /** 与操控台一致的配色（ARGB；DIM/BAR_BG 自带半透明） */
    private static final int ACCENT = 0xFF00D9FF;
    private static final int TEXT = 0xFFDDEEFF;
    private static final int DIM = 0xC08FA3B8;
    private static final int BAR_BG = 0x96060A0E;
    private static final int REC_RED = 0xFFFF4040;
    private static final int OK_GREEN = 0xFF3DE08A;
    private static final int WARN = 0xFFFFB13D;
    private static final int RETICLE = 0xD000D9FF;
    private static final int HORIZON = 0xB0DDEEFF;

    private static int errorCooldown;
    private static boolean verifyLogged;
    /** 推流开始时刻（PRO 模式 REC 计时用），世界卸载/关闭推流时归零 */
    private static long recStartMillis;

    private StreamOsd() {
    }

    /** OSD 缩放档位（与 draw/verify 共用，保证探针位置一致） */
    private static int osdScale(PictureRect pic) {
        return Mth.clamp(pic.h() / 270, 2, 5);
    }

    /**
     * 在推流帧上叠加 OSD：绑定纹理 → 下载 → 逐像素混合 → 上传 → 自校验。
     * 必须在 blitToSave 之后、SpoutSender.send 之前调用（渲染线程持有 GL 上下文）。
     */
    static void apply(Minecraft mc, RenderTarget target, PictureRect pic, DroneEntity drone) {
        if (drone == null || pic.w() <= 0 || pic.h() <= 0) {
            return;
        }
        if (errorCooldown > 0) {
            errorCooldown--;
        }
        if (recStartMillis == 0) {
            recStartMillis = Util.getMillis();
        }
        NativeImage frame = null;
        try (MemoryStack stack = MemoryStack.stackPush()) {
            // 显式原始绑定：强制纹理单元 0 并直接绑定 saveTarget 纹理，结束后还原原状态。
            // 不用 GlStateManager._bindTexture：其缓存在 Iris/Sodium 绕过式绑定下可能失真
            int prevActive = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            int prevTexture = GL11.glGetInteger(GL13.GL_TEXTURE_BINDING_2D);
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, target.getColorTextureId());
            try {
                frame = new NativeImage(target.width, target.height, false);
                frame.downloadTexture(0, false);
                draw(mc, frame, pic, drone, Util.getMillis());
                frame.upload(0, 0, 0, false);
                verifyUploaded(target.getColorTextureId(), pic, stack);
                checkGlError();
            } finally {
                GL11.glBindTexture(GL11.GL_TEXTURE_2D, prevTexture);
                GL13.glActiveTexture(prevActive);
            }
        } catch (Throwable t) {
            if (errorCooldown <= 0) {
                BlackAnninsDrone.LOGGER.warn("推流 OSD 绘制失败，本帧跳过: {}", t.toString());
                errorCooldown = 600;
            }
        } finally {
            if (frame != null) {
                frame.close();
            }
        }
    }

    /** 重置录制计时（世界卸载/关闭推流时由渲染器调用） */
    static void reset() {
        recStartMillis = 0;
    }

    /**
     * 上传后自校验：用 DSA 直接按纹理 ID 读回探针像素（画面底部遥测条的青色分隔线，
     * alpha=255 纯色，不参与混合，可精确比对）。每次会话只记录一次结果，避免刷日志。
     */
    private static void verifyUploaded(int texId, PictureRect pic, MemoryStack stack) {
        // 探针是 CLEAN 模式底部遥测条的青色分隔线；PRO 模式无该元素，跳过自校验
        if (verifyLogged || ClientConfig.SPOUT_OSD_PRO.get()) {
            return;
        }
        verifyLogged = true;
        int scale = osdScale(pic);
        int barY = pic.y() + pic.h() - 11 * scale;
        int probeX = pic.x() + pic.w() / 2;
        int probeY = barY - 1;
        ByteBuffer buf = stack.malloc(4);
        GL45.glGetTextureSubImage(texId, 0, probeX, probeY, 0, 1, 1, 1,
                GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, buf);
        int r = buf.get(0) & 0xFF;
        int g = buf.get(1) & 0xFF;
        int b = buf.get(2) & 0xFF;
        int a = buf.get(3) & 0xFF;
        if (r == 0x00 && g == 0xD9 && b == 0xFF && a == 0xFF) {
            BlackAnninsDrone.LOGGER.info("OSD 自校验通过: 探针 ({}, {}) 为纯青色，已写入推流帧", probeX, probeY);
        } else {
            BlackAnninsDrone.LOGGER.warn("OSD 自校验异常: 探针 ({}, {}) 读回 R={} G={} B={} A={}，预期纯青 (0,217,255)",
                    probeX, probeY, r, g, b, a);
        }
    }

    /** GL 错误巡检：有错误则记录（带冷却），避免刷日志 */
    private static void checkGlError() {
        int err = GL11.glGetError();
        if (err != GL11.GL_NO_ERROR && errorCooldown <= 0) {
            BlackAnninsDrone.LOGGER.warn("OSD 合成产生 GL 错误: 0x{}", Integer.toHexString(err));
            errorCooldown = 600;
        }
    }

    private static void draw(Minecraft mc, NativeImage img, PictureRect pic, DroneEntity drone, long millis) {
        int scale = osdScale(pic);
        boolean pro = ClientConfig.SPOUT_OSD_PRO.get();
        drawRec(img, pic, scale, millis, pro);
        if (pro) {
            drawPro(img, pic, scale, drone, mc);
        } else {
            drawCrosshair(img, pic, scale);
            drawTelemetryBar(mc, img, pic, scale, drone);
        }
    }

    /**
     * PRO（专业）模式：参照真实无人机 FPV 界面的全屏布局。
     * 顶部：REC 计时 / 航向刻度带 / 世界坐标；右缘：高度刻度尺；
     * 中部：人工地平线 + 加粗十字准星；左中：俯仰；底缘：速度 / Home / 模式。
     */
    private static void drawPro(NativeImage img, PictureRect pic, int scale, DroneEntity drone, Minecraft mc) {
        int m = 4 * scale;
        // 顶部（窄画面下省略最宽的航向刻度带与坐标块防重叠）
        if (pic.w() >= 640) {
            drawHeadingRibbon(img, pic, scale, drone);
            OsdFont.drawTextRight(img, pic.x() + pic.w() - m, pic.y() + 3 * scale, scale, posText(drone), TEXT);
        }
        // 右缘高度刻度尺
        drawAltitudeRibbon(img, pic, scale, drone);
        // 左中俯仰
        OsdFont.drawText(img, pic.x() + m, pic.y() + pic.h() / 2 - 7 * scale / 2, scale,
                "PITCH " + String.format(Locale.ROOT, "%+.1f", drone.getXRot()), TEXT);
        // 中部：人工地平线（先画，位于十字之下）+ 加粗十字准星
        drawHorizon(img, pic, scale, drone);
        drawCross(img, pic, scale);
        // 底缘：速度（左） / Home 方向与距离（中） / 模式（右）
        int baseY = pic.y() + pic.h() - m - 7 * scale;
        Vec3 v = drone.getDeltaMovement();
        double hSpd = Math.sqrt(v.x * v.x + v.z * v.z) * 20.0D;
        OsdFont.drawText(img, pic.x() + m, baseY - 8 * scale, scale,
                "V.SPD " + String.format(Locale.ROOT, "%+.1fM/S", v.y * 20.0D), TEXT);
        OsdFont.drawText(img, pic.x() + m, baseY, scale,
                "SPD " + String.format(Locale.ROOT, "%.1fM/S", hSpd), TEXT);
        drawHomeIndicator(img, pic, scale, drone, mc);
        OsdFont.drawTextRight(img, pic.x() + pic.w() - m, baseY, scale, "MODE " + modeText(drone), modeColor(drone));
    }

    private static String modeText(DroneEntity drone) {
        return drone.isManuallyControlled() ? "MANUAL" : drone.isCinematic() ? "CINEMA" : "FOLLOW";
    }

    private static int modeColor(DroneEntity drone) {
        return drone.isManuallyControlled() ? ACCENT : drone.isCinematic() ? WARN : OK_GREEN;
    }

    private static String posText(DroneEntity drone) {
        Vec3 p = drone.position();
        return String.format(Locale.ROOT, "POS %d %d %d",
                (int) Math.floor(p.x), (int) Math.floor(p.y), (int) Math.floor(p.z));
    }

    /** REC 指示：红点 1Hz 闪烁 + REC 字样；PRO 模式附录制时长 */
    private static void drawRec(NativeImage img, PictureRect pic, int scale, long millis, boolean pro) {
        int x = pic.x() + 3 * scale;
        int y = pic.y() + 3 * scale;
        if (millis / 500 % 2 == 0) {
            OsdFont.fillRect(img, x, y + 2 * scale, x + 3 * scale, y + 5 * scale, REC_RED);
        }
        String text = "REC";
        if (pro && recStartMillis > 0) {
            long secs = Mth.clamp((millis - recStartMillis) / 1000, 0, 99 * 60 + 59);
            text += String.format(Locale.ROOT, " %02d:%02d", secs / 60, secs % 60);
        }
        OsdFont.drawText(img, x + 4 * scale, y, scale, text, TEXT);
    }

    /** PRO 顶部航向刻度带：±45° 滑窗、15° 刻度、N/E/S/W 主刻度、中心读数 */
    private static void drawHeadingRibbon(NativeImage img, PictureRect pic, int scale, DroneEntity drone) {
        float heading = Mth.wrapDegrees(drone.getYRot() + 180.0F);
        int cx = pic.x() + pic.w() / 2;
        int ribbonW = Math.max(60 * scale, pic.w() / 4);
        int letterY = pic.y() + 2 * scale;
        int baseY = letterY + 10 * scale;
        int t = Math.max(1, scale / 2);
        double ppd = ribbonW / 90.0D;

        // 刻度带基线
        OsdFont.fillRect(img, cx - ribbonW / 2, baseY, cx + ribbonW / 2, baseY + t, DIM);
        // 15° 绝对网格刻度随航向滑动
        int base = Math.round(heading / 15.0F) * 15;
        for (int k = -3; k <= 3; k++) {
            int deg = base + k * 15;
            int x = cx + (int) ((deg - heading) * ppd);
            if (x < cx - ribbonW / 2 || x > cx + ribbonW / 2) {
                continue;
            }
            int norm = ((deg % 360) + 360) % 360;
            boolean major = norm % 90 == 0;
            OsdFont.fillRect(img, x, baseY - (major ? 3 * scale : 2 * scale), x + t, baseY, major ? TEXT : DIM);
            if (major) {
                String label = switch (norm) {
                    case 0 -> "N";
                    case 90 -> "E";
                    case 180 -> "S";
                    default -> "W";
                };
                OsdFont.drawTextCentered(img, x, letterY, scale, label, TEXT);
            }
        }
        // 中心指针与当前航向读数
        OsdFont.fillRect(img, cx - t, baseY + t, cx + t, baseY + t + 2 * scale, ACCENT);
        int hdg = ((Math.round(heading) % 360) + 360) % 360;
        OsdFont.drawTextCentered(img, cx, baseY + 3 * scale, scale, hdg + cardinal(hdg), ACCENT);
    }

    /** PRO 右缘高度刻度尺：±20m 滑窗、5m 刻度、10m 数值、中心当前高度读数 */
    private static void drawAltitudeRibbon(NativeImage img, PictureRect pic, int scale, DroneEntity drone) {
        float alt = (float) drone.getY();
        int cx = pic.x() + pic.w() / 2;
        int cy = pic.y() + pic.h() / 2;
        int x = pic.x() + pic.w() - 6 * scale;
        int top = pic.y() + (int) (pic.h() * 0.30D);
        int bot = pic.y() + (int) (pic.h() * 0.70D);
        int t = Math.max(1, scale / 2);
        double ppm = (bot - top) / 40.0D;

        OsdFont.fillRect(img, x, top, x + t, bot, DIM);
        int firstTick = (int) Math.ceil((alt - 20.0D) / 5.0D) * 5;
        for (int a = firstTick; a <= alt + 20.0D; a += 5) {
            int ty = (int) Math.round(cy - (a - alt) * ppm);
            if (ty < top || ty > bot - t) {
                continue;
            }
            boolean major = a % 10 == 0;
            OsdFont.fillRect(img, x - (major ? 3 : 2) * scale, ty, x, ty + t, major ? TEXT : DIM);
            if (major) {
                OsdFont.drawTextRight(img, x - 4 * scale, ty - 7 * scale / 2, scale, String.valueOf(a), DIM);
            }
        }
        OsdFont.drawTextRight(img, x - 4 * scale, cy - 7 * scale / 2, scale,
                String.format(Locale.ROOT, "%.1fM", alt), ACCENT);
    }

    /** PRO 人工地平线：随俯仰移动的水平参考线（低头时上移），两端带短垂 tick */
    private static void drawHorizon(NativeImage img, PictureRect pic, int scale, DroneEntity drone) {
        float pitch = drone.getXRot();
        int cx = pic.x() + pic.w() / 2;
        int cy = pic.y() + pic.h() / 2;
        int ladderW = (int) (pic.w() * 0.36D);
        int t = Math.max(1, scale / 2);
        double ppd = pic.h() / 60.0D;
        int hy = cy + (int) Mth.clamp(pitch * ppd, -pic.h() * 0.35D, pic.h() * 0.35D);

        OsdFont.fillRect(img, cx - ladderW / 2, hy, cx + ladderW / 2, hy + t, HORIZON);
        OsdFont.fillRect(img, cx - ladderW / 2, hy, cx - ladderW / 2 + t, hy + 3 * scale, HORIZON);
        OsdFont.fillRect(img, cx + ladderW / 2 - t, hy, cx + ladderW / 2, hy + 3 * scale, HORIZON);
    }

    /** PRO 加粗放大十字准星：厚臂 + 臂端刻度 + 中心点 */
    private static void drawCross(NativeImage img, PictureRect pic, int scale) {
        int cx = pic.x() + pic.w() / 2;
        int cy = pic.y() + pic.h() / 2;
        int t = 2 * scale;
        int gap = 6 * scale;
        int armV = (int) (pic.h() * 0.20D);
        int armH = (int) (pic.w() * 0.16D);
        int tick = 3 * scale;

        // 垂直臂（上/下）与臂端刻度
        OsdFont.fillRect(img, cx - t / 2, cy - gap - armV, cx - t / 2 + t, cy - gap, RETICLE);
        OsdFont.fillRect(img, cx - t / 2, cy + gap, cx - t / 2 + t, cy + gap + armV, RETICLE);
        OsdFont.fillRect(img, cx - tick, cy - gap - armV, cx + tick, cy - gap - armV + t, RETICLE);
        OsdFont.fillRect(img, cx - tick, cy + gap + armV - t, cx + tick, cy + gap + armV, RETICLE);
        // 水平臂（左/右）与臂端刻度
        OsdFont.fillRect(img, cx - gap - armH, cy - t / 2, cx - gap, cy - t / 2 + t, RETICLE);
        OsdFont.fillRect(img, cx + gap, cy - t / 2, cx + gap + armH, cy - t / 2 + t, RETICLE);
        OsdFont.fillRect(img, cx - gap - armH, cy - tick, cx - gap - armH + t, cy + tick, RETICLE);
        OsdFont.fillRect(img, cx + gap + armH - t, cy - tick, cx + gap + armH, cy + tick, RETICLE);
        // 中心点
        OsdFont.fillRect(img, cx - t / 2, cy - t / 2, cx - t / 2 + t, cy - t / 2 + t, RETICLE);
    }

    /** PRO 底部 Home 指示：指向玩家（Home 点）的 8 方向箭头 + 距离 */
    private static void drawHomeIndicator(NativeImage img, PictureRect pic, int scale, DroneEntity drone, Minecraft mc) {
        if (mc.player == null) {
            return;
        }
        Vec3 delta = mc.player.position().subtract(drone.position());
        double dist = delta.horizontalDistance();
        float bearingToPlayer = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
        float rel = Mth.wrapDegrees(bearingToPlayer - drone.getYRot());
        // 相对方位量化为 8 方向（rel=0 正前 → 箭头朝上，rel=90 右 → 朝右）
        int idx = Math.round(rel / 45.0F) & 7;
        double rad = Math.toRadians(idx * 45.0D);
        double dx = Math.sin(rad);
        double dy = -Math.cos(rad);

        String label = String.format(Locale.ROOT, "%.1fM", dist);
        int iconR = 3 * scale;
        int groupW = iconR * 2 + 2 * scale + OsdFont.textWidth(label, scale);
        int gx = pic.x() + pic.w() / 2 - groupW / 2;
        int gy = pic.y() + pic.h() - 3 * scale - 7 * scale;
        int ax = gx + iconR;
        int ay = gy + iconR;
        int t = Math.max(2, scale - 1);
        int len = iconR - scale;

        int ex = ax + (int) Math.round(dx * len);
        int ey = ay + (int) Math.round(dy * len);
        drawLine(img, ax - (int) Math.round(dx * len * 0.4D), ay - (int) Math.round(dy * len * 0.4D), ex, ey, t, ACCENT);
        OsdFont.fillRect(img, ex - t / 2, ey - t / 2, ex - t / 2 + t, ey - t / 2 + t, ACCENT);
        OsdFont.drawText(img, gx + iconR * 2 + 2 * scale, gy + (iconR * 2 - 7 * scale) / 2, scale, label, TEXT);
    }

    /** Bresenham 粗线（方块笔刷） */
    private static void drawLine(NativeImage img, int x0, int y0, int x1, int y1, int t, int argb) {
        int dx = Math.abs(x1 - x0);
        int sx = x0 < x1 ? 1 : -1;
        int dy = -Math.abs(y1 - y0);
        int sy = y0 < y1 ? 1 : -1;
        int err = dx + dy;
        while (true) {
            OsdFont.fillRect(img, x0 - t / 2, y0 - t / 2, x0 - t / 2 + t, y0 - t / 2 + t, argb);
            if (x0 == x1 && y0 == y1) {
                break;
            }
            int e2 = 2 * err;
            if (e2 >= dy) {
                err += dy;
                x0 += sx;
            }
            if (e2 <= dx) {
                err += dx;
                y0 += sy;
            }
        }
    }

    /** CLEAN 简约模式小准星：上下左右四条短臂，不遮挡中心 */
    private static void drawCrosshair(NativeImage img, PictureRect pic, int scale) {
        int cx = pic.x() + pic.w() / 2;
        int cy = pic.y() + pic.h() / 2;
        int t = Math.max(2, scale - 1);
        int gap = 4 * scale;
        int len = 3 * scale;
        int color = 0xC000D9FF;
        OsdFont.fillRect(img, cx - t / 2, cy - gap - len, cx - t / 2 + t, cy - gap, color);
        OsdFont.fillRect(img, cx - t / 2, cy + gap, cx - t / 2 + t, cy + gap + len, color);
        OsdFont.fillRect(img, cx - gap - len, cy - t / 2, cx - gap, cy - t / 2 + t, color);
        OsdFont.fillRect(img, cx + gap, cy - t / 2, cx + gap + len, cy - t / 2 + t, color);
    }

    /** CLEAN 底部遥测条：半透明底 + 青色分隔线 + 模式/高度/距离/水平速度/航向 */
    private static void drawTelemetryBar(Minecraft mc, NativeImage img, PictureRect pic, int scale, DroneEntity drone) {
        int barH = 11 * scale;
        int barY = pic.y() + pic.h() - barH;
        OsdFont.fillRect(img, pic.x(), barY, pic.x() + pic.w(), pic.y() + pic.h(), BAR_BG);
        OsdFont.fillRect(img, pic.x(), barY - 2, pic.x() + pic.w(), barY, ACCENT);
        int y = barY + (barH - 7 * scale) / 2;
        int x = pic.x() + 4 * scale;
        int maxX = pic.x() + pic.w() - 4 * scale;

        x = drawSegment(img, x, y, scale, "MODE", modeText(drone), modeColor(drone), maxX);

        Vec3 p = drone.position();
        double dist = mc.player != null ? drone.distanceTo(mc.player) : 0.0D;
        Vec3 v = drone.getDeltaMovement();
        double hSpd = Math.sqrt(v.x * v.x + v.z * v.z) * 20.0D;
        int hdg = ((Math.round(Mth.wrapDegrees(drone.getYRot() + 180.0F)) % 360) + 360) % 360;
        x = drawSegment(img, x, y, scale, "ALT", String.format(Locale.ROOT, "%.1fM", p.y), TEXT, maxX);
        x = drawSegment(img, x, y, scale, "DIST", String.format(Locale.ROOT, "%.1fM", dist), TEXT, maxX);
        x = drawSegment(img, x, y, scale, "SPD", String.format(Locale.ROOT, "%.1fM/S", hSpd), TEXT, maxX);
        drawSegment(img, x, y, scale, "HDG", hdg + cardinal(hdg), TEXT, maxX);
    }

    private static String cardinal(int heading) {
        return switch (heading / 90) {
            case 0 -> "N";
            case 1 -> "E";
            case 2 -> "S";
            default -> "W";
        };
    }

    /** 一组「标签 值」：标签 DIM，值用指定颜色；超出画面右缘时整组跳过（小分辨率安全） */
    private static int drawSegment(NativeImage img, int x, int y, int scale, String label, String value,
                                   int valueColor, int maxX) {
        int width = OsdFont.textWidth(label, scale) + 2 * scale + OsdFont.textWidth(value, scale);
        if (x + width > maxX) {
            return x;
        }
        x = OsdFont.drawText(img, x, y, scale, label, DIM) + 2 * scale;
        return OsdFont.drawText(img, x, y, scale, value, valueColor) + 4 * scale;
    }
}
