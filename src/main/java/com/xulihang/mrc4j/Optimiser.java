/*
 * mrc4j - Java port of archive-pdf-tools MRC compression.
 * Copyright (C) 2020-2021 Internet Archive
 * Copyright (C) 2026 mrc4j contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package com.xulihang.mrc4j;

import java.util.Arrays;


/**
 * Port of the image "optimising" step of archive-pdf-tools
 * ({@code cython/optimiser.pyx}).
 *
 * <p>For the foreground layer, pixels <em>outside</em> the text mask are
 * replaced by the mean of the masked pixels around them (and of the pixels
 * already computed above/left), so the foreground image consists of text on a
 * smooth background that resembles the surrounding artwork. For the background
 * layer the same is done with the inverted mask. This radiates the interesting
 * content into the area the other layer covers, which keeps edges smooth and
 * makes both layers much easier (and cheaper) to compress.</p>
 */
public final class Optimiser {

    private Optimiser() {
    }

    /**
     * Replaces every pixel outside the mask by the mean of masked pixels in an
     * {@code nSize} pixel window and of already processed pixels above/left of
     * it. Works for grayscale and RGB(A) buffers alike.
     */
    public static PixelBuffer optimise(PixelBuffer image, Mask mask, int nSize) {
        int w = image.getWidth();
        int h = image.getHeight();
        int ch = image.getChannels();
        byte[] in = image.getData();
        PixelBuffer out = image.copy();
        byte[] o = out.getData();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (mask.get(x, y)) {
                    continue;
                }
                long[] acc = new long[ch];
                int n = 0;
                for (int yy = Math.max(0, y - nSize); yy < Math.min(h, y + nSize); yy++) {
                    for (int xx = Math.max(0, x - nSize); xx < Math.min(w, x + nSize); xx++) {
                        if (mask.get(xx, yy)) {
                            int p = (yy * w + xx) * ch;
                            for (int c = 0; c < ch; c++) {
                                acc[c] += in[p + c] & 0xff;
                            }
                            n++;
                        }
                    }
                }
                for (int yy = Math.max(0, y - nSize); yy < y; yy++) {
                    for (int xx = Math.max(0, x - nSize); xx < x; xx++) {
                        int p = (yy * w + xx) * ch;
                        for (int c = 0; c < ch; c++) {
                            acc[c] += o[p + c] & 0xff;
                        }
                        n++;
                    }
                }
                int p = (y * w + x) * ch;
                for (int c = 0; c < ch; c++) {
                    o[p + c] = (byte) (n > 0 ? acc[c] / n : 0);
                }
            }
        }
        return out;
    }

    /** Drop the alpha channel of an RGBA buffer, keeping only RGB. */
    public static PixelBuffer rgbOnly(PixelBuffer image) {
        if (!image.hasAlpha()) {
            return image;
        }
        PixelBuffer out = new PixelBuffer(image.getWidth(), image.getHeight(), 3);
        byte[] src = image.getData();
        byte[] dst = out.getData();
        for (int i = 0; i < image.getWidth() * image.getHeight(); i++) {
            dst[i * 3] = src[i * 4];
            dst[i * 3 + 1] = src[i * 4 + 1];
            dst[i * 3 + 2] = src[i * 4 + 2];
        }
        return out;
    }

    /**
     * Port of {@code fast_mask_denoise}: drops mask pixels that have fewer than
     * {@code minCnt} set neighbours in a (2*nSize+1)^2 window. The mask is
     * modified in place, like in the original.
     */
    public static void fastMaskDenoise(Mask mask, int minCnt, int nSize) {
        int width = mask.getWidth();
        int height = mask.getHeight();
        byte[] m = mask.getData();
        for (int y = nSize; y < height - nSize; y++) {
            for (int x = nSize; x < width - nSize; x++) {
                if (m[y * width + x] != 0) {
                    int cnt = 0;
                    for (int hh = -nSize; hh <= nSize; hh++) {
                        int row = (y - hh) * width;
                        for (int ww = -nSize; ww <= nSize; ww++) {
                            cnt += m[row + x - ww];
                        }
                    }
                    m[y * width + x] = (byte) ((cnt - 1) >= minCnt ? 1 : 0);
                }
            }
        }
    }
}
