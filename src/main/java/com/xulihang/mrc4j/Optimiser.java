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

import java.util.ArrayList;
import java.util.List;

import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgproc.Imgproc;

/**
 * Builds the MRC colour layers and cleans up the mask.
 *
 * <p>The foreground keeps the ink pixels and fills the rest with the local
 * average of nearby ink colours (a normalised Gaussian blur), so flat areas
 * stay flat and compress well. The background replaces ink with the local
 * average of the surrounding paper/artwork. Tiny connected components are
 * dropped from the mask, which keeps dots and accents intact.</p>
 */
public final class Optimiser {

    /** Radius (in pixels) over which ink colours are spread in the foreground. */
    private static final double FOREGROUND_SIGMA = 2.0;

    /** Radius used to fill the background under the ink. */
    private static final double BACKGROUND_SIGMA = 2.0;

    /** Normalised weights below this are filled with a flat colour instead. */
    private static final double LOW_WEIGHT = 0.004;

    private Optimiser() {
    }

    /**
     * Foreground layer: masked pixels keep their sampled colour, everything
     * else receives the local average of the masked pixels around it.
     *
     * @param background the matching background layer, used where no ink is
     *                   close enough to supply a colour
     */
    public static PixelBuffer foreground(PixelBuffer image, Mask mask,
                                         PixelBuffer background) {
        Cv.load();
        Mat src = Cv.rgbMat(image);
        Mat bg = Cv.rgbMat(background);
        Mat m = Cv.maskMat(mask);
        int channels = src.channels();

        List<Mat> srcChannels = new ArrayList<>();
        List<Mat> bgChannels = new ArrayList<>();
        Core.split(src, srcChannels);
        Core.split(bg, bgChannels);

        Mat inkWeight = new Mat();
        m.convertTo(inkWeight, CvType.CV_32F, 1.0 / 255.0);
        Mat blurredWeight = new Mat();
        Imgproc.GaussianBlur(inkWeight, blurredWeight, new Size(0, 0), FOREGROUND_SIGMA);
        Mat lowWeight = new Mat(blurredWeight.rows(), blurredWeight.cols(),
                blurredWeight.type(), new Scalar(LOW_WEIGHT));
        Mat lowMask = new Mat();
        Core.compare(blurredWeight, lowWeight, lowMask, Core.CMP_LT);

        List<Mat> outChannels = new ArrayList<>();
        for (int c = 0; c < channels; c++) {
            Mat channel = new Mat();
            srcChannels.get(c).convertTo(channel, CvType.CV_32F);
            Mat masked = new Mat();
            Core.multiply(channel, inkWeight, masked);
            Mat numerator = new Mat();
            Imgproc.GaussianBlur(masked, numerator, new Size(0, 0), FOREGROUND_SIGMA);

            Mat filled = new Mat();
            Core.divide(numerator, blurredWeight, filled);
            // Too far from any ink: fall back to the inpainted background.
            Mat backgroundChannel = new Mat();
            bgChannels.get(c).convertTo(backgroundChannel, CvType.CV_32F);
            backgroundChannel.copyTo(filled, lowMask);
            // Ink pixels keep the exact sampled colour.
            channel.copyTo(filled, m);

            Mat result = new Mat();
            filled.convertTo(result, CvType.CV_8U);
            outChannels.add(result);

            channel.release();
            masked.release();
            numerator.release();
            filled.release();
            backgroundChannel.release();
        }

        Mat out = new Mat();
        Core.merge(outChannels, out);
        PixelBuffer pixels = Cv.pixels(out);

        releaseAll(srcChannels);
        releaseAll(bgChannels);
        releaseAll(outChannels);
        out.release();
        lowMask.release();
        lowWeight.release();
        blurredWeight.release();
        inkWeight.release();
        m.release();
        bg.release();
        src.release();
        return pixels;
    }

