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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;

import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.jbig2.JBIG2ImageReaderSpi;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

/**
 * End-to-end checks for the built-in JBIG2 mask: exact lossless round trip and
 * correct SMask polarity when the page is rendered.
 */
class Jbig2MaskTest {

    @Test
    void builtInJbig2MaskIsLosslessAndRendersOpaqueInk() throws IOException {
        BufferedImage source = new BufferedImage(64, 64, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < 64; y++) {
            for (int x = 0; x < 64; x++) {
                boolean ink = x >= 20 && x < 44 && y >= 20 && y < 44;
                source.setRGB(x, y, ink ? 0x000000 : 0xFFFFFF);
            }
        }

        PdfCompressor.Options options = new PdfCompressor.Options();
        options.jpeg2000.encoder = PdfCompressor.Jpeg2000Options.Encoder.JAVA;
        PdfCompressor.ImageResult result = PdfCompressor.encodePixels(
                ImageCodec.fromBufferedImage(source), 64, 64, 0, "test", options);

        // The built-in encoder is used by default and is lossless.
        assertNotNull(result.maskJbig2(), "built-in JBIG2 mask");
        assertMaskMatches(result);

        try (PDDocument doc = new PDDocument()) {
            PDImageXObject background = MrcImages.background(doc, result);
            PDImageXObject foreground = MrcImages.foreground(doc, result);

            COSStream smask = (COSStream) foreground.getCOSObject()
                    .getDictionaryObject(COSName.SMASK);
            assertNotNull(smask, "foreground SMask");
            assertEquals(COSName.JBIG2_DECODE, smask.getItem(COSName.FILTER));
            COSArray decode = (COSArray) smask.getDictionaryObject(COSName.DECODE);
            assertNotNull(decode, "JBIG2 SMask needs /Decode [1 0]");
            assertEquals(1, decode.getInt(0));
            assertEquals(0, decode.getInt(1));

            PDPage page = new PDPage(new PDRectangle(64, 64));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.drawImage(background, 0, 0, 64, 64);
                cs.drawImage(foreground, 0, 0, 64, 64);
            }
            BufferedImage rendered = new PDFRenderer(doc).renderImageWithDPI(0, 72);
            assertTrue(luma(rendered, 32, 32) < 64, "ink center should be dark");
            assertTrue(luma(rendered, 4, 4) > 200, "paper should stay light");
        }
    }

    private static void assertMaskMatches(PdfCompressor.ImageResult result) throws IOException {
        Mask mask = result.mask();
        ImageReader reader = new JBIG2ImageReaderSpi().createReaderInstance();
        try (ImageInputStream iis = javax.imageio.ImageIO.createImageInputStream(
                new ByteArrayInputStream(result.maskJbig2()))) {
            reader.setInput(iis, false, false);
            BufferedImage decoded = reader.read(0, reader.getDefaultReadParam());
            assertEquals(mask.getWidth(), decoded.getWidth());
            assertEquals(mask.getHeight(), decoded.getHeight());
            for (int y = 0; y < mask.getHeight(); y++) {
                for (int x = 0; x < mask.getWidth(); x++) {
                    boolean ink = luma(decoded, x, y) < 128;
                    assertEquals(mask.get(x, y), ink,
                            "mask pixel " + x + "," + y);
                }
            }
        } finally {
            reader.dispose();
        }
    }

    private static int luma(BufferedImage image, int x, int y) {
        int rgb = image.getRGB(x, y);
        int r = (rgb >> 16) & 0xff;
        int g = (rgb >> 8) & 0xff;
        int b = rgb & 0xff;
        return (r + g + b) / 3;
    }
}
