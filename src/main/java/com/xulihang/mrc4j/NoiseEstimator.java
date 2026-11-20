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
import org.opencv.core.MatOfDouble;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

/**
 * Estimates the standard deviation of additive image noise from the response
 * of a 3x3 Laplacian filter.
 *
 * <p>The Laplacian kernel {@code [0 1 0; 1 -4 1; 0 1 0]} has squared weights
 * summing to 20, so for white Gaussian noise of standard deviation
 * {@code sigma} the filtered signal has standard deviation
 * {@code sigma * sqrt(20)}. Only the central half of the image is used so
 * that page borders and black margins do not skew the estimate.</p>
 */
public final class NoiseEstimator {

    private NoiseEstimator() {
    }

    /** Noise sigma in 8-bit grey levels. */
    public static double estimateSigma(PixelBuffer grayscale) {
        if (grayscale.getChannels() != 1) {
            throw new IllegalArgumentException("expected grayscale image");
        }
        int w = grayscale.getWidth();
        int h = grayscale.getHeight();
        if (w < 4 || h < 4) {
            return 0.0;
        }
        Cv.load();
        Mat src = Cv.rgbMat(grayscale);
        Mat f = new Mat();
        src.convertTo(f, CvType.CV_32F);
        Mat laplacian = new Mat();
        Imgproc.Laplacian(f, laplacian, CvType.CV_32F, 1, 1.0, 0.0,
                Core.BORDER_DEFAULT);

        int cw = Math.max(1, w / 2);
        int ch = Math.max(1, h / 2);
        Mat center = new Mat(laplacian, new Rect(w / 4, h / 4, cw, ch));
        MatOfDouble mean = new MatOfDouble();
        MatOfDouble stddev = new MatOfDouble();
        Core.meanStdDev(center, mean, stddev);
        double sigma = stddev.toArray()[0] / Math.sqrt(20.0);

        center.release();
        mean.release();
        stddev.release();
        laplacian.release();
        f.release();
        src.release();
        return sigma;
    }
}
