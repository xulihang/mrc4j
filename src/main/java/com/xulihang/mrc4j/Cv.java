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

import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Bridges {@link PixelBuffer}/{@link Mask} and OpenCV {@code Mat} objects and
 * loads the OpenCV native libraries on first use.
 */
final class Cv {

    private Cv() {
    }

    /** Load the bundled OpenCV natives (idempotent). */
    static void load() {
        nu.pattern.OpenCV.loadLocally();
    }

    /** Grayscale or RGB Mat from a pixel buffer; an alpha channel is dropped. */
    static Mat rgbMat(PixelBuffer image) {
        load();
        int w = image.getWidth();
        int h = image.getHeight();
        int ch = image.getChannels();
        if (ch == 1) {
            Mat m = new Mat(h, w, CvType.CV_8UC1);
            m.put(0, 0, image.getData());
            return m;
        }
        if (ch == 3) {
            Mat m = new Mat(h, w, CvType.CV_8UC3);
            m.put(0, 0, image.getData());
            return m;
        }
        Mat rgba = new Mat(h, w, CvType.CV_8UC4);
        rgba.put(0, 0, image.getData());
        Mat rgb = new Mat();
        Imgproc.cvtColor(rgba, rgb, Imgproc.COLOR_RGBA2RGB);
        rgba.release();
        return rgb;
    }

    /** Copy an 8-bit 1/3-channel Mat into a pixel buffer. */
    static PixelBuffer pixels(Mat mat) {
        Mat src = mat;
        if (mat.depth() != CvType.CV_8U) {
            src = new Mat();
            mat.convertTo(src, CvType.CV_8U);
        }
        int ch = src.channels();
        if (ch != 1 && ch != 3) {
            throw new IllegalArgumentException("unsupported channel count " + ch);
        }
        byte[] data = new byte[src.cols() * src.rows() * ch];
        src.get(0, 0, data);
        PixelBuffer out = new PixelBuffer(src.cols(), src.rows(), ch, data);
        if (src != mat) {
            src.release();
        }
        return out;
    }

    /** 8-bit mask Mat, 255 where the mask is set. */
    static Mat maskMat(Mask mask) {
        load();
        byte[] src = mask.getData();
        byte[] data = new byte[src.length];
        for (int i = 0; i < src.length; i++) {
            data[i] = (byte) (src[i] != 0 ? 255 : 0);
        }
        Mat m = new Mat(mask.getHeight(), mask.getWidth(), CvType.CV_8UC1);
        m.put(0, 0, data);
        return m;
    }

    /** Mask from a Mat; any non-zero sample is set. */
    static Mask mask(Mat mat) {
        Mat src = mat;
        Mat gray = null;
        if (mat.channels() != 1) {
            gray = new Mat();
            Imgproc.cvtColor(mat, gray, Imgproc.COLOR_RGB2GRAY);
            src = gray;
        }
        byte[] data = new byte[src.cols() * src.rows()];
        if (src.depth() == CvType.CV_8U) {
            src.get(0, 0, data);
        } else {
            Mat tmp = new Mat();
            src.convertTo(tmp, CvType.CV_8U);
            tmp.get(0, 0, data);
            tmp.release();
        }
        Mask mask = new Mask(src.cols(), src.rows());
        byte[] out = mask.getData();
        for (int i = 0; i < data.length; i++) {
            out[i] = (byte) (data[i] != 0 ? 1 : 0);
        }
        if (gray != null) {
            gray.release();
        }
        return mask;
    }

    /** Resample with area interpolation (good for downsampling layers). */
    static PixelBuffer resize(PixelBuffer image, int width, int height) {
        load();
        Mat src = rgbMat(image);
        Mat dst = new Mat();
        Imgproc.resize(src, dst, new Size(width, height), 0, 0, Imgproc.INTER_AREA);
        PixelBuffer out = pixels(dst);
        src.release();
        dst.release();
        return out;
    }
}
