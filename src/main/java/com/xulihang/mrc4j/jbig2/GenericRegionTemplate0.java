/*
 * mrc4j - MRC image compression for PDFs.
 * Copyright (C) 2026 mrc4j contributors
 *
 * Portions derived from jbig2enc (https://github.com/agl/jbig2enc),
 * Copyright 2006 Google Inc., licensed under the Apache License, Version 2.0.
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
package com.xulihang.mrc4j.jbig2;

import com.xulihang.mrc4j.Mask;

/**
 * Generic-region template 0 from ITU T.88 (16 context pixels), ported from
 * {@code jbig2enc_bitimage} in jbig2enc.
 *
 * <p>The 16-pixel context is assembled from three row accumulators:</p>
 * <ul>
 *   <li>{@code c1}: 5 pixels of row y-2, columns x-2 .. x+2;</li>
 *   <li>{@code c2}: 7 pixels of row y-1, columns x-3 .. x+3;</li>
 *   <li>{@code c3}: 4 pixels of the current row, columns x-4 .. x-1.</li>
 * </ul>
 *
 * <p>{@code jbig2enc} reads Leptonica's 32-bit-word 1-bpp buffers, whose bit
 * order is equivalent to this logical left-to-right traversal; reading the
 * byte-per-pixel {@link Mask} directly therefore yields the identical bit
 * stream without the word-packing/endianness pitfalls.</p>
 */
final class GenericRegionTemplate0 {

    /** The context used for the TPGD (duplicate-line) bits. */
    private static final int TPGD_CONTEXT = 0x9b25;

    private GenericRegionTemplate0() {
    }

    static void encode(QmArithmeticEncoder encoder, Mask mask, boolean duplicateLineRemoval) {
        final byte[] states = encoder.context();
        final byte[] data = mask.getData();
        final int width = mask.getWidth();
        final int height = mask.getHeight();

        int ltp = 0;
        int sltp = 0;

        for (int y = 0; y < height; y++) {
            if (duplicateLineRemoval) {
                if (y > 0) {
                    if (sameRow(data, width, y, y - 1)) {
                        sltp = ltp ^ 1;
                        ltp = 1;
                    } else {
                        sltp = ltp;
                        ltp = 0;
                    }
                }
                encoder.encodeBit(states, TPGD_CONTEXT, sltp);
                if (ltp != 0) {
                    continue;
                }
            }

            int c1 = (pixel(data, width, height, 0, y - 2) << 2)
                    | (pixel(data, width, height, 1, y - 2) << 1)
                    | pixel(data, width, height, 2, y - 2);
            int c2 = (pixel(data, width, height, 0, y - 1) << 3)
                    | (pixel(data, width, height, 1, y - 1) << 2)
                    | (pixel(data, width, height, 2, y - 1) << 1)
                    | pixel(data, width, height, 3, y - 1);
            int c3 = 0;

            for (int x = 0; x < width; x++) {
                final int v = pixel(data, width, height, x, y);
                final int contextIndex = (c1 << 11) | (c2 << 4) | c3;
                encoder.encodeBit(states, contextIndex, v);

                // Roll the future columns into the row accumulators.
                c1 = ((c1 << 1) | pixel(data, width, height, x + 3, y - 2)) & 31;
                c2 = ((c2 << 1) | pixel(data, width, height, x + 4, y - 1)) & 127;
                c3 = ((c3 << 1) | v) & 15;
            }
        }
    }

    /** Bit at (x,y); everything outside the image (top and right) is 0. */
    private static int pixel(byte[] data, int width, int height, int x, int y) {
        if (y < 0 || y >= height || x < 0 || x >= width) {
            return 0;
        }
        return data[y * width + x] != 0 ? 1 : 0;
    }

    private static boolean sameRow(byte[] data, int width, int row, int previous) {
        int a = row * width;
        int b = previous * width;
        for (int i = 0; i < width; i++) {
            if ((data[a + i] != 0) != (data[b + i] != 0)) {
                return false;
            }
        }
        return true;
    }
}
