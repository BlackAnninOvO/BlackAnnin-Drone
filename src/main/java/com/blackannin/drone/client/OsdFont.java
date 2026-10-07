package com.blackannin.drone.client;

import com.mojang.blaze3d.platform.NativeImage;

import java.util.HashMap;
import java.util.Map;

/**
 * OSD 位图字体与像素绘制工具：内置 5×7 字形（A-Z、0-9 与少量符号），
 * 提供"投影 + 加粗"的文字绘制与 alpha 混合矩形填充。
 * 不依赖资源包与 GL 字体渲染，是推流 OSD"像素合成"路线的一部分
 * （严禁 font.drawInBatch/Tesselator 进推流管线，见 AI_PROMPT 附录 A 参考 2）。
 */
final class OsdFont {
    /** 文字投影颜色与偏移 */
    private static final int SHADOW = 0x99000000;
    private static final Map<Character, Long> FONT = buildFont();

    private OsdFont() {
    }

    /**
     * 绘制一行 5×7 位图文字（scale 整数放大）。
     * 三重描边实现加粗（水平/垂直各扩 1px）+ 右下暗色投影保证亮背景可读。
     *
     * @return 结束 x
     */
    static int drawText(NativeImage img, int x, int y, int scale, String text, int argb) {
        int o = Math.max(1, scale / 2);
        drawGlyphs(img, x + o, y + o, scale, text, SHADOW);
        drawGlyphs(img, x, y, scale, text, argb);
        drawGlyphs(img, x + 1, y, scale, text, argb);
        drawGlyphs(img, x, y + 1, scale, text, argb);
        return x + textWidth(text, scale);
    }

    /** 水平居中绘制 */
    static void drawTextCentered(NativeImage img, int cx, int y, int scale, String text, int argb) {
        drawText(img, cx - textWidth(text, scale) / 2, y, scale, text, argb);
    }

    /** 右对齐绘制（rightX 为文字右缘） */
    static void drawTextRight(NativeImage img, int rightX, int y, int scale, String text, int argb) {
        drawText(img, rightX - textWidth(text, scale), y, scale, text, argb);
    }

    static int textWidth(String text, int scale) {
        return text.isEmpty() ? 0 : text.length() * 6 * scale - scale;
    }

    private static int drawGlyphs(NativeImage img, int x, int y, int scale, String text, int argb) {
        for (int i = 0; i < text.length(); i++) {
            long glyph = FONT.getOrDefault(text.charAt(i), 0L);
            for (int r = 0; r < 7; r++) {
                for (int c = 0; c < 5; c++) {
                    if ((glyph >>> (r * 5 + c) & 1L) != 0L) {
                        fillRect(img, x + c * scale, y + r * scale,
                                x + (c + 1) * scale, y + (r + 1) * scale, argb);
                    }
                }
            }
            x += 6 * scale;
        }
        return x;
    }

    /**
     * alpha 混合填充矩形（standard "over" 运算，边界钳制到图像内）。
     * NativeImage 像素为 ABGR 打包（内存 RGBA 小端读取）。
     */
    static void fillRect(NativeImage img, int x0, int y0, int x1, int y1, int argb) {
        x0 = Math.max(x0, 0);
        y0 = Math.max(y0, 0);
        x1 = Math.min(x1, img.getWidth());
        y1 = Math.min(y1, img.getHeight());
        if (x0 >= x1 || y0 >= y1) {
            return;
        }
        int srcA = (argb >>> 24) & 0xFF;
        int srcR = (argb >>> 16) & 0xFF;
        int srcG = (argb >>> 8) & 0xFF;
        int srcB = argb & 0xFF;
        for (int y = y0; y < y1; y++) {
            for (int x = x0; x < x1; x++) {
                int dst = img.getPixelRGBA(x, y);
                int dstA = (dst >>> 24) & 0xFF;
                int dstB = (dst >>> 16) & 0xFF;
                int dstG = (dst >>> 8) & 0xFF;
                int dstR = dst & 0xFF;
                int outA = srcA + dstA * (255 - srcA) / 255;
                if (outA == 0) {
                    img.setPixelRGBA(x, y, 0);
                    continue;
                }
                int outB = (srcB * srcA + dstB * dstA * (255 - srcA) / 255) / outA;
                int outG = (srcG * srcA + dstG * dstA * (255 - srcA) / 255) / outA;
                int outR = (srcR * srcA + dstR * dstA * (255 - srcA) / 255) / outA;
                img.setPixelRGBA(x, y, (outA << 24) | (outB << 16) | (outG << 8) | outR);
            }
        }
    }

