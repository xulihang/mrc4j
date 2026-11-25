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
import java.util.Arrays;

/**
 * The QM arithmetic encoder of ITU T.88 (Annex E), ported from
 * {@code jbig2arith.cc} in jbig2enc.
 *
 * <p>The coder is used for generic regions only. One instance owns the
 * arithmetic state ({@code a}, {@code c}, {@code ct}, {@code b}, {@code bp})
 * and the 65536-entry context state machine; allocate a fresh instance for
 * every region so no probability state leaks between images.</p>
 */
final class QmArithmeticEncoder {

    private static final int CONTEXT_COUNT = 65536;

    // Arithmetic coder state. `c` carries unsigned 32-bit semantics; Java
    // int addition and left shifts wrap modulo 2^32, and every right shift
    // below is unsigned (>>>).
    private int a;
    private int c;
    private int ct;
    private int b;
    private int bp;

    private final byte[] context = new byte[CONTEXT_COUNT];
    private final ByteArrayOutputStream out = new ByteArrayOutputStream(CONTEXT_COUNT / 4);

    QmArithmeticEncoder() {
        reset();
    }

    /** Reset to the initial coder state (jbig2enc_reset). */
    void reset() {
        a = 0x8000;
        c = 0;
        ct = 12;
        bp = -1;
        b = 0;
        Arrays.fill(context, (byte) 0);
        out.reset();
    }

    /** The 65536-entry context state array, shared with the region template. */
    byte[] context() {
        return context;
    }

    private void emit() {
        out.write(b);
    }

    /**
     * BYTEOUT from the standard, in the merged form used by jbig2enc. The
     * first call only latches {@code b}; nothing is emitted until {@code bp}
     * reaches 0.
     */
    private void byteout() {
        if (b == 0xff) {
            renormaliseByteout();
            return;
        }
        if (Integer.compareUnsigned(c, 0x08000000) < 0) {
            lowerByteout();
            return;
        }
        b += 1;
        if (b != 0xff) {
            lowerByteout();
            return;
        }
        c &= 0x7ffffff;
        renormaliseByteout();
    }

    private void renormaliseByteout() {
        if (bp >= 0) {
            emit();
        }
        b = c >>> 20;
        bp++;
        c &= 0xfffff;
        ct = 7;
    }

    private void lowerByteout() {
        if (bp >= 0) {
            emit();
        }
        b = c >>> 19;
        bp++;
        c &= 0x7ffff;
        ct = 8;
    }

    /**
     * Encode one bit with the given context state machine and context index
     * (the merged ENCODE / CODELPS / CODEMPS procedures).
     */
    void encodeBit(byte[] states, int contextIndex, int bit) {
        final int state = states[contextIndex] & 0xff;
        final int mps = state > 46 ? 1 : 0;
        final int qe = QmStateTable.QE[state];

        if (bit != mps) {
            // CODELPS
            a -= qe;
            if (a < qe) {
                c += qe;
            } else {
                a = qe;
            }
            states[contextIndex] = (byte) QmStateTable.NLPS[state];
        } else {
            // CODEMPS
            a -= qe;
            if ((a & 0x8000) != 0) {
                c += qe;
                return;
            }
            if (a < qe) {
                a = qe;
            } else {
                c += qe;
            }
            states[contextIndex] = (byte) QmStateTable.NMPS[state];
        }

        // RENORME
        do {
            a <<= 1;
            c <<= 1;
            ct -= 1;
            if (ct == 0) {
                byteout();
            }
        } while ((a & 0x8000) == 0);
    }

    /** FINALISE from the standard: flush the remaining coder state. */
    void finish() {
        // SETBITS
        final int tempc = c + a;
        c |= 0xffff;
        if (Integer.compareUnsigned(c, tempc) >= 0) {
            c -= 0x8000;
        }

        c <<= ct;
        byteout();
        c <<= ct;
        byteout();
        emit();
        if (b != 0xff) {
            b = 0xff;
            emit();
        }
        b = 0xac;
        emit();
    }

    byte[] toByteArray() {
        return out.toByteArray();
    }
}
