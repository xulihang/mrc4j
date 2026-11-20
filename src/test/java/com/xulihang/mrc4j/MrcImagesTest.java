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

import java.awt.image.BufferedImage;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.junit.jupiter.api.Test;

/**
 * The mask bits are decoded with every mask writer, so the documented meaning
 * of {@link Mask} (1 = text/foreground = opaque) is what a viewer sees.
 */
class MrcImagesTest {

    private static PdfCompressor.ImageResult result(Mask mask, byte[] jbig2,
                                                    PdfCompressor.MaskFallback fallback) {
        return new PdfCompressor.ImageResult("mask", 1, 2, 2, 1, 1, 2, 2, 1, 1,
                mask, jbig2, fallback, null, null);
    }

    private static void assertTextOpaque(PdfCompressor.ImageResult r) throws Exception {
        try (PDDocument doc = new PDDocument()) {
            BufferedImage mask = MrcImages.mask(doc, r).getImage();
            assertEquals(0xFF, mask.getRGB(0, 0) & 0xFF, "text pixel opaque");
            assertEquals(0x00, mask.getRGB(1, 0) & 0xFF, "paper pixel transparent");
        }
    }

    @Test
    void jbig2MaskDecodesTextOpaque() throws Exception {
        // jbig2enc's embedded "-p" output: generic region page 2x2, only the
        // top-left pixel (text) set.
        byte[] jbig2 = {
                // page information: generic region, 2x2, stripped default flags
                0x00, 0x00, 0x00, 0x01,
                0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x02,
                0x00, 0x00, 0x00, 0x02,
                0x00, 0x00, 0x00, 0x00, 0x00,
                // generic region at (0,0): !!!|8 pixels, 0x80, then 0x3B padding
                0x00, 0x00, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, (byte) 0x80, 0x3B,
                // end of page
                (byte) 0xFF, (byte) 0xAC,
        };
        assertTextOpaque(result(null, jbig2, PdfCompressor.MaskFallback.G4));
    }

    @Test
    void g4MaskDecodesTextOpaque() throws Exception {
        Mask mask = new Mask(2, 2);
        mask.set(0, 0, true);
        assertTextOpaque(result(mask, null, PdfCompressor.MaskFallback.G4));
    }

    @Test
    void flateMaskDecodesTextOpaque() throws Exception {
        Mask mask = new Mask(2, 2);
        mask.set(0, 0, true);
        assertTextOpaque(result(mask, null, PdfCompressor.MaskFallback.FLATE));
    }
}
