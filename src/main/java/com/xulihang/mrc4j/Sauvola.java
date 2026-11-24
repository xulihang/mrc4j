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

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Sauvola binarisation, as described in J. Sauvola and M. Pietikainen,
 * "Adaptive document image binarization", Pattern Recognition 33 (2000).
 *
 * <p>For each pixel the local mean {@code m} and standard deviation {@code s}
 * are computed over a square window, and the pixel is marked as ink when it is
 * darker than</p>
 *
 * <pre>  T = m * (1 + k * (s / R - 1))</pre>
 *
 * <p>with {@code R} the dynamic range of the standard deviation (128 for
 * 8-bit input) and {@code k} a user-controlled sensitivity (0.2-0.5).</p>
 */
public final class Sauvola {

    private Sauvola() {
    }

    /**
     * @param in     grayscale input
     * @param window window size in pixels (forced to an odd value &gt;= 3)
     * @param k      Sauvola k (usually 0.2-0.5)
     * @param r      dynamic range of the local standard deviation (128)
     * @return mask with {@code true} for ink (text and line art)
     */
    public static Mask thresholdImage(PixelBuffer in, int window, double k, double r) {
        if (in.getChannels() != 1) {
            throw new IllegalArgumentException("thresholdImage expects grayscale input");
        }
        if (r <= 0) {
            throw new IllegalArgumentException("r must be positive");
        }
        Cv.load();
        int w = in.getWidth();
        int h = in.getHeight();
        int win = Math.max(3, window);
        if ((win & 1) == 0) {
            win++;
        }

        Mat src = Cv.rgbMat(in);
        Mat gray = new Mat();
        src.convertTo(gray, CvType.CV_32F);
        Mat mean = new Mat();
        Mat square = new Mat();
        Mat squareMean = new Mat();
        Imgproc.boxFilter(gray, mean, CvType.CV_32F, new Size(win, win),
                new Point(-1, -1), true, Core.BORDER_REPLICATE);
        Core.multiply(gray, gray, square);
        Imgproc.boxFilter(square, squareMean, CvType.CV_32F, new Size(win, win),
                new Point(-1, -1), true, Core.BORDER_REPLICATE);

        float[] localMean = new float[w * h];
        float[] localSquareMean = new float[w * h];
        mean.get(0, 0, localMean);
        squareMean.get(0, 0, localSquareMean);
        byte[] pixels = in.getData();

        Mask mask = new Mask(w, h);
        byte[] out = mask.getData();
        double invR = 1.0 / r;
        for (int i = 0; i < localMean.length; i++) {
            double variance = localSquareMean[i] - (double) localMean[i] * localMean[i];
            if (variance < 0) {
                variance = 0;
            }
            double std = Math.sqrt(variance);
            double threshold = localMean[i] * (1.0 + k * (std * invR - 1.0));
            out[i] = (byte) (((pixels[i] & 0xff) < threshold) ? 1 : 0);
        }

        src.release();
        gray.release();
        mean.release();
        square.release();
        squareMean.release();
        return mask;
    }
}
