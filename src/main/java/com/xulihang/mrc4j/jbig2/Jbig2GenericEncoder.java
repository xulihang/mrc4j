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
 * Pure-Java lossless JBIG2 encoder for single 1-bit images, using generic
 * region coding with template 0 (ITU T.88).
 *
 * <p>This is a port of the non-symbol-mode path of jbig2enc: for the same
 * bitmap the bytes of {@link #encodeEmbedded(Mask)} follow what
 * {@code jbig2 -p image.png} writes (no file header, no end-of-page /
 * end-of-file segments), which is exactly what a PDF {@code /JBIG2Decode}
 * image stream contains. The upstream byte-regression vector for a 1x1 image
 * is asserted in the tests; {@code Jbig2ExternalDifferentialTest} compares the
 * whole stream against an installed {@code jbig2} binary when one is
 * available. No external binary is required.</p>
 *
 * <p>Only generic-region coding is implemented; symbol dictionaries and text
 * regions are not. Generic coding is lossless: every mask pixel is
 * reconstructed exactly.</p>
 */
public final class Jbig2GenericEncoder {

    /** Size of the page information segment payload. */
    private static final int PAGE_INFO_LENGTH = 19;

    /** Size of the generic-region header inside its segment payload. */
    private static final int GENERIC_REGION_LENGTH = 26;

    private Jbig2GenericEncoder() {
    }

    /**
     * Encode a mask as a PDF-embeddable JBIG2 stream (the equivalent of
     * {@code jbig2 -p}): page information and generic-region segments only,
     * with no file header and no end-of-page / end-of-file segments.
     */
    public static byte[] encodeEmbedded(Mask mask) {
        return encode(mask, 0, 0, false, false);
    }

    /**
     * Encode a mask as a standalone JBIG2 file: file header, page
     * information, generic region, end-of-page and end-of-file segments.
     */
    public static byte[] encodeFile(Mask mask, int xres, int yres) {
        return encode(mask, xres, yres, true, false);
    }

    /**
     * @param fullHeaders           write the JBIG2 file header and end segments
     * @param duplicateLineRemoval  enable TPGD (jbig2enc's {@code -d}); off by
     *                              default because it can break some readers
     *                              and usually costs a few bytes
     */
    public static byte[] encode(Mask mask, int xres, int yres, boolean fullHeaders,
                                boolean duplicateLineRemoval) {
        if (mask == null) {
            throw new IllegalArgumentException("mask is null");
        }
        if (mask.getWidth() < 0 || mask.getHeight() < 0) {
            throw new IllegalArgumentException("negative image dimensions");
        }

        QmArithmeticEncoder encoder = new QmArithmeticEncoder();
        GenericRegionTemplate0.encode(encoder, mask, duplicateLineRemoval);
        encoder.finish();
        byte[] data = encoder.toByteArray();

        Jbig2SegmentWriter writer = new Jbig2SegmentWriter();
        if (fullHeaders) {
            writer.fileHeader(1);
        }

        // Segment 0: page information (page association 1, as jbig2enc always
        // uses in its single-page path).
        writer.segmentHeader(0, Jbig2SegmentWriter.TYPE_PAGE_INFORMATION, 1, 0,
                Jbig2SegmentWriter.noRefs(), PAGE_INFO_LENGTH);
        writer.pageInfo(mask.getWidth(), mask.getHeight(), xres, yres);

        // Segment 1: immediate generic region carrying the arithmetic data.
        writer.segmentHeader(1, Jbig2SegmentWriter.TYPE_IMM_GENERIC_REGION, 1, 0,
                Jbig2SegmentWriter.noRefs(), GENERIC_REGION_LENGTH + data.length);
        writer.genericRegion(mask.getWidth(), mask.getHeight(), duplicateLineRemoval);
        writer.bytes(data);

        if (fullHeaders) {
            // Segment 2: end of page; segment 3: end of file.
            writer.segmentHeader(2, Jbig2SegmentWriter.TYPE_END_OF_PAGE, 1, 0,
                    Jbig2SegmentWriter.noRefs(), 0);
            writer.segmentHeader(3, Jbig2SegmentWriter.TYPE_END_OF_FILE, 0, 0,
                    Jbig2SegmentWriter.noRefs(), 0);
        }

        return writer.toByteArray();
    }
}
