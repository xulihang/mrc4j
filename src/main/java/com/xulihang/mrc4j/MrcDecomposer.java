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

/**
 * Splits a page image into MRC components with OpenCV:
 *
 * <ol>
 *   <li>a 1-bit <b>mask</b> marking ink (text and line art), obtained with
 *       Sauvola binarisation and a 5x5 neighbourhood speckle filter,</li>
 *   <li>a <b>foreground</b> layer that keeps the ink colours and fills the
 *       rest with the averaged nearby ink colours (radiating fill),</li>
 *   <li>a <b>background</b> layer where ink is removed by local averaging and
 *       the remaining artwork and paper texture are kept exactly.</li>
 * </ol>
 */
public final class MrcDecomposer {

    /** Parameters that control the decomposition. */
    public static final class Options {
        /** DPI of the image; drives the Sauvola window size (dpi / 4). */
        public int dpi = 300;
        /** Downsampling factor applied to the background layer (3 in the tool). */
        public int bgDownsample = 3;
        /** Downsampling factor applied to the foreground layer (none by default). */
        public int fgDownsample = 1;
        /** Drop isolated mask speckles with the 5x5 neighbourhood rule. */
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
        int width = image.getWidth();
        int height = image.getHeight();

        // --- mask -----------------------------------------------------------
        PixelBuffer gray = image.toGrayscale();
        // The wavelet estimator only reacts to high-frequency noise, so clean
        // digital pages are left untouched (like archive-pdf-tools).
        double sigmaEstimate = NoiseEstimator.meanEstimateSigma(gray);
        if (sigmaEstimate > 1.0) {
            gray = gray.gaussianFilter(sigmaEstimate * 0.1, 4);
        }
        Mask mask = Sauvola.thresholdImage(gray, sauvolaWindow(options.dpi),
                options.sauvolaK, 128);
        if (options.denoiseMask) {
            Optimiser.denoise(mask);
        }

        // --- colour layers ---------------------------------------------------
        PixelBuffer source = image.getChannels() == 4 ? Optimiser.rgbOnly(image) : image;
        PixelBuffer background = Optimiser.background(source, mask);
        PixelBuffer foreground = Optimiser.foreground(source, mask, background);

        if (options.fgDownsample > 1) {
            foreground = Cv.resize(foreground,
                    Math.max(1, (int) (width / (double) options.fgDownsample)),
                    Math.max(1, (int) (height / (double) options.fgDownsample)));
        }
        if (options.bgDownsample > 1) {
            background = Cv.resize(background,
                    Math.max(1, (int) (width / (double) options.bgDownsample)),
                    Math.max(1, (int) (height / (double) options.bgDownsample)));
        }
        return new Components(mask, foreground, background);
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