    /**
     * Background layer: ink pixels are replaced by the local average of the
     * surrounding paper/artwork (a normalised blur of the non-ink pixels);
     * non-ink pixels are left exactly as scanned.
     */
    public static PixelBuffer background(PixelBuffer image, Mask mask) {
        Cv.load();
        Mat src = Cv.rgbMat(image);
        Mat ink = Cv.maskMat(mask);
        // Remove one pixel of anti-aliased edge around the ink as well, so the
        // background does not keep grey fringes around the glyphs.
        Mat inkForBackground = new Mat();
        Imgproc.dilate(ink, inkForBackground,
                Imgproc.getStructuringElement(Imgproc.MORPH_RECT, new Size(3, 3)));
        Mat paper = new Mat();
        Core.bitwise_not(inkForBackground, paper);
        Mat paperWeight = new Mat();
        paper.convertTo(paperWeight, CvType.CV_32F, 1.0 / 255.0);
        Mat weight = new Mat();
        Imgproc.GaussianBlur(paperWeight, weight, new Size(0, 0), BACKGROUND_SIGMA);
        Mat lowWeight = new Mat(weight.rows(), weight.cols(), weight.type(),
                new Scalar(LOW_WEIGHT));
        Mat lowMask = new Mat();
        Core.compare(weight, lowWeight, lowMask, Core.CMP_LT);
        // Flat fallback used where a stretch of ink has no paper nearby.
        Scalar paperMean = Core.mean(src, paper);

        int channels = src.channels();
        List<Mat> srcChannels = new ArrayList<>();
        Core.split(src, srcChannels);
        List<Mat> outChannels = new ArrayList<>();
        for (int c = 0; c < channels; c++) {
            Mat channel = new Mat();
            srcChannels.get(c).convertTo(channel, CvType.CV_32F);
            Mat masked = new Mat();
            Core.multiply(channel, paperWeight, masked);
            Mat numerator = new Mat();
            Imgproc.GaussianBlur(masked, numerator, new Size(0, 0), BACKGROUND_SIGMA);

            Mat filled = new Mat();
            Core.divide(numerator, weight, filled);
            double flat = c < paperMean.val.length ? paperMean.val[c] : paperMean.val[0];
            filled.setTo(new Scalar(flat), lowMask);
            Mat filled8 = new Mat();
            filled.convertTo(filled8, CvType.CV_8U);

            Mat result = new Mat();
            srcChannels.get(c).copyTo(result);
            filled8.copyTo(result, inkForBackground);
            outChannels.add(result);

            channel.release();
            masked.release();
            numerator.release();
            filled.release();
            filled8.release();
        }

        Mat out = new Mat();
        Core.merge(outChannels, out);
        PixelBuffer pixels = Cv.pixels(out);

        releaseAll(srcChannels);
        releaseAll(outChannels);
        out.release();
        lowMask.release();
        lowWeight.release();
        weight.release();
        paperWeight.release();
        paper.release();
        inkForBackground.release();
        ink.release();
        src.release();
        return pixels;
    }

    /** Remove isolated mask speckles, keeping dots, accents and line work. */
    public static void denoise(Mask mask) {
        Cv.load();
        Mat m = Cv.maskMat(mask);
        Mat labels = new Mat();
        Mat stats = new Mat();
        Mat centroids = new Mat();
        int count = Imgproc.connectedComponentsWithStats(m, labels, stats, centroids,
                8, CvType.CV_32S);

        int area = mask.getWidth() * mask.getHeight();
        int[] labelData = new int[area];
        labels.get(0, 0, labelData);
        int[] statsData = new int[count * 5];
        stats.get(0, 0, statsData);

        byte[] out = mask.getData();
        for (int i = 0; i < labelData.length; i++) {
            int label = labelData[i];
            boolean keep = label != 0
                    && statsData[label * 5 + Imgproc.CC_STAT_AREA] > 2;
            out[i] = (byte) (keep ? 1 : 0);
        }

        centroids.release();
        stats.release();
        labels.release();
        m.release();
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

    private static void releaseAll(List<Mat> mats) {
        for (Mat mat : mats) {
            mat.release();
        }
    }
}
