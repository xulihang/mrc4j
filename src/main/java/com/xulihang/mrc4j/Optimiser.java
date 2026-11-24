/*
 * mrc4j - MRC image compression for PDFs.
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

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Builds the MRC colour layers and cleans up the mask.
 *
 * <p>Both colour layers come from the same "radiate" fill used by
 * archive-pdf-tools' {@code optimiser.pyx}: every pixel outside the mask is
 * replaced by the mean of the masked pixels around it plus the pixels already
 * computed above/left of it. The foreground uses the ink mask, so the masked
 * strokes keep their exact colours while the rest is filled with nearby ink
 * colours; the background uses the inverted mask, so the paper/artwork keeps
 * every original pixel and only the strokes are filled in. The result is that
 * compositing background + foreground (through the mask) reproduces the scan
 * exactly, while both layers consist of flat, easy-to-compress areas.</p>
 *
 * <p>The reference implementation scans a square window for every pixel. This
 * version keeps the same arithmetic (window sums, double-counted top-left
 * quadrant, integer division) but computes the sums incrementally, so the cost
 * is linear in the pixel count instead of quadratic in the window size.</p>
 */
public final class Optimiser {

    /** Window size of the foreground fill (archive-pdf-tools uses 3). */
    private static final int FOREGROUND_WINDOW = 3;

    /** Window size of the background fill (archive-pdf-tools uses 10). */
    private static final int BACKGROUND_WINDOW = 10;

    /** Mask denoising radius and neighbour count (archive-pdf-tools defaults). */
    private static final int DENOISE_RADIUS = 2;
    private static final int DENOISE_MIN_NEIGHBOURS = 4;

    private Optimiser() {
    }

    /**
     * Foreground layer: masked pixels keep their sampled colour, everything
     * else receives the local average of the masked pixels around it.
     *
     * @param background retained for API compatibility; no longer used
     */
    public static PixelBuffer foreground(PixelBuffer image, Mask mask,
                                         PixelBuffer background) {
        return optimise(image, mask, FOREGROUND_WINDOW);
    }

    /**
     * Background layer: ink pixels are replaced by the local average of the
     * surrounding paper/artwork; every non-ink pixel (including the
     * anti-aliased glyph edges) is left exactly as scanned.
     */
    public static PixelBuffer background(PixelBuffer image, Mask mask) {
        return optimise(image, mask.invert(), BACKGROUND_WINDOW);
    }

    /**
     * Reference "optimise" step: replaces every pixel outside {@code mask} by
     * the mean of the masked input pixels in a {@code 2*nSize} square window
     * plus the already computed output pixels in the top-left quadrant of that
     * window.
     */
    public static PixelBuffer optimise(PixelBuffer image, Mask mask, int nSize) {
        if (nSize < 1) {
            throw new IllegalArgumentException("nSize must be >= 1");
        }
        int width = image.getWidth();
        int height = image.getHeight();
        if (mask.getWidth() != width || mask.getHeight() != height) {
            throw new IllegalArgumentException("mask size does not match image");
        }
        int channels = image.getChannels();
        byte[] in = image.getData();
        byte[] m = mask.getData();
        PixelBuffer out = image.copy();
        byte[] o = out.getData();
        for (int c = 0; c < channels; c++) {
            optimiseChannel(in, o, m, width, height, channels, c, nSize);
        }
        return out;
    }

