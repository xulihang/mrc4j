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
        /**
         * Radius in pixels by which the background fill mask is grown. A
         * positive value clears that much anti-aliased fringe from the
         * background; {@code -1} picks {@code bgDownsample - 1}, so the fringe
         * does not turn into a light halo after downsampling. Set 0 to keep
         * the reference behaviour (exact fringe in the background).
         */
        public int bgDilate = -1;
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
            o.bgDilate = bgDilate;
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
        // digital pages are left untouched.
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
        // At full resolution the background keeps the exact anti-aliased edge
        // pixels and the layers reproduce the scan. Once the background is
        // downsampled, that fringe is averaged with the paper and reads as a
        // light halo around every stroke. Clear a slightly larger area from
        // the background instead (like GdPicture's MRC), so the background is
        // paper right up to the mask and the text stays crisp at reduced size.
        int fillDilate = options.bgDilate >= 0 ? options.bgDilate
                : Math.max(0, options.bgDownsample - 1);
        Mask backgroundMask = Optimiser.dilate(mask, fillDilate);
        PixelBuffer background = Optimiser.background(source, backgroundMask);
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
