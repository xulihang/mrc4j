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
package com.xulihang.mrc4j.jbig2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;

import org.junit.jupiter.api.Test;

import com.xulihang.mrc4j.Mask;

/**
 * Byte-for-byte checks against the reference vectors in jbig2enc's
 * {@code tests/byte_regression.cc} and the expanded QM state table.
 */
class Jbig2GoldenTest {

    /**
     * Full-header encoding of a 1x1 all-zero image (jbig2enc
     * {@code test_known_generic_stream}): file header + page info + generic
     * region + end of page + end of file.
     */
    private static final byte[] GOLDEN_FILE = {
            (byte) 0x97, 0x4a, 0x42, 0x32, 0x0d, 0x0a, 0x1a, 0x0a, 0x01,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x30,
            0x00, 0x01, 0x00, 0x00, 0x00, 0x13, 0x00, 0x00, 0x00,
            0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x01, 0x26, 0x00, 0x01, 0x00, 0x00, 0x00, 0x1d,
            0x00, 0x00,
            0x00, 0x01, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x03, (byte) 0xff, (byte) 0xfd,
            (byte) 0xff, 0x02, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe, 0x7f, (byte) 0xff, (byte) 0xac, 0x00,
            0x00, 0x00, 0x02, 0x31, 0x00, 0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x03, 0x33, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00,
    };

    @Test
    void fullHeaderStreamMatchesReference() {
        byte[] encoded = Jbig2GenericEncoder.encodeFile(new Mask(1, 1), 0, 0);
        assertEquals(105, GOLDEN_FILE.length, "reference vector length");
        assertArrayEquals(GOLDEN_FILE, encoded, "full-header generic stream");
    }

    @Test
    void embeddedStreamIsTheReferenceMinusFileHeaderAndTrailers() {
        byte[] encoded = Jbig2GenericEncoder.encodeEmbedded(new Mask(1, 1));
        // Embedded PDF output = the reference stream without the 13-byte file
        // header and the two 11-byte end segments.
        byte[] expected = Arrays.copyOfRange(GOLDEN_FILE, 13, GOLDEN_FILE.length - 22);
        assertEquals(70, expected.length);
        assertArrayEquals(expected, encoded, "embedded generic stream");
    }

    @Test
    void segmentHeaderMatchesReference() {
        Jbig2SegmentWriter writer = new Jbig2SegmentWriter();
        writer.segmentHeader(5, 6, 7, 3, new int[] {1, 2}, 0x01020304);
        assertArrayEquals(new byte[] {
                0x00, 0x00, 0x00, 0x05, 0x06, 0x43, 0x01, 0x02, 0x07,
                0x01, 0x02, 0x03, 0x04,
        }, writer.toByteArray());
    }

    @Test
    void fileHeaderMatchesReference() {
        Jbig2SegmentWriter writer = new Jbig2SegmentWriter();
        writer.fileHeader(1);
        assertArrayEquals(new byte[] {
                (byte) 0x97, 0x4a, 0x42, 0x32, 0x0d, 0x0a, 0x1a, 0x0a, 0x01,
                0x00, 0x00, 0x00, 0x01,
        }, writer.toByteArray());
    }

    @Test
    void pageInfoLayout() {
        Jbig2SegmentWriter writer = new Jbig2SegmentWriter();
        writer.pageInfo(2, 3, 300, 301);
        assertArrayEquals(new byte[] {
                0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x03,
                0x00, 0x00, 0x01, 0x2c, 0x00, 0x00, 0x01, 0x2d,
                0x01, 0x00, 0x00,
        }, writer.toByteArray());
    }

    @Test
    void genericRegionLayoutWithTpgd() {
        Jbig2SegmentWriter writer = new Jbig2SegmentWriter();
        writer.genericRegion(2, 3, true);
        assertArrayEquals(new byte[] {
                0x00, 0x00, 0x00, 0x02, 0x00, 0x00, 0x00, 0x03,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x08, 0x03, (byte) 0xff, (byte) 0xfd, (byte) 0xff,
                0x02, (byte) 0xfe, (byte) 0xfe, (byte) 0xfe,
        }, writer.toByteArray());
    }

    @Test
    void qmStateTableAnchors() {
        assertArrayEquals(new int[] {0x5601, 1, 47}, entry(0));
        assertArrayEquals(new int[] {0x3401, 2, 6}, entry(1));
        assertArrayEquals(new int[] {0x5601, 7, 52}, entry(6));
        assertArrayEquals(new int[] {0x5601, 47, 1}, entry(46));
        assertArrayEquals(new int[] {0x0001, 91, 89}, entry(91));
    }

    private static int[] entry(int state) {
        return new int[] {
                QmStateTable.QE[state],
                QmStateTable.NMPS[state],
                QmStateTable.NLPS[state],
        };
    }
}
