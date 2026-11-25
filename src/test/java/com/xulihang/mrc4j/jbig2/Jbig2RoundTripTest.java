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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Random;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.jbig2.JBIG2ImageReaderSpi;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.xulihang.mrc4j.Mask;
import com.xulihang.mrc4j.Png1Bit;

/**
 * Round-trips generated JBIG2 streams through the PDFBox / jbig2-imageio
 * decoders. The generic-region coder is lossless, so every mask pixel must
 * come back unchanged.
 */
class Jbig2RoundTripTest {

    @Test
    void syntheticMasksRoundTrip() throws IOException {
        int[][] sizes = {
                {1, 1}, {2, 2}, {31, 5}, {32, 5}, {33, 7}, {64, 1},
                {8, 8}, {97, 53}, {320, 240},
        };
        Random random = new Random(0x5eed);
        for (int[] size : sizes) {
            int w = size[0];
            int h = size[1];
            Mask randomMask = new Mask(w, h);
            for (int i = 0; i < w * h; i++) {
                randomMask.getData()[i] = (byte) (random.nextBoolean() ? 1 : 0);
            }
            assertRoundTrip(randomMask, "random " + w + "x" + h);

            Mask solid = new Mask(w, h);
            for (int i = 0; i < w * h; i++) {
                solid.getData()[i] = 1;
            }
            assertRoundTrip(solid, "solid " + w + "x" + h);
        }

        Mask checker = new Mask(37, 29);
        for (int y = 0; y < checker.getHeight(); y++) {
            for (int x = 0; x < checker.getWidth(); x++) {
                checker.set(x, y, ((x ^ y) & 1) == 0);
            }
        }
        assertRoundTrip(checker, "checkerboard 37x29");
    }