    /**
     * One channel of {@link #optimise}. {@code colMask}/{@code colCount} hold
     * the masked-pixel sums and counts of the rows in the current search
     * window; {@code colOut} holds the output sums of the rows above the
     * current one (at most {@code nSize} of them). Prefix sums over the columns
     * then give both window sums in constant time per pixel.
     */
    private static void optimiseChannel(byte[] in, byte[] out, byte[] mask,
                                        int width, int height, int channels,
                                        int channel, int nSize) {
        long[] colMask = new long[width];
        long[] colCount = new long[width];
        long[] colOut = new long[width];
        long[] prefixMask = new long[width + 1];
        long[] prefixCount = new long[width + 1];
        long[] prefixOut = new long[width + 1];

        int top = 0;
        int bottom = 0;
        for (int y = 0; y < height; y++) {
            int y0 = Math.max(0, y - nSize);
            int y1 = Math.min(height, y + nSize);
            while (top < y0) {
                removeMaskedRow(colMask, colCount, in, mask, top, width, channels, channel);
                top++;
            }
            while (bottom < y1) {
                addMaskedRow(colMask, colCount, in, mask, bottom, width, channels, channel);
                bottom++;
            }

            prefixMask[0] = 0;
            prefixCount[0] = 0;
            prefixOut[0] = 0;
            for (int x = 0; x < width; x++) {
                prefixMask[x + 1] = prefixMask[x] + colMask[x];
                prefixCount[x + 1] = prefixCount[x] + colCount[x];
                prefixOut[x + 1] = prefixOut[x] + colOut[x];
            }

            int rowBase = y * width;
            long aboveCount = y - y0;
            for (int x = 0; x < width; x++) {
                if (mask[rowBase + x] != 0) {
                    continue;
                }
                int x0 = Math.max(0, x - nSize);
                int x1 = Math.min(width, x + nSize);
                long sum = (prefixMask[x1] - prefixMask[x0])
                        + (prefixOut[x] - prefixOut[x0]);
                long count = (prefixCount[x1] - prefixCount[x0])
                        + (long) (x - x0) * aboveCount;
                int p = (rowBase + x) * channels + channel;
                out[p] = (byte) (count > 0 ? sum / count : 0);
            }

            // The current row is above the next one; drop the row that falls
            // out of the nSize-row window after that.
            for (int x = 0; x < width; x++) {
                colOut[x] += out[(rowBase + x) * channels + channel] & 0xff;
            }
            int drop = y - nSize;
            if (drop >= 0) {
                int dropBase = drop * width;
                for (int x = 0; x < width; x++) {
                    colOut[x] -= out[(dropBase + x) * channels + channel] & 0xff;
                }
            }
        }
    }

    private static void addMaskedRow(long[] colMask, long[] colCount, byte[] in,
                                     byte[] mask, int y, int width, int channels,
                                     int channel) {
        int base = y * width;
        for (int x = 0; x < width; x++) {
            if (mask[base + x] != 0) {
                colMask[x] += in[(base + x) * channels + channel] & 0xff;
                colCount[x]++;
            }
        }
    }

    private static void removeMaskedRow(long[] colMask, long[] colCount, byte[] in,
                                        byte[] mask, int y, int width, int channels,
                                        int channel) {
        int base = y * width;
        for (int x = 0; x < width; x++) {
            if (mask[base + x] != 0) {
                colMask[x] -= in[(base + x) * channels + channel] & 0xff;
                colCount[x]--;
            }
        }
    }

    /**
     * Drop isolated mask speckles, keeping dots, accents and line work.
     *
     * <p>This is the reference {@code fast_mask_denoise} rule: a set pixel
     * survives when at least {@value #DENOISE_MIN_NEIGHBOURS} of the other 24
     * pixels in its 5x5 neighbourhood are set too. The outermost two rows and
     * columns are left untouched, as in archive-pdf-tools. OpenCV computes the
     * neighbourhood sums.</p>
     */
    public static void denoise(Mask mask) {
        Cv.load();
        Mat m = Cv.maskMat(mask);
        Mat sums = new Mat();
        Imgproc.boxFilter(m, sums, CvType.CV_32F,
                new Size(2 * DENOISE_RADIUS + 1, 2 * DENOISE_RADIUS + 1),
                new Point(-1, -1), false, Core.BORDER_REPLICATE);

        int width = mask.getWidth();
        int height = mask.getHeight();
        float[] neighbourhood = new float[width * height];
        sums.get(0, 0, neighbourhood);

        byte[] out = mask.getData();
        for (int y = DENOISE_RADIUS; y < height - DENOISE_RADIUS; y++) {
            int row = y * width;
            for (int x = DENOISE_RADIUS; x < width - DENOISE_RADIUS; x++) {
                int i = row + x;
                if (out[i] == 0) {
                    continue;
                }
                int setNeighbours = Math.round(neighbourhood[i] / 255.0f) - 1;
                out[i] = (byte) (setNeighbours >= DENOISE_MIN_NEIGHBOURS ? 1 : 0);
            }
        }

        sums.release();
        m.release();
    }

    /**
     * Grow the mask by {@code radius} pixels with a square structuring element.
     * Used to clear the anti-aliased fringe around strokes from the background
     * before that layer is downsampled (where it would otherwise turn into a
     * light halo).
     */
    public static Mask dilate(Mask mask, int radius) {
        if (radius <= 0) {
            return mask;
        }
        Cv.load();
        Mat m = Cv.maskMat(mask);
        Mat dst = new Mat();
        Imgproc.dilate(m, dst, Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                new Size(2 * radius + 1, 2 * radius + 1)));
        Mask out = Cv.mask(dst);
        dst.release();
        m.release();
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
}
