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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PresetTest {

    @Test
    void presetSetsRatesAndDownsampleFactors() {
        PdfCompressor.Options o = new PdfCompressor.Options();

        PdfCompressor.Preset.COMIC.apply(o);
        assertEquals(0.30, o.jpeg2000.fgBitsPerPixel, 1e-9);
        assertEquals(0.10, o.jpeg2000.bgBitsPerPixel, 1e-9);
        assertEquals(1, o.mrc.fgDownsample);
        assertEquals(2, o.mrc.bgDownsample);

        PdfCompressor.Preset.SMALL.apply(o);
        assertEquals(0.10, o.jpeg2000.fgBitsPerPixel, 1e-9);
        assertEquals(0.08, o.jpeg2000.bgBitsPerPixel, 1e-9);
        assertEquals(3, o.mrc.fgDownsample);
        assertEquals(3, o.mrc.bgDownsample);
    }

    @Test
    void parseIsCaseInsensitive() {
        assertEquals(PdfCompressor.Preset.BALANCED, PdfCompressor.Preset.parse(" Balanced "));
        assertEquals("balanced", PdfCompressor.Preset.BALANCED.optionName());
    }

    @Test
    void unknownPresetIsRejected() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PdfCompressor.Preset.parse("nope"));
        assertTrue(e.getMessage().contains("balanced"));
    }
}
