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

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

import com.github.jaiimageio.jpeg2000.J2KImageWriteParam;

/**
 * Encodes the MRC foreground/background to JPEG2000 with the same command line
 * options bin/compress-pdf-images hands to Kakadu's {@code kdu_compress}:
 *
 * <pre>
 *   kdu_compress -i bg.tif -o bg.jp2 -slope 44250 -num_threads 0
 *   kdu_compress -i fg.tif -o fg.jp2 -slope 44500 -num_threads 0
 * </pre>
 *
 * <p>Kakadu and Grok share the basic flags; for OpenJPEG (and Grok without
 * Kakadu's -slope) a code-rate based setting is used instead, which is the
 * closest publicly available equivalent.</p>
 */
public final class Jpeg2000Encoder {

    private Jpeg2000Encoder() {
    }

    public static boolean isAvailable(PdfCompressor.Jpeg2000Options options) {
        if (options.encoder == PdfCompressor.Jpeg2000Options.Encoder.NONE) {
            return false;
        }
        if (options.encoder == PdfCompressor.Jpeg2000Options.Encoder.JAVA) {
            return javaEncoderAvailable();
        }
        return executable(options.command) != null;
    }

    private static boolean javaEncoderAvailable() {
        ImageCodec.registerImageIoPlugins();
        return ImageIO.getImageWritersByFormatName("jpeg2000").hasNext();
    }

    /** Encode with the JAI ImageIO JPEG2000 writer (no external binaries). */
    private static byte[] encodeJava(PixelBuffer img, char type,
                                     PdfCompressor.Jpeg2000Options o) throws IOException {
        ImageCodec.registerImageIoPlugins();
        if (!ImageIO.getImageWritersByFormatName("jpeg2000").hasNext()) {
            throw new IOException("No JPEG2000 ImageIO writer; "
                    + "add com.github.jai-imageio:jai-imageio-jpeg2000");
        }
        double bpp = type == 'f' ? o.fgBitsPerPixel : o.bgBitsPerPixel;
        BufferedImage bi = ImageCodec.toBufferedImage(img);
        try {
            return writeJava(bi, bpp, false);
        } catch (IllegalArgumentException e) {
            // "Overall target bitrate too low, given the current bit stream
            // header overhead": the code-block headers do not fit into the
            // budget of tiny (fast mode) images. Store losslessly instead.
            if (e.getMessage() == null || !e.getMessage().contains("header overhead")) {
                throw e;
            }
            return writeJava(bi, bpp, true);
        }
    }

    /**
     * One JPEG2000 write with a fresh writer. {@code lossless} uses the
     * reversible 5/3 filter.
     */
    private static byte[] writeJava(BufferedImage bi, double bpp, boolean lossless) throws IOException {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg2000").next();
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            J2KImageWriteParam param = new J2KImageWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            if (lossless) {
                param.setLossless(true);
                // The default filter is the irreversible 9/7; lossless needs
                // the reversible 5/3 one.
                param.setFilter(J2KImageWriteParam.FILTER_53);
            } else {
                param.setLossless(false);
                param.setEncodingRate(bpp);
            }
            writer.write(null, new IIOImage(bi, null, null), param);
            ios.flush();
            return bos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    /** Encode a component ('f' foreground or 'b' background) to JP2 bytes. */
    static byte[] encode(PixelBuffer img, char type, PdfCompressor.Jpeg2000Options o) throws IOException {
        if (o.encoder == PdfCompressor.Jpeg2000Options.Encoder.NONE) {
            throw new IOException("JPEG2000 encoding disabled");
        }
        if (o.encoder == PdfCompressor.Jpeg2000Options.Encoder.JAVA) {
            return encodeJava(img, type, o);
        }
        String exe = executable(o.command);
        if (exe == null) {
            throw new IOException("JPEG2000 encoder '" + o.command + "' not found on PATH; "
                    + "install Kakadu (kdu_compress) or pass another compatible binary");
        }
        Path dir = Files.createTempDirectory("mrc4j-jp2");
        try {
            File tif = dir.resolve("in.tif").toFile();
            File jp2 = dir.resolve("out.jp2").toFile();
            ImageCodec.writeTiff(img, tif);
            List<String> cmd = new ArrayList<>();
            cmd.add(exe);
            cmd.add("-i");
            cmd.add(tif.getAbsolutePath());
            cmd.add("-o");
            cmd.add(jp2.getAbsolutePath());
            int slope = type == 'f' ? o.fgSlope : o.bgSlope;
            switch (o.encoder) {
                case KAKADU:
                    cmd.add("-slope");
                    cmd.add(Integer.toString(slope));
                    cmd.add("-num_threads");
                    cmd.add(o.threads <= 1 ? "0" : Integer.toString(o.threads));
                    break;
                case GROK:
                    cmd.add("-Cp");
                    cmd.add("slope=" + slope);
                    cmd.add("-H");
                    cmd.add(Integer.toString(Math.max(1, o.threads)));
                    break;
                case OPENJPEG:
                    cmd.add("-I");
                    cmd.add("-r");
                    cmd.add(Double.toString(type == 'f' ? o.fgRate : o.bgRate));
                    if (o.threads > 1) {
                        cmd.add("-threads");
                        cmd.add(Integer.toString(o.threads));
                    }
                    break;
                default:
                    throw new IOException("unsupported encoder " + o.encoder);
            }
            if (o.debug) {
                System.out.println("  " + String.join(" ", cmd));
            }
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            byte[] output;
            try (var is = p.getInputStream()) {
                output = is.readAllBytes();
            }
            if (!p.waitFor(600, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException("JPEG2000 encoder timed out");
            }
            if (p.exitValue() != 0 || !jp2.exists()) {
                throw new IOException("JPEG2000 encoder failed (" + p.exitValue() + "): "
                        + new String(output, StandardCharsets.UTF_8).trim());
            }
            return Files.readAllBytes(jp2.toPath());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted", e);
        } finally {
            deleteRecursively(dir);
        }
    }

    /** Resolve an executable name on PATH (absolute paths are used as given). */
    static String executable(String name) {
        if (name == null || name.isEmpty()) {
            return null;
        }
        File f = new File(name);
        if (f.isAbsolute() || name.contains("/") || name.contains("\\")) {
            return f.canExecute() ? f.getAbsolutePath() : null;
        }
        String path = System.getenv("PATH");
        if (path == null) {
            return null;
        }
        String[] exts = {".exe", "", ".cmd", ".bat"};
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isEmpty()) {
                continue;
            }
            for (String ext : exts) {
                File candidate = new File(dir, name + ext);
                if (candidate.isFile() && candidate.canExecute()) {
                    return candidate.getAbsolutePath();
                }
            }
        }
        return null;
    }

    static void deleteRecursively(Path dir) {
        try (var s = Files.walk(dir)) {
            s.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // best effort cleanup of temp files
        }
    }
}
