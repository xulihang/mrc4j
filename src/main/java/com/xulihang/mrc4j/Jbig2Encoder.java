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
            // what the Python tool (+ PyMuPDF) embeds as a /JBIG2Decode
            // stream as well.
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