    @Test
    void standaloneFileStreamRoundTrips() throws IOException {
        Mask mask = new Mask(200, 120);
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                mask.set(x, y, (x * 7 + y * 13) % 11 == 0);
            }
        }
        byte[] file = Jbig2GenericEncoder.encodeFile(mask, 300, 300);
        assertSamePixels(mask, decode(file), "standalone file");
    }

    @Test
    void embeddedStreamDecodesThroughPdfImageXObject() throws IOException {
        Mask mask = new Mask(123, 77);
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                mask.set(x, y, (x / 5 + y / 3) % 4 == 0);
            }
        }
        byte[] embedded = Jbig2GenericEncoder.encodeEmbedded(mask);
        try (PDDocument doc = new PDDocument()) {
            PDImageXObject image = new PDImageXObject(
                    new PDStream(doc, new ByteArrayInputStream(embedded)), new PDResources());
            image.getCOSObject().setInt(COSName.WIDTH, mask.getWidth());
            image.getCOSObject().setInt(COSName.HEIGHT, mask.getHeight());
            image.getCOSObject().setItem(COSName.BITS_PER_COMPONENT, COSInteger.ONE);
            image.getCOSObject().setItem(COSName.COLORSPACE, PDDeviceGray.INSTANCE);
            image.getCOSObject().setItem(COSName.FILTER, COSName.JBIG2_DECODE);
            assertSamePixels(mask, image.getImage(), "PDF image XObject");
        }
    }

    @Test
    void tpgdStreamRoundTrips() throws IOException {
        Mask mask = new Mask(40, 24);
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                boolean ink;
                if (y >= 6 && y <= 17) {
                    ink = x >= 10 && x < 30;
                } else {
                    ink = (x + y) % 9 == 0;
                }
                mask.set(x, y, ink);
            }
        }
        byte[] stream = Jbig2GenericEncoder.encode(mask, 0, 0, false, true);
        assertSamePixels(mask, decode(stream), "TPGD stream");
    }

    @Test
    void dumpedRealMasksRoundTripAndBeatG4() throws IOException {
        Path[] candidates = {
                Paths.get("out", "tmpdump-comic-base", "mrc0-mask.png"),
                Paths.get("out", "tmpdump-gdp-base", "mrc0-mask.png"),
        };
        for (Path png : candidates) {
            Assumptions.assumeTrue(Files.exists(png), "missing fixture " + png);
            Mask mask = readMaskPng(png);
            byte[] jbig2 = Jbig2GenericEncoder.encodeEmbedded(mask);
            assertSamePixels(mask, decode(jbig2), png.toString());

            byte[] g4 = encodeG4(mask);
            System.out.printf("%s %dx%d: JBIG2 %d B, G4 %d B (%.0f%% of G4)%n",
                    png.getFileName(), mask.getWidth(), mask.getHeight(),
                    jbig2.length, g4.length, 100.0 * jbig2.length / g4.length);
            assertTrue(jbig2.length < g4.length,
                    "generic JBIG2 should beat G4 on " + png);
        }
    }

    private static void assertRoundTrip(Mask mask, String label) throws IOException {
        assertSamePixels(mask, decode(Jbig2GenericEncoder.encodeEmbedded(mask)), label);
    }

    private static BufferedImage decode(byte[] stream) throws IOException {
        // ImageIO.getImageReaders()/ImageIO.read() rely on canDecodeInput(),
        // which requires the 8-byte file-header magic; PDF-embedded streams do
        // not have one, so drive the reader directly.
        ImageReader reader = new JBIG2ImageReaderSpi().createReaderInstance();
        try (ImageInputStream iis = ImageIO.createImageInputStream(
                new ByteArrayInputStream(stream))) {
            reader.setInput(iis, false, false);
            return reader.read(0, reader.getDefaultReadParam());
        } finally {
            reader.dispose();
        }
    }

    private static void assertSamePixels(Mask mask, BufferedImage decoded, String label) {
        assertEquals(mask.getWidth(), decoded.getWidth(), label + " width");
        assertEquals(mask.getHeight(), decoded.getHeight(), label + " height");
        for (int y = 0; y < mask.getHeight(); y++) {
            for (int x = 0; x < mask.getWidth(); x++) {
                boolean actual = isInk(decoded, x, y);
                if (actual != mask.get(x, y)) {
                    fail(label + ": pixel mismatch at " + x + "," + y
                            + " expected " + mask.get(x, y) + " got " + actual);
                }
            }
        }
    }

    /**
     * The decoders render a JBIG2 foreground bit 1 as black; a mask's ink is
     * therefore the black pixel in the decoded image.
     */
    private static boolean isInk(BufferedImage image, int x, int y) {
        int rgb = image.getRGB(x, y);
        return ((rgb >> 16) & 0xff) < 128;
    }

    private static Mask readMaskPng(Path file) throws IOException {
        BufferedImage image = ImageIO.read(file.toFile());
        if (image == null) {
            throw new IOException("cannot read " + file);
        }
        int w = image.getWidth();
        int h = image.getHeight();
        Mask mask = new Mask(w, h);
        Raster raster = image.getRaster();
        boolean indexed = image.getColorModel() instanceof IndexColorModel;
        int maxSample = (1 << image.getSampleModel().getSampleSize(0)) - 1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int sample = raster.getSample(x, y, 0);
                boolean ink = indexed ? sample != 0 : sample > maxSample / 2;
                if (ink) {
                    mask.set(x, y, true);
                }
            }
        }
        return mask;
    }

    private static byte[] encodeG4(Mask mask) throws IOException {
        COSDictionary params = new COSDictionary();
        params.setInt(COSName.COLUMNS, mask.getWidth());
        params.setInt(COSName.ROWS, mask.getHeight());
        params.setInt(COSName.K, -1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        FilterFactory.INSTANCE.getFilter(COSName.CCITTFAX_DECODE)
                .encode(new ByteArrayInputStream(Png1Bit.packBits(mask)), out, params, 0);
        return out.toByteArray();
    }
}
