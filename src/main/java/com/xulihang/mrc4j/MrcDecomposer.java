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

/**
 * Port of {@code create_mrc_hocr_components()} in archive-pdf-tools' mrc.py.
 *
 * <p>Splits a page image into the MRC layers:</p>
 * <ol>
 *   <li>a 1-bit <b>mask</b> marking text/line-art pixels,</li>
 *   <li>a <b>foreground</b> layer that keeps the masked pixels and replaces
 *       everything else with nearby colours, and</li>
 *   <li>a <b>background</b> layer that keeps only the non-masked pixels, with
 *       the masked content filled in from its surroundings.</li>
 * </ol>
 *
 * <p>Fully annotation-aware (hOCR) pages are not supported here: the mask is
 * derived from the image alone using Sauvola binarisation plus the same noise
 * handling as the Python tool (so it behaves like running
 * {@code bin/compress-pdf-images} without an hOCR file).</p>
 */
public final class MrcDecomposer {

    /** Parameters that mirror the defaults used by bin/compress-pdf-images. */
    public static final class Options {
        /** DPI of the image; drives the Sauvola window size (dpi / 4). */
        public int dpi = 300;
        /** Downsampling factor applied to the background layer (3 in the tool). */
        public int bgDownsample = 3;
        /** Downsampling factor applied to the foreground layer (none by default). */
        public int fgDownsample = 1;
        /** Remove isolated mask speckles (DENOISE_FAST, as used by the tool). */
        public boolean denoiseMask = true;
        /** Sauvola k parameter used for the global page threshold. */
        public double sauvolaK = 0.34;

        public static Options defaults() {
            return new Options();
        }

        public Options copy() {
            Options o = new Options();
            o.dpi = dpi;
            o.bgDownsample = bgDownsample;
            o.fgDownsample = fgDownsample;
            o.denoiseMask = denoiseMask;
            o.sauvolaK = sauvolaK;
            return o;
        }

        public Options dpi(int dpi) {
            this.dpi = dpi;
            return this;
        }

        public Options bgDownsample(int v) {
            this.bgDownsample = v;
            return this;
        }

        public Options fgDownsample(int v) {
            this.fgDownsample = v;
            return this;
        }

        public Options denoiseMask(boolean v) {
            this.denoiseMask = v;
            return this;
        }
    }

    /** The three MRC components of one page image. */
    public static final class Components {
        public final Mask mask;
        public final PixelBuffer foreground;
        public final PixelBuffer background;

        public Components(Mask mask, PixelBuffer foreground, PixelBuffer background) {
            this.mask = mask;
            this.foreground = foreground;
            this.background = background;
        }
    }

    private MrcDecomposer() {
    }

    public static Components decompose(PixelBuffer image, Options options) {
        PixelBuffer gray = image.toGrayscale();
        int width = image.getWidth();
        int height = image.getHeight();

        // --- mask -----------------------------------------------------------
        PixelBuffer blurred = gray;
        double sigmaEst = NoiseEstimator.meanEstimateSigma(gray);
        if (sigmaEst > 1.0) {
            blurred = gray.gaussianFilter(sigmaEst * 0.1, 4);
        }
        Mask mask = Sauvola.thresholdImage(blurred, sauvolaWindow(options.dpi), options.sauvolaK, 128);
        if (options.denoiseMask) {
            Optimiser.fastMaskDenoise(mask, 4, 2);
        }

        // --- foreground ------------------------------------------------------
        // Pillow modes other than L/RGB are mapped to RGB first; the mask itself
        // is broadcast over the alpha channel untouched (as in numpy).
        PixelBuffer fgSource = image.getChannels() == 1 || image.getChannels() == 3
                ? image : Optimiser.rgbOnly(image);
        PixelBuffer fg = Optimiser.optimise(fgSource, mask, 3);
        if (options.fgDownsample > 1) {
            int wDown = (int) (width / (double) options.fgDownsample);
            int hDown = (int) (height / (double) options.fgDownsample);
            if (wDown > 0 && hDown > 0) {
                fg = fg.thumbnail(wDown, hDown);
            }
        }

        // --- background ------------------------------------------------------
        Mask maskInv = mask.invert();
        PixelBuffer bg = Optimiser.optimise(fgSource, maskInv, 10);
        if (options.bgDownsample > 1) {
            int wDown = (int) (width / (double) options.bgDownsample);
            int hDown = (int) (height / (double) options.bgDownsample);
            if (wDown > 0 && hDown > 0) {
                bg = bg.thumbnail(wDown, hDown);
            }
        }
        return new Components(mask, fg, bg);
    }

    static int sauvolaWindow(int dpi) {
        int windowSize = 51;
        if (dpi > 0) {
            windowSize = dpi / 4;
            if (windowSize % 2 == 0) {
                windowSize += 1;
            }
        }
        return windowSize;
    }
}
