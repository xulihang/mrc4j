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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * MRC mask encoding, mirroring {@code encode_mrc_mask()} in mrc.py: the mask is
 * written as a 1-bit PNG and piped through {@code jbig2enc}'s {@code jbig2}
 * tool: the embedded-mode JBIG2 stream produced by {@code jbig2 -p} is put in
 * a {@code /JBIG2Decode} image stream, exactly like
 * {@code page.insert_image(mask=...)} ends up doing with PyMuPDF.
 */
public final class Jbig2Encoder {

    /** Encoded mask: {@code data} is what goes into the PDF stream. */
    public static final class Result {
        private final byte[] data;

        public Result(byte[] data) {
            this.data = data;
        }

        public byte[] data() {
            return data;
        }
    }

    private Jbig2Encoder() {
    }

    public static boolean isAvailable(String tool) {
        return Jpeg2000Encoder.executable(tool) != null;
    }

    /**
     * @param tool     jbig2 binary name/path
     * @param dumpDir  optional directory to keep the PNG/JBIG2 files in
     * @return encoded mask, or {@code null} when no jbig2 tool is available
     */
    static Result encode(Mask mask, String tool, File dumpDir, String name) throws IOException {
        String exe = Jpeg2000Encoder.executable(tool);
        Path dir;
        boolean temp = dumpDir == null;
        if (dumpDir != null) {
            Files.createDirectories(dumpDir.toPath());
            dir = Files.createTempDirectory(dumpDir.toPath(), "mask-");
        } else {
            dir = Files.createTempDirectory("mrc4j-mask");
        }
        try {
            File png = dir.resolve("mask.png").toFile();
            Files.write(png.toPath(), Png1Bit.encode(mask));
            if (exe == null) {
                if (dumpDir != null) {
                    System.out.println("  jbig2 tool not found, wrote mask PNG " + png);
                }
                return null;
            }
            // -p makes jbig2enc emit the page stream with an embedded global
            // segment. Its output is the embedded-mode-only format, which is
            // what the reference tool embeds as a /JBIG2Decode stream as well.
            Process p = new ProcessBuilder(exe, "-p", png.getAbsolutePath())
                    .redirectErrorStream(true).start();
            byte[] output;
            try (var is = p.getInputStream()) {
                output = is.readAllBytes();
            }
            if (p.waitFor() != 0 || output.length == 0) {
                System.err.println("warning: jbig2 failed for " + name + ", mask will be written as PNG");
                return null;
            }
            if (dumpDir != null) {
                File jb = dir.resolve("mask.jbig2").toFile();
                Files.write(jb.toPath(), output);
                System.out.println("  wrote " + png + " and " + jb);
            }
            return new Result(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } finally {
            // temp dirs are cleaned by the OS when dumpDir is null; keep dumps
            if (temp) {
                Jpeg2000Encoder.deleteRecursively(dir);
            }
        }
    }
}
