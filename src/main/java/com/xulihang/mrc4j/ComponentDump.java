/*
 * mrc4j - MRC image compression for PDFs.
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

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * Developer helper: dumps the MRC components of an image (as produced by
 * {@link MrcDecomposer}) plus md5 hashes, so they can be compared with the
 * reference dumps from the Python implementation.
 */
public final class ComponentDump {

    private ComponentDump() {
    }

    public static void main(String[] args) throws Exception {
        File in = new File(args[0]);
        File outDir = new File(args[1]);
        int dpi = args.length > 2 ? Integer.parseInt(args[2]) : 300;
        Files.createDirectories(outDir.toPath());

        PixelBuffer img = ImageCodec.decode(Files.readAllBytes(in.toPath()));
        PixelBuffer gray = img.toGrayscale();
        double sigma = NoiseEstimator.meanEstimateSigma(gray);
        System.out.println("sigma_est " + sigma);

        MrcDecomposer.Options o = new MrcDecomposer.Options();
        o.dpi = dpi;
        o.bgDownsample = 3;
        MrcDecomposer.Components c = MrcDecomposer.decompose(img, o);
        Files.write(outDir.toPath().resolve("java-mask.png"), Png1Bit.encode(c.mask));
        Files.write(outDir.toPath().resolve("java-fg.png"), ImageCodec.encodePng(c.foreground));
        Files.write(outDir.toPath().resolve("java-bg.png"), ImageCodec.encodePng(c.background));
        System.out.println("mask " + md5(c.mask.getData()) + " " + c.mask.count());
        System.out.println("fg " + md5(c.foreground.getData()));
        System.out.println("bg " + md5(c.background.getData()));
        System.out.println("bg_ds " + md5(c.background.getData()) + " " + c.background.getWidth()
                + "x" + c.background.getHeight());
    }

    static String md5(byte[] data) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5").digest(data);
        char[] hex = new char[digest.length * 2];
        final char[] digits = "0123456789abcdef".toCharArray();
        for (int i = 0; i < digest.length; i++) {
            int v = digest[i] & 0xff;
            hex[i * 2] = digits[v >>> 4];
            hex[i * 2 + 1] = digits[v & 0x0f];
        }
        return new String(hex);
    }
}
