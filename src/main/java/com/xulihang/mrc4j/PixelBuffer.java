/*
 * mrc4j - MRC image compression for PDFs.
 * Copyright (C) 2026 mrc4j contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.xulihang.mrc4j;

/**
 * Plain 8-bit pixel buffer used by the MRC algorithms.
 *
 * <p>The reference implementation operates on numpy-style arrays. Overwhelmingly those
 * arrays are {@code uint8}, with either one channel (grayscale / binary masks,
 * where {@code 1} means "set") or three channels in RGB order. An optional
 * fourth channel carries alpha and is passed through untouched.</p>
 */
public final class PixelBuffer {

    private final int width;
    private final int height;
    private final int channels;
    private final byte[] data;

    public PixelBuffer(int width, int height, int channels) {
        this(width, height, channels, new byte[width * height * channels]);
    }

    public PixelBuffer(int width, int height, int channels, byte[] data) {
        if (channels != 1 && channels != 3 && channels != 4) {
            throw new IllegalArgumentException("channels must be 1, 3 or 4");
        }
        if (data.length != width * height * channels) {
            throw new IllegalArgumentException("data length " + data.length
                    + " does not match " + width + "x" + height + "x" + channels);
        }
        this.width = width;
        this.height = height;
        this.channels = channels;
        this.data = data;
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public int getChannels() {
        return channels;
    }

    public byte[] getData() {
        return data;
    }

    public boolean hasAlpha() {
        return channels == 4;
    }

    public PixelBuffer copy() {
        return new PixelBuffer(width, height, channels, data.clone());
    }

    /** Unsigned pixel value of the given channel. */
    public int get(int x, int y, int channel) {
        return data[(y * width + x) * channels + channel] & 0xff;
    }

    public int get1(int x, int y) {
        return data[y * width + x] & 0xff;
    }

    public void put(int x, int y, int channel, int value) {
        data[(y * width + x) * channels + channel] = (byte) value;
    }

    public void put1(int x, int y, int value) {
        data[y * width + x] = (byte) value;
    }

    public PixelBuffer subImage(int x0, int y0, int w, int h) {
        if (x0 < 0 || y0 < 0 || w < 0 || h < 0 || x0 + w > width || y0 + h > height) {
            throw new IllegalArgumentException("sub image out of bounds");
        }
        PixelBuffer out = new PixelBuffer(w, h, channels);
        for (int y = 0; y < h; y++) {
            System.arraycopy(data, ((y0 + y) * width + x0) * channels,
                    out.data, y * w * channels, w * channels);
        }
        return out;
    }

    /** Replace the region {@code [x0, x0+w) x [y0, y0+h)} with {@code src}. */
    public void paste(PixelBuffer src, int x0, int y0) {
        if (src.channels != channels) {
            throw new IllegalArgumentException("channel mismatch");
        }
        for (int y = 0; y < src.height; y++) {
            System.arraycopy(src.data, y * src.width * channels,
                    data, ((y0 + y) * width + x0) * channels, src.width * channels);
        }
    }

    /** Rec. 601 luma, the weights Pillow uses for {@code Image.convert('L')}. */
    public PixelBuffer toGrayscale() {
        if (channels == 1) {
            return this;
        }
        PixelBuffer out = new PixelBuffer(width, height, 1);
        int n = width * height;
        for (int i = 0; i < n; i++) {
            int p = i * channels;
            int r = data[p] & 0xff;
            int g = data[p + 1] & 0xff;
            int b = data[p + 2] & 0xff;
            out.data[i] = (byte) ((r * 19595 + g * 38470 + b * 7471 + 0x8000) >> 16);
        }
        return out;
    }

    /**
     * Convert to 3-channel RGB (grayscale is replicated, alpha dropped).
     */
    public PixelBuffer toRgb() {
        if (channels == 3) {
            return this;
        }
        PixelBuffer out = new PixelBuffer(width, height, 3);
        int n = width * height;
        for (int i = 0; i < n; i++) {
            int p = i * channels;
            int r = data[p] & 0xff;
            int g = channels == 1 ? r : data[p + 1] & 0xff;
            int b = channels == 1 ? r : data[p + 2] & 0xff;
            out.data[i * 3] = (byte) r;
            out.data[i * 3 + 1] = (byte) g;
            out.data[i * 3 + 2] = (byte) b;
        }
        return out;
    }

    /**
     * Pillow {@code thumbnail((w, h))}: shrink so both dimensions fit, keeping
     * the aspect ratio, and never enlarge.
     */
    public PixelBuffer thumbnail(int maxWidth, int maxHeight) {
        if (width <= maxWidth && height <= maxHeight) {
            return this;
        }
        double scale = Math.min((double) maxWidth / width, (double) maxHeight / height);
        int newW = Math.max(1, (int) Math.round(width * scale));
        int newH = Math.max(1, (int) Math.round(height * scale));
        return resize(newW, newH);
    }

    /**
     * Resize with the same separable Bicubic kernel Pillow uses for
     * {@code Image.resize(..., BICUBIC)} (a = -0.5).
     */
    public PixelBuffer resize(int newW, int newH) {
        Coeffs xc = Coeffs.build(newW, width);
        Coeffs yc = Coeffs.build(newH, height);
        double[] xw = xc.weights();
        double[] yw = yc.weights();
        int[] xoff = xc.offsets();
        int[] xstride = xc.strides();
        int[] xoffSize = xc.sizes();
        int[] yoff = yc.offsets();
        int[] ystride = yc.strides();
        int[] yoffSize = yc.sizes();

        // Horizontal pass into an intermediate buffer
        double[] tmp = new double[newW * height * channels];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < newW; x++) {
                int c = xoffSize[x];
                int base = y * width * channels;
                for (int ch = 0; ch < channels; ch++) {
                    double s = 0;
                    int wBase = xstride[x];
                    for (int k = 0; k < c; k++) {
                        s += (data[base + (xoff[x] + k) * channels + ch] & 0xff) * xw[wBase + k];
                    }
                    tmp[(y * newW + x) * channels + ch] = s;
                }
            }
        }
        // Vertical pass
        PixelBuffer out = new PixelBuffer(newW, newH, channels);
        for (int y = 0; y < newH; y++) {
            int c = yoffSize[y];
            for (int x = 0; x < newW; x++) {
                for (int ch = 0; ch < channels; ch++) {
                    double s = 0;
                    int wBase = ystride[y];
                    for (int k = 0; k < c; k++) {
                        s += tmp[((yoff[y] + k) * newW + x) * channels + ch] * yw[wBase + k];
                    }
                    out.data[(y * newW + x) * channels + ch] = (byte) clamp8((long) Math.floor(s + 0.5));
                }
            }
        }
        return out;
    }

    private static final double BICUBIC_A = -0.5;

    private static double bicubic(double x) {
        x = Math.abs(x);
        if (x < 1.0) {
            return ((BICUBIC_A + 2.0) * x - (BICUBIC_A + 3.0)) * x * x + 1.0;
        }
        if (x < 2.0) {
            return (((x - 5) * x + 8) * x - 4) * BICUBIC_A;
        }
        return 0.0;
    }

    /**
     * Mirrors Pillow's {@code ImagingResampleHorizontal}: bicubic coefficients
     * are quantised to a 2^PRECISION fixed-point grid and normalised per
     * output pixel.
     */
    static final class Coeffs {
        private final double[] weights;
        private final int[] strides;
        private final int[] offsets;
        private final int[] sizes;

        private Coeffs(double[] weights, int[] strides, int[] offsets, int[] sizes) {
            this.weights = weights;
            this.strides = strides;
            this.offsets = offsets;
            this.sizes = sizes;
        }

        double[] weights() {
            return weights;
        }

        int[] strides() {
            return strides;
        }

        int[] offsets() {
            return offsets;
        }

        int[] sizes() {
            return sizes;
        }

        static Coeffs build(int outSize, int inSize) {
            final int precisionBits = 32 - 8 - 2;
            final double support = 2.0;
            double scale = (double) inSize / outSize;
            double filterscale = Math.max(scale, 1.0);
            double radius = support * filterscale;

            int[] offsets = new int[outSize];
            int[] sizes = new int[outSize];
            int[] strides = new int[outSize];
            int total = 0;
            for (int i = 0; i < outSize; i++) {
                double center = (i + 0.5) * scale;
                int xmin = (int) Math.floor(center - radius);
                int xmax = (int) Math.ceil(center + radius);
                xmin = Math.max(0, xmin);
                xmax = Math.min(inSize, xmax);
                offsets[i] = xmin;
                strides[i] = total;
                sizes[i] = Math.max(1, xmax - xmin);
                total += sizes[i];
            }
            double[] weights = new double[total];
            for (int i = 0; i < outSize; i++) {
                double center = (i + 0.5) * scale;
                int count = sizes[i];
                long sum = 0;
                for (int k = 0; k < count; k++) {
                    double w = bicubic(((offsets[i] + k - center) + 0.5) / filterscale);
                    sum += Math.round(w * (1L << precisionBits));
                }
                if (sum == 0) {
                    sum = 1;
                }
                for (int k = 0; k < count; k++) {
                    double w = bicubic(((offsets[i] + k - center) + 0.5) / filterscale);
                    weights[strides[i] + k] = (double) Math.round(w * (1L << precisionBits)) / sum;
                }
            }
            return new Coeffs(weights, strides, offsets, sizes);
        }
    }

    static int clamp8(long v) {
        if (v < 0) {
            return 0;
        }
        if (v > 255) {
            return 255;
        }
        return (int) v;
    }

    /**
     * Separable Gaussian blur with mirror-101 boundary handling and truncation
     * at {@code truncate} standard deviations, matching
     * {@code scipy.ndimage.gaussian_filter}.
     */
    public PixelBuffer gaussianFilter(double sigma, int truncate) {
        double[] kernel = gaussianKernel1d(sigma, truncate);
        int radius = (kernel.length - 1) / 2;
        int[] ymap = mirror101(height, radius);
        int[] xmap = mirror101(width, radius);

        // Vertical pass
        double[] tmp = new double[width * height * channels];
        for (int y = 0; y < height; y++) {
            for (int k = -radius; k <= radius; k++) {
                int sy = ymap[(y + k + radius)];
                double wk = kernel[k + radius];
                if (wk == 0.0) {
                    continue;
                }
                int src = sy * width * channels;
                int dst = y * width * channels;
                for (int p = 0; p < width * channels; p++) {
                    tmp[dst + p] += (data[src + p] & 0xff) * wk;
                }
            }
        }
        // Horizontal pass
        PixelBuffer out = new PixelBuffer(width, height, channels);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int k = -radius; k <= radius; k++) {
                    double wk = kernel[k + radius];
                    if (wk == 0.0) {
                        continue;
                    }
                    int sx = xmap[x + k + radius];
                    int src = (y * width + sx) * channels;
                    int dst = (y * width + x) * channels;
                    for (int ch = 0; ch < channels; ch++) {
                        tmp[dst + ch] += (data[src + ch] & 0xff) * wk;
                    }
                }
            }
        }
        for (int p = 0; p < tmp.length; p++) {
            out.data[p] = (byte) clamp8((long) Math.floor(tmp[p] + 0.5));
        }
        return out;
    }

    /**
     * Box ("uniform") filter with float output, equivalent to
     * {@code scipy.ndimage.uniform_filter}. Returns one double plane per
     * channel, laid out {@code [(y * width + x) * channels + ch]}.
     */
    public double[] uniformFilter(int size) {
        if (size < 1) {
            throw new IllegalArgumentException("size must be >= 1");
        }
        double inv = 1.0 / size;
        int back = (size - 1) / 2;
        double[] tmp = new double[width * height * channels];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int k = 0; k < size; k++) {
                    int sx = mirror101Index(x + k - back, width);
                    int src = (y * width + sx) * channels;
                    int dst = (y * width + x) * channels;
                    for (int ch = 0; ch < channels; ch++) {
                        tmp[dst + ch] += (data[src + ch] & 0xff) * inv;
                    }
                }
            }
        }
        double[] out = new double[tmp.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                for (int k = 0; k < size; k++) {
                    int sy = mirror101Index(y + k - back, height);
                    int src = (sy * width + x) * channels;
                    int dst = (y * width + x) * channels;
                    for (int ch = 0; ch < channels; ch++) {
                        out[dst + ch] += tmp[src + ch] * inv;
                    }
                }
            }
        }
        return out;
    }

    public static double[] gaussianKernel1d(double sigma, int truncate) {
        int radius = (int) (truncate * sigma + 0.5);
        double[] k = new double[2 * radius + 1];
        double sum = 0;
        for (int i = -radius; i <= radius; i++) {
            double v = Math.exp(-0.5 * (i * i) / (sigma * sigma));
            k[i + radius] = v;
            sum += v;
        }
        for (int i = 0; i < k.length; i++) {
            k[i] /= sum;
        }
        return k;
    }

    static int[] mirror101(int n, int radius) {
        int[] map = new int[n + 2 * radius];
        for (int i = 0; i < map.length; i++) {
            map[i] = mirror101Index(i - radius, n);
        }
        return map;
    }

    static int mirror101Index(int i, int n) {
        if (n == 1) {
            return 0;
        }
        int period = 2 * (n - 1);
        int m = ((i % period) + period) % period;
        return m < n ? m : period - m;
    }
}
