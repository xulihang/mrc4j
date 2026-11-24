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
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Bridges {@link PixelBuffer}/{@link Mask} and OpenCV {@code Mat} objects and
 * loads the OpenCV native libraries on first use.
 */
final class Cv {

    private static boolean loaded;

    private Cv() {
    }

    /**
     * Load the OpenCV native libraries (idempotent). The shaded jar bundles
     * them, so extract-and-load works out of the box. Hosts such as ImageTrans
     * ship the Java bindings without the native resources and provide the
     * library on {@code java.library.path}; fall back to that, or to an
     * explicit path in {@code mrc4j.opencv.path}.
     */
    static synchronized void load() {
        if (loaded) {
            return;
        }
        try {
            nu.pattern.OpenCV.loadLocally();
        } catch (Throwable bundledFailure) {
            String path = System.getProperty("mrc4j.opencv.path");
            if (path != null && !path.isEmpty()) {
                System.load(path);
            } else {
                System.loadLibrary(Core.NATIVE_LIBRARY_NAME);
            }
        }
        loaded = true;
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
