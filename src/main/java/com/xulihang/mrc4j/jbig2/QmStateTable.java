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

/**
 * The adaptive arithmetic-coder state table of ITU T.88 (Table E.1), in the
 * expanded form used by jbig2enc: 92 entries, where entries 46..91 repeat the
 * 46 base rows with the opposite most-probable symbol.
 *
 * <p>{@link #QE} is the probability estimate of the least-probable symbol,
 * {@link #NMPS} / {@link #NLPS} are the next states after coding an MPS / LPS
 * respectively. The MPS bit itself is not stored: as in jbig2enc it is
 * {@code state > 46 ? 1 : 0}.</p>
 */
final class QmStateTable {

    static final int[] QE = new int[92];
    static final int[] NMPS = new int[92];
    static final int[] NLPS = new int[92];

    /** {qe, nextMps, nextLps, switchMps} for the 46 base rows. */
    private static final int[] BASE = {
            0x5601, 1, 1, 1,
            0x3401, 2, 6, 0,
            0x1801, 3, 9, 0,
            0x0ac1, 4, 12, 0,
            0x0521, 5, 29, 0,
            0x0221, 38, 33, 0,
            0x5601, 7, 6, 1,
            0x5401, 8, 14, 0,
            0x4801, 9, 14, 0,
            0x3801, 10, 14, 0,
            0x3001, 11, 17, 0,
            0x2401, 12, 18, 0,
            0x1c01, 13, 20, 0,
            0x1601, 29, 21, 0,
            0x5601, 15, 14, 1,
            0x5401, 16, 14, 0,
            0x5101, 17, 15, 0,
            0x4801, 18, 16, 0,
            0x3801, 19, 17, 0,
            0x3401, 20, 18, 0,
            0x3001, 21, 19, 0,
            0x2801, 22, 19, 0,
            0x2401, 23, 20, 0,
            0x2201, 24, 21, 0,
            0x1c01, 25, 22, 0,
            0x1801, 26, 23, 0,
            0x1601, 27, 24, 0,
            0x1401, 28, 25, 0,
            0x1201, 29, 26, 0,
            0x1101, 30, 27, 0,
            0x0ac1, 31, 28, 0,
            0x09c1, 32, 29, 0,
            0x08a1, 33, 30, 0,
            0x0521, 34, 31, 0,
            0x0441, 35, 32, 0,
            0x02a1, 36, 33, 0,
            0x0221, 37, 34, 0,
            0x0141, 38, 35, 0,
            0x0111, 39, 36, 0,
            0x0085, 40, 37, 0,
            0x0049, 41, 38, 0,
            0x0025, 42, 39, 0,
            0x0015, 43, 40, 0,
            0x0009, 44, 41, 0,
            0x0005, 45, 42, 0,
            0x0001, 45, 43, 0,
    };

    static {
        for (int i = 0; i < 46; i++) {
            int j = i * 4;
            int qe = BASE[j];
            int nmps = BASE[j + 1];
            int nlps = BASE[j + 2];
            boolean swap = BASE[j + 3] != 0;

            QE[i] = qe;
            NMPS[i] = nmps;
            NLPS[i] = swap ? nlps + 46 : nlps;

            QE[i + 46] = qe;
            NMPS[i + 46] = nmps + 46;
            NLPS[i + 46] = swap ? nlps : nlps + 46;
        }
    }

    private QmStateTable() {
    }
}
