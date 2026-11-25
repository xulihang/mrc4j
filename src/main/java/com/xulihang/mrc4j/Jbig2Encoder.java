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

import com.xulihang.mrc4j.jbig2.Jbig2GenericEncoder;

/**
 * Encodes an MRC mask as a PDF-ready JBIG2 stream.
 *
 * <p>The default is the built-in pure-Java encoder
 * ({@link Jbig2GenericEncoder}), which is lossless and needs no external
 * program. Passing an external {@code jbig2} (jbig2enc) binary uses that
 * tool instead — useful for byte-level comparison — with the built-in
 * encoder as a fallback. The embedded-mode stream produced either way goes
 * into a {@code /JBIG2Decode} image stream, exactly like the reference
 * tool's {@code jbig2 -p} output.</p>
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

    /**
     * @param tool external jbig2 binary name/path, or {@code null} for the
     *             built-in encoder
     */
    public static boolean isAvailable(String tool) {
        return tool == null || Jpeg2000Encoder.executable(tool) != null;
    }

    /**
     * @param tool    external jbig2 binary name/path, or {@code null} to use
     *                the built-in encoder
     * @param dumpDir optional directory to keep the encoded {@code .jbig2}
     *                stream in
     * @return encoded mask, or {@code null} when no encoder succeeded
     */
    static Result encode(Mask mask, String tool, File dumpDir, String name) throws IOException {
        if (tool != null) {
            Result external = encodeExternal(mask, tool, name);
            if (external != null) {
                dump(dumpDir, name, external.data());
                return external;
            }
            System.err.println("warning: external jbig2 '" + tool + "' unavailable or failed for "
                    + name + ", using the built-in encoder");
        }

        try {
            byte[] data = Jbig2GenericEncoder.encodeEmbedded(mask);
            dump(dumpDir, name, data);
            return new Result(data);
        } catch (RuntimeException e) {
            System.err.println("warning: built-in JBIG2 encoder failed for " + name + ": " + e);
            return null;
        }
    }

    private static Result encodeExternal(Mask mask, String tool, String name) throws IOException {
        String exe = Jpeg2000Encoder.executable(tool);
        if (exe == null) {
            return null;
        }
        Path png = Files.createTempFile("mrc4j-mask-", ".png");
        try {
            Files.write(png, Png1Bit.encode(mask));
            // -p makes jbig2enc emit the page stream with an embedded global
            // segment. Its output is the embedded-mode-only format, which is
            // what the reference tool embeds as a /JBIG2Decode stream as well.
            Process p = new ProcessBuilder(exe, "-p", png.toString())
                    .redirectError(ProcessBuilder.Redirect.INHERIT)
                    .start();
            byte[] output;
            try (var is = p.getInputStream()) {
                output = is.readAllBytes();
            }
            if (p.waitFor() != 0 || output.length == 0) {
                return null;
            }
            return new Result(output);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException(e);
        } finally {
            Files.deleteIfExists(png);
        }
    }

    private static void dump(File dumpDir, String name, byte[] data) throws IOException {
        if (dumpDir == null) {
            return;
        }
        Files.createDirectories(dumpDir.toPath());
        Path jb = dumpDir.toPath().resolve(name + "-mask.jbig2");
        Files.write(jb, data);
        System.out.println("  wrote " + jb);
    }
}