    /**
     * 内置 5×7 位图字体表，每字形编码为 7 行 × 5 列位图（bit r*5+c = 第 r 行第 c 列）。
     */
    private static Map<Character, Long> buildFont() {
        String[] defs = {
                "A 01110 10001 10001 11111 10001 10001 10001",
                "B 11110 10001 11110 10001 10001 10001 11110",
                "C 01110 10001 10000 10000 10000 10001 01110",
                "D 11100 10010 10001 10001 10001 10010 11100",
                "E 11111 10000 11110 10000 10000 10000 11111",
                "F 11111 10000 11110 10000 10000 10000 10000",
                "G 01110 10001 10000 10011 10001 10001 01111",
                "H 10001 10001 11111 10001 10001 10001 10001",
                "I 01110 00100 00100 00100 00100 00100 01110",
                "J 00111 00010 00010 00010 00010 10010 01100",
                "K 10001 10010 10100 11000 10100 10010 10001",
                "L 10000 10000 10000 10000 10000 10000 11111",
                "M 10001 11011 10101 10101 10001 10001 10001",
                "N 10001 11001 10101 10011 10001 10001 10001",
                "O 01110 10001 10001 10001 10001 10001 01110",
                "P 11110 10001 10001 11110 10000 10000 10000",
                "Q 01110 10001 10001 10001 10101 10010 01101",
                "R 11110 10001 10001 11110 10100 10010 10001",
                "S 01111 10000 10000 01110 00001 00001 11110",
                "T 11111 00100 00100 00100 00100 00100 00100",
                "U 10001 10001 10001 10001 10001 10001 01110",
                "V 10001 10001 10001 10001 10001 01010 00100",
                "W 10001 10001 10001 10101 10101 10101 01010",
                "X 10001 10001 01010 00100 01010 10001 10001",
                "Y 10001 10001 01010 00100 00100 00100 00100",
                "Z 11111 00001 00010 00100 01000 10000 11111",
                "0 01110 10001 10011 10101 11001 10001 01110",
                "1 00100 01100 00100 00100 00100 00100 01110",
                "2 01110 10001 00001 00110 01000 10000 11111",
                "3 11111 00010 00100 00010 00001 10001 01110",
                "4 00010 00110 01010 10010 11111 00010 00010",
                "5 11111 10000 11110 00001 00001 10001 01110",
                "6 00110 01000 10000 11110 10001 10001 01110",
                "7 11111 00001 00010 00100 01000 01000 01000",
                "8 01110 10001 10001 01110 10001 10001 01110",
                "9 01110 10001 10001 01111 00001 00010 01100",
                ": 00000 00100 00000 00000 00100 00000 00000",
                ". 00000 00000 00000 00000 00000 01100 01100",
                "- 00000 00000 00000 01110 00000 00000 00000",
                "+ 00000 00100 00100 11111 00100 00100 00000",
                "/ 00001 00010 00010 00100 01000 01000 10000",
                "  00000 00000 00000 00000 00000 00000 00000",
        };
        Map<Character, Long> font = new HashMap<>();
        for (String def : defs) {
            long bits = 0L;
            for (int r = 0; r < 7; r++) {
                String row = def.substring(2 + r * 6, 2 + r * 6 + 5);
                for (int c = 0; c < 5; c++) {
                    if (row.charAt(c) == '1') {
                        bits |= 1L << (r * 5 + c);
                    }
                }
            }
            font.put(def.charAt(0), bits);
        }
        return font;
    }
}
