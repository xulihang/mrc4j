package com.xulihang.mrc4j;

/**
 * Port of {@code cython/sauvola.pyx} from archive-pdf-tools, an integral-image
 * based Sauvola (plus local contrast) binariser.
 *
 * <p>Output convention: a pixel becomes {@code true} when the input pixel is
 * <em>darker</em> than {@code mean * (1 - k)} plus a local variance term; see
 * {@link #binarise}.</p>
 */
public final class Sauvola {

    private Sauvola() {
    }

    /**
     * Equivalent of calling {@code binarise_sauvola} and then inverting it, as
     * {@code threshold_image()} in mrc.py does. The result has {@code true} for
     * pixels that should end up in the MRC mask.
     *
     * <p>Like in mrc.py, the window size is only meaningful when it is odd;
     * an even value makes every pixel in the first half of each row use the
     * {@code -1 / 0} outcome, which is what the original code does.</p>
     *
     * @param in        grayscale input
     * @param window    Sauvola window size (px), typically dpi/4 rounded up to odd
     * @param k         Sauvola k parameter
     * @param r         Sauvola dynamic range parameter (128 in mrc.py)
     */
    public static Mask thresholdImage(PixelBuffer in, int window, double k, double r) {
        if (in.getChannels() != 1) {
            throw new IllegalArgumentException("thresholdImage expects grayscale input");
        }
        int width = in.getWidth();
        int height = in.getHeight();
        boolean[] raw = binarise(in.getData(), width, height, window, window, k, r);
        Mask out = new Mask(width, height);
        byte[] d = out.getData();
        for (int i = 0; i < d.length; i++) {
            d[i] = (byte) (raw[i] ? 0 : 1);
        }
        return out;
    }

    /**
     * Line-by-line port of {@code binarise_sauvola}. Returns {@code true} where
     * the original writes 1 into {@code out_arr} (i.e. "formres" was false).
     */
    public static boolean[] binarise(byte[] inArr, int width, int height,
                                     int windowWidth, int windowHeight, double k, double r) {
        boolean[] out = new boolean[width * height];
        int[] integral = new int[width];
        int[] integralSquare = new int[width];

        // C semantics: (a + 1) / 2 and a / 2 are integer divisions
        int l = (windowWidth + 1) / 2;
        int rWin = windowWidth / 2;
        int o = (windowHeight + 1) / 2;
        int u = windowHeight / 2;

        double k2 = k * k / r / r;

        int index = 0;
        int imax = Math.min(height, u);
        for (int i = 0; i < imax; i++) {
            for (int j = 0; j < width; j++) {
                int pixel = inArr[index] & 0xff;
                integral[j] += pixel;
                integralSquare[j] += pixel * pixel;
                index++;
            }
        }

        int dr0 = Math.min(windowWidth, width);
        int dr1 = Math.min(rWin, width);
        int dr2 = width - l;
        int dr3 = Math.min(dr2, 0);
        int indexTop = 0;
        int indexBottom = u * width;

        int winTop = -o;
        int winBottom = u;
        index = 0;
        int bottomMax = height + u;
        while (winBottom < bottomMax) {
            if (winTop >= 0) {
                for (int j = 0; j < width; j++) {
                    int pixel = inArr[indexTop] & 0xff;
                    integral[j] -= pixel;
                    integralSquare[j] -= pixel * pixel;
                    indexTop++;
                }
                // top = winTop: only used through winHeight below
            }

            if (winBottom < height) {
                for (int j = 0; j < width; j++) {
                    int pixel = inArr[indexBottom] & 0xff;
                    integral[j] += pixel;
                    integralSquare[j] += pixel * pixel;
                    indexBottom++;
                }
            }

            // The original only guards these with non-negative checks; the only
            // reachable case with a negative bound is a leading "-1", which
            // keeps the whole column window.
            int top = winTop >= 0 ? winTop : -1;
            int bottom = winBottom < height ? winBottom : height - 1;
            int winHeight = bottom - top;

            int sum = 0;
            int squareSum = 0;
            for (int j = 0; j < dr1; j++) {
                sum += integral[j];
                squareSum += integralSquare[j];
            }

            int count = dr1 * winHeight;
            int winRight = rWin;
            while (winRight < dr0) {
                count += winHeight;
                sum += integral[winRight];
                squareSum += integralSquare[winRight];

                int pixel = inArr[index] & 0xff;
                out[index] = !formRes(k, k2, sum, squareSum, count, pixel);
                winRight++;
                index++;
            }

            int winLeft = winRight - windowWidth;
            if (winRight >= width) {
                while (winLeft < dr3) {
                    int pixel = inArr[index] & 0xff;
                    out[index] = !formRes(k, k2, sum, squareSum, count, pixel);
                    winLeft++;
                    index++;
                }
            } else {
                while (winRight < width) {
                    sum += integral[winRight] - integral[winLeft];
                    squareSum += integralSquare[winRight] - integralSquare[winLeft];

                    int pixel = inArr[index] & 0xff;
                    out[index] = !formRes(k, k2, sum, squareSum, count, pixel);
                    winLeft++;
                    winRight++;
                    index++;
                }
            }

            while (winLeft < dr2) {
                count -= winHeight;
                sum -= integral[winLeft];
                squareSum -= integralSquare[winLeft];

                int pixel = inArr[index] & 0xff;
                out[index] = !formRes(k, k2, sum, squareSum, count, pixel);
                winLeft++;
                index++;
            }

            winTop++;
            winBottom++;
        }
        return out;
    }

    private static boolean formRes(double k, double k2, int sum, int squareSum, int count, int pixel) {
        double mean = (double) sum / count;
        // squareSum is an int32 in the original, so it wraps like here
        double variance = (double) squareSum / count - (mean * mean);
        if (k >= 0) {
            double tmp = pixel + (mean * (k - 1));
            return (tmp <= 0) || (tmp * tmp <= mean * mean * k2 * variance);
        } else {
            double tmp = pixel + mean * (k - 1);
            return (tmp <= 0) && (tmp * tmp >= mean * mean * k2 * variance);
        }
    }
}
