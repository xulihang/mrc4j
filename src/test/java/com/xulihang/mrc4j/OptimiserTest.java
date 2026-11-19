/*
 * mrc4j - Java port of archive-pdf-tools MRC compression.
 * Copyright (C) 2020-2021 Internet Archive
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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OptimiserTest {

    @Test
    void spreadsMaskedPixelIntoWindow() {
        PixelBuffer img = new PixelBuffer(4, 1, 1);
        for (int x = 0; x < 4; x++) {
            img.put(x, 0, 0, 200);
        }
        Mask mask = new Mask(4, 1);
        mask.set(2, 0, true);
        PixelBuffer out = Optimiser.optimise(img, mask, 1);
        // x=2 is masked and keeps its value; x=3 sees the masked pixel, x=0/1 see nothing
        assertEquals(200, out.get(2, 0, 0));
        assertEquals(200, out.get(3, 0, 0));
    }
}
