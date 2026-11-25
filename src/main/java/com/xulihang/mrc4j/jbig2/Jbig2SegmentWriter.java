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

import java.io.ByteArrayOutputStream;

/**
 * Serialises JBIG2 segment headers and the payloads needed by a generic
 * region, matching {@code jbig2segments.h} / {@code jbig2structs.h} in
 * jbig2enc (little-endian bitfield order, big-endian multi-byte fields).
 */
final class Jbig2SegmentWriter {

    static final int TYPE_IMM_GENERIC_REGION = 38;
    static final int TYPE_PAGE_INFORMATION = 48;
    static final int TYPE_END_OF_PAGE = 49;
    static final int TYPE_END_OF_FILE = 51;

    private static final int[] NO_REFS = new int[0];

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    /** The 13-byte JBIG2 file header (only used for standalone files). */
    void fileHeader(int numberOfPages) {
        out.write(0x97);
        out.write(0x4a);
        out.write(0x42);
        out.write(0x32);
        out.write(0x0d);
        out.write(0x0a);
        out.write(0x1a);
        out.write(0x0a);
        // organisation_type = 1 (sequential), unknown_n_pages = 0, reserved = 0
        out.write(0x01);
        writeU32(numberOfPages);
    }

    /**
     * A segment header. {@code dataLength} is the payload size only, not
     * including this header.
     */
    void segmentHeader(int number, int type, int page, int retainBits,
                       int[] referredTo, int dataLength) {
        final int pageSize = page <= 255 ? 1 : 4;
        final int refSize = number <= 256 ? 1 : number <= 65536 ? 2 : 4;

        writeU32(number);
        // type:6, page_association_size:1, deferred_non_retain:1 (LSB first)
        out.write((type & 0x3f) | ((pageSize == 4 ? 1 : 0) << 6));
        // retain_bits:5, segment_count:3 (LSB first)
        out.write((retainBits & 0x1f) | ((referredTo.length & 0x07) << 5));
        for (int ref : referredTo) {
            writeN(ref, refSize);
        }
        writeN(page, pageSize);
        writeU32(dataLength);
    }

    /** The 19-byte page information payload. */
    void pageInfo(int width, int height, int xres, int yres) {
        writeU32(width);
        writeU32(height);
        writeU32(xres);
        writeU32(yres);
        // is_lossless:1, contains_refinements:1, default_pixel:1,
        // default_operator:2, aux_buffers:1, operator_override:1, reserved:1
        out.write(0x01);
        out.write(0);
        out.write(0);
    }

    /** The 26-byte generic-region payload with template 0 adaptive pixels. */
    void genericRegion(int width, int height, boolean tpgd) {
        writeU32(width);
        writeU32(height);
        writeU32(0); // x
        writeU32(0); // y
        out.write(0); // combination operator: OR
        // mmr:1, gbtemplate:2, tpgdon:1, reserved:4
        out.write(tpgd ? 0x08 : 0x00);
        // a1x, a1y, a2x, a2y, a3x, a3y, a4x, a4y (two's complement bytes)
        out.write(3);
        out.write(-1);
        out.write(-3);
        out.write(-1);
        out.write(2);
        out.write(-2);
        out.write(-2);
        out.write(-2);
    }

    void bytes(byte[] data) {
        out.write(data, 0, data.length);
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }

    static int[] noRefs() {
        return NO_REFS;
    }

    private void writeU32(int value) {
        out.write(value >>> 24);
        out.write(value >>> 16);
        out.write(value >>> 8);
        out.write(value);
    }

    private void writeN(int value, int bytes) {
        for (int shift = (bytes - 1) * 8; shift >= 0; shift -= 8) {
            out.write(value >>> shift);
        }
    }
}
