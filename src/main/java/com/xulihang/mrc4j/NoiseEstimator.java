package com.xulihang.mrc4j;

import java.util.Arrays;

/**
 * Noise level estimation, a dependency-free port of skimage's
 * {@code estimate_sigma(image)} for 2D images (default arguments). This is
 * what {@code mean_estimate_sigma()} in archive-pdf-tools' mrc.py computes:
 *
 * <pre>
 *   coeffs = pywt.dwtn(image, wavelet='db2')
 *   detail_coeffs = coeffs['dd']
 *   detail_coeffs = detail_coeffs[detail_coeffs != 0]
 *   sigma = median(|detail_coeffs|) / norm.ppf(0.75)
 * </pre>
 *
 * <p>skimage first converts the uint8 image to float in [0, 1], then PyWavelets
 * runs a single-level 2D DWT in "periodization" mode. Periodization pads odd
 * lengths by replicating the last sample, and computes</p>
 * <pre>
 *   c[k] = sum_j x[(2 + 2k - j) mod N] * h[j]
 * </pre>
 * <p>with {@code h} the decomposition filter (verified against PyWavelets).</p>
 */
public final class NoiseEstimator {

    private static final double[] DB2_DEC_LO = {
            -0.12940952255126037, 0.2241438680420134, 0.8365163037378079, 0.48296291314453416,
    };
    private static final double[] DB2_DEC_HI = {
            -0.48296291314453416, 0.8365163037378079, -0.2241438680420134, -0.12940952255126037,
    };

    /** norm.ppf(0.75) for the standard normal distribution. */
    public static final double NORM_PPF_075 = 0.6744897501960817;

    private NoiseEstimator() {
    }

    /** Equivalent of {@code np.mean(estimate_sigma(np.array(img, dtype=np.float32)))}. */
    public static double meanEstimateSigma(PixelBuffer grayscale) {
        if (grayscale.getChannels() != 1) {
            throw new IllegalArgumentException("expected grayscale image");
        }
        int h = grayscale.getHeight();
        int w = grayscale.getWidth();
        if (h < 2 || w < 2) {
            return 0.0;
        }
        byte[] data = grayscale.getData();

        // Rows: one-level DWT along x, only the detail (high-pass) band is needed.
        int rowLen = (w + 1) / 2;
        double[] hi = new double[h * rowLen];
        double[] buf = new double[Math.max(w, h)];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                buf[x] = (data[y * w + x] & 0xff) / 255.0;
            }
            dwt1dPeriodizationDetail(buf, w, DB2_DEC_HI, hi, y * rowLen);
        }
        // Columns: DWT along y of the detail band -> HH ("dd") coefficients.
        int colLen = (h + 1) / 2;
        int n = 0;
        double[] hh = new double[colLen * rowLen];
        for (int x = 0; x < rowLen; x++) {
            for (int y = 0; y < h; y++) {
                buf[y] = hi[y * rowLen + x];
            }
            dwt1dPeriodizationDetail(buf, h, DB2_DEC_HI, hh, x * colLen);
        }
        // Remember layout: hh[x * colLen + y]
        int size = colLen * rowLen;
        double[] vals = new double[size];
        for (int i = 0; i < size; i++) {
            double v = hh[i];
            if (v != 0.0) {
                vals[n++] = Math.abs(v);
            }
        }
        if (n == 0) {
            return 0.0;
        }
        vals = Arrays.copyOf(vals, n);
        Arrays.sort(vals);
        double median = n % 2 == 1 ? vals[n / 2] : (vals[n / 2 - 1] + vals[n / 2]) / 2.0;
        return median / NORM_PPF_075;
    }

    /** Detail band of a one-level periodization DWT, written to {@code out[outOffset ..]}. */
    static void dwt1dPeriodizationDetail(double[] in, int len, double[] filter,
                                         double[] out, int outOffset) {
        int outLen = (len + 1) / 2;
        // Odd lengths are extended by one replicated sample.
        int n = len % 2 == 0 ? len : len + 1;
        for (int k = 0; k < outLen; k++) {
            double acc = 0;
            for (int j = 0; j < filter.length; j++) {
                int idx = (2 + 2 * k - j) % n;
                if (idx < 0) {
                    idx += n;
                }
                double sample = idx >= len ? in[len - 1] : in[idx];
                acc += sample * filter[j];
            }
            out[outOffset + k] = acc;
        }
    }
}
