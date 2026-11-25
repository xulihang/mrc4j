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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.xulihang.mrc4j.Mask;
import com.xulihang.mrc4j.Png1Bit;

/**
 * Optional byte-for-byte differential test against a real jbig2enc binary.
 * Skipped unless {@code -Djbig2.bin=<path>} or the {@code JBIG2_BIN}
 * environment variable points at one. This is the strongest check of the
 * "same stream as {@code jbig2 -p}" guarantee; the regular suite covers the
 * published reference vectors and lossless round trips instead.
 */
class Jbig2ExternalDifferentialTest {

    @Test
    void embeddedStreamMatchesExternalJbig2() throws IOException {
        String bin = System.getProperty("jbig2.bin", System.getenv("JBIG2_BIN"));
        Assumptions.assumeTrue(bin != null && !bin.isEmpty(),
                "set -Djbig2.bin=<jbig2 binary> to run the differential test");

        Random random = new Random(0x5eed);
        for (int[] size : new int[][] {{1, 1}, {31, 5}, {32, 5}, {33, 7}, {64, 3}, {97, 53}}) {
            Mask mask = new Mask(size[0], size[1]);
            for (int i = 0; i < mask.getData().length; i++) {
                mask.getData()[i] = (byte) (random.nextBoolean() ? 1 : 0);
            }
            assertArrayEquals(runExternal(bin, mask),
                    Jbig2GenericEncoder.encodeEmbedded(mask),
                    "jbig2 -p " + size[0] + "x" + size[1]);
        }

        for (String candidate : new String[] {
                "out/tmpdump-comic-base/mrc0-mask.png",
                "out/tmpdump-gdp-base/mrc0-mask.png"}) {
            File file = new File(candidate);
            if (!file.isFile()) {
                continue;
            }
            Mask mask = readMask(file);
            assertArrayEquals(runExternal(bin, mask),
                    Jbig2GenericEncoder.encodeEmbedded(mask), candidate);
        }
    }

    private static byte[] runExternal(String bin, Mask mask) throws IOException {
        Path png = Files.createTempFile("mrc4j-diff-", ".png");
        try {
            Files.write(png, Png1Bit.encode(mask));
            Process process = new ProcessBuilder(bin, "-p", png.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            byte[] output;
            try (InputStream is = process.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                is.transferTo(out);
                output = out.toByteArray();
            }
            try {
                if (process.waitFor() != 0 || output.length == 0) {
                    throw new IOException("jbig2 exited with " + process.exitValue());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            }
            return output;
        } finally {
            Files.deleteIfExists(png);
        }
    }

    private static Mask readMask(File png) throws IOException {
        java.awt.image.BufferedImage image = javax.imageio.ImageIO.read(png);
        int w = image.getWidth();
        int h = image.getHeight();
        Mask mask = new Mask(w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (((image.getRGB(x, y) >> 16) & 0xff) > 127) {
                    mask.set(x, y, true);
                }
            }
        }
        return mask;
    }
}
