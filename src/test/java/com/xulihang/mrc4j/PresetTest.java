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
