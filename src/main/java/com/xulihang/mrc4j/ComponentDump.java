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

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;

/**
 * Developer helper: dumps the MRC components of an image (as produced by
 * {@link MrcDecomposer}) plus md5 hashes, so they can be compared with the
 * reference dumps from the reference implementation.
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
