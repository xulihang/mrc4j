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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * MRC image compressor for PDFs.
 *
 * <p>Each image on each page is split into MRC layers (1-bit text mask,
 * foreground, downsampled background), compressed with JPEG2000 (foreground /
 * background) and JBIG2 (mask), and put back where the original image was.
 * The text layer of the source PDF is preserved, so the result stays
 * searchable and copyable.</p>
 *
 * <h2>Command line</h2>
 * <pre>
 *   java -jar mrc4j.jar input.pdf output.pdf [options]
 *
 *   --kdu PATH          kdu_compress binary (default: from PATH)
 *   --jbig2 PATH        use this external jbig2 (jbig2enc) binary for masks
 *   --no-jbig2          do not use JBIG2; masks use --mask-compression
 *   --mask-compression  g4 | flate | jbig2 (fallback G4)
 *   --fg-slope N        Kakadu foreground slope (default 44500)
 *   --bg-slope N        Kakadu background slope (default 44250)
 *   --fg-rates R        OpenJPEG/Grok foreground compression ratio (default 750)
 *   --bg-rates R        OpenJPEG/Grok background compression ratio (default 500)
 *   --fg-bpp N          Java (JAI) foreground rate, bits per pixel (default 0.4)
 *   --bg-bpp N          Java (JAI) background rate, bits per pixel (default 0.2)
 *   --encoder NAME      kakadu | openjpeg | grok | java | none
 *   --preset NAME       high | balanced | small | comic | photo
 *   --bg-downsample N   background downsample factor (default 3)
 *   --bg-dilate N       background fill mask growth, -1 = auto (default -1)
 *   --dpi N             fallback DPI when the image has none (default 300)
 *   --sauvola-k N       Sauvola sensitivity, 0.2-0.5 (default 0.34)
 *   --no-denoise        keep isolated mask speckles
 *   --threads N         encoder threads (default 1)
 *   --dump DIR          keep the MRC component images for inspection
 *   --debug             verbose output
 * </pre>
 */
public final class PdfCompressor {

    /** JPEG2000 encoder configuration (Kakadu slope values by default). */
    public static final class Jpeg2000Options {
        public enum Encoder { KAKADU, OPENJPEG, GROK, NONE, JAVA }

        public Encoder encoder = Encoder.KAKADU;
        public String command = "kdu_compress";
        public int fgSlope = 44500;
        public int bgSlope = 44250;
        /** OpenJPEG/Grok compression ratios (reference defaults). */
        public double fgRate = 750;
        public double bgRate = 500;
        /** Java (JAI) encoder target rates, bits per pixel. */
        public double fgBitsPerPixel = 0.4;
        public double bgBitsPerPixel = 0.2;
        public int threads = 1;
        public boolean debug = false;
    }

    /** Mask codec used when jbig2enc is not available. */
    public enum MaskFallback { G4, FLATE }

    /** Compression configuration. */
    public static final class Options {
        /**
         * External jbig2 (jbig2enc) binary, or {@code null} (default) to use
         * the built-in pure-Java JBIG2 encoder.
         */
        public String jbig2Command = null;
        /** Encode the mask with JBIG2 (built-in, or {@link #jbig2Command}). */
        public boolean useJbig2 = true;
        /** Codec for the mask when JBIG2 is unavailable/disabled. */
        public MaskFallback maskFallback = MaskFallback.G4;
        public Jpeg2000Options jpeg2000 = new Jpeg2000Options();
        public MrcDecomposer.Options mrc = MrcDecomposer.Options.defaults();
        public File dumpDir = null;
        public boolean debug = false;
    }

    /**
     * Named quality/size profiles for the built-in Java pipeline. A preset
     * sets the JAI rates and the foreground/background downsample factors;
     * explicit options that follow {@code --preset} on the command line
     * override individual values.
     */
    public enum Preset {
        /** Full-resolution colour: largest files, best for print and zooming. */
        HIGH(0.40, 0.20, 1, 1),
        /** General-purpose documents: half-resolution colour, 1/3 background. */
        BALANCED(0.20, 0.10, 2, 3),
        /** Screen reading: 1/3 colour and background, low rates. */
        SMALL(0.10, 0.08, 3, 3),
        /** Comics and line art: keep the colour foreground full resolution. */
        COMIC(0.30, 0.10, 1, 2),
        /** Photo-heavy pages: keep more colour and background resolution. */
        PHOTO(0.25, 0.15, 2, 2);

        private final double fgBitsPerPixel;
        private final double bgBitsPerPixel;
        private final int fgDownsample;
        private final int bgDownsample;

        Preset(double fgBitsPerPixel, double bgBitsPerPixel,
               int fgDownsample, int bgDownsample) {
            this.fgBitsPerPixel = fgBitsPerPixel;
            this.bgBitsPerPixel = bgBitsPerPixel;
            this.fgDownsample = fgDownsample;
            this.bgDownsample = bgDownsample;
        }

        /** Apply this profile's rates and downsample factors to {@code o}. */
        public void apply(Options o) {
            o.jpeg2000.fgBitsPerPixel = fgBitsPerPixel;
            o.jpeg2000.bgBitsPerPixel = bgBitsPerPixel;
            o.mrc.fgDownsample = fgDownsample;
            o.mrc.bgDownsample = bgDownsample;
        }

        /** Parse a preset name (case-insensitive). */
        public static Preset parse(String name) {
            try {
                return valueOf(name.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("unknown preset '" + name
                        + "'; valid presets: high, balanced, small, comic, photo");
            }
        }

        /** Lower-case name accepted by {@code --preset}. */
        public String optionName() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Result for one compressed page image. */
    public static final class ImageResult {
        private final String name;
        private final int pageNumber;
        private final int width;
        private final int height;
        private final int bgWidth;
        private final int bgHeight;
        private final int fgWidth;
        private final int fgHeight;
        private final int bgChannels;
        private final int fgChannels;
        private final Mask mask;
        private final byte[] maskJbig2;
        private final MaskFallback maskFallback;
        private final byte[] bgJp2;
        private final byte[] fgJp2;

        public ImageResult(String name, int pageNumber, int width, int height,
                           int bgWidth, int bgHeight, int fgWidth, int fgHeight,
                           int bgChannels, int fgChannels,
                           Mask mask, byte[] maskJbig2, MaskFallback maskFallback,
                           byte[] bgJp2, byte[] fgJp2) {
            this.name = name;
            this.pageNumber = pageNumber;
            this.width = width;
            this.height = height;
            this.bgWidth = bgWidth;
            this.bgHeight = bgHeight;
            this.fgWidth = fgWidth;
            this.fgHeight = fgHeight;
            this.bgChannels = bgChannels;
            this.fgChannels = fgChannels;
            this.mask = mask;
            this.maskJbig2 = maskJbig2;
            this.maskFallback = maskFallback;
            this.bgJp2 = bgJp2;
            this.fgJp2 = fgJp2;
        }

        public String name() {
            return name;
        }

        public int pageNumber() {
            return pageNumber;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        public int bgWidth() {
            return bgWidth;
        }

        public int bgHeight() {
            return bgHeight;
        }

        public int fgWidth() {
            return fgWidth;
        }

        public int fgHeight() {
            return fgHeight;
        }

        public int bgChannels() {
            return bgChannels;
        }

        public int fgChannels() {
            return fgChannels;
        }

        public Mask mask() {
            return mask;
        }

        public byte[] maskJbig2() {
            return maskJbig2;
        }

        public MaskFallback maskFallback() {
            return maskFallback;
        }

        public byte[] bgJp2() {
            return bgJp2;
        }

        public byte[] fgJp2() {
            return fgJp2;
        }

        public boolean jbig2() {
            return maskJbig2 != null;
        }
    }

    private final PDDocument doc;
    private final Options options;

    PdfCompressor(PDDocument doc, Options options) {
        this.doc = doc;
        this.options = options;
    }

    /** Compress {@code input} to {@code output}; returns per-image results. */
    public static List<ImageResult> compress(File input, File output, Options options) throws IOException {
        try (PDDocument doc = PDDocument.load(input)) {
            PdfCompressor c = new PdfCompressor(doc, options);
            List<ImageResult> results = c.compressAll();
            doc.save(output);
            return results;
        }
    }

    List<ImageResult> compressAll() throws IOException {
        List<ImageResult> results = new ArrayList<>();
        int pages = doc.getNumberOfPages();
        // Collect the pages up front: compressing a page updates the page tree,
        // and iterating a page tree that changes mid-iteration can skip pages.
        List<PDPage> pageList = new ArrayList<>();
        for (PDPage page : doc.getPages()) {
            pageList.add(page);
        }
        int pageNumber = 1;
        PageCompressor pageCompressor = new PageCompressor(doc, this);
        progress(0, pages);
        for (PDPage page : pageList) {
            if (options.debug) {
                System.out.println("page " + pageNumber);
            }
            results.addAll(pageCompressor.compress(page, pageNumber));
            progress(pageNumber, pages);
            pageNumber++;
        }
        return results;
    }

    /** Print {@code done/total} on its own line while pages are compressed. */
    private static void progress(int done, int total) {
        System.out.printf(Locale.ROOT, "%d/%d%n", done, total);
        System.out.flush();
    }

    /** Extract, decompose and encode one page image. */
    ImageResult compressImage(PDImageXObject image, int pageNumber, String name) throws IOException {
        return encodePageImage(image, pageNumber, name, options);
    }

    /** Decompose/encode one PDImageXObject (used by the per-image facade). */
    static ImageResult encodePageImage(PDImageXObject image, int pageNumber, String name,
                                       Options options) throws IOException {
        PixelBuffer pixels = ImageCodec.fromBufferedImage(ImageCodec.getImage(image));
        return encodePixels(pixels, image.getWidth(), image.getHeight(), pageNumber, name, options);
    }

    /** Decompose/encode one pixel buffer. */
    static ImageResult encodePixels(PixelBuffer pixels, int w, int h, int pageNumber,
                                    String name, Options options) throws IOException {
        MrcDecomposer.Options mrcOptions = options.mrc.copy();
        mrcOptions.dpi = options.mrc.dpi;
        long t0 = System.currentTimeMillis();
        MrcDecomposer.Components c = MrcDecomposer.decompose(pixels, mrcOptions);
        long t1 = System.currentTimeMillis();

        byte[] maskJbig2 = null;
        if (options.useJbig2) {
            Jbig2Encoder.Result encoded = Jbig2Encoder.encode(c.mask, options.jbig2Command, options.dumpDir, name);
            if (encoded != null) {
                maskJbig2 = encoded.data();
            }
        }

        // The Java (JAI) encoder always writes RGB: per-component rates on a
        // single-component (gray) codestream come out far too coarse.
        boolean java = options.jpeg2000.encoder == Jpeg2000Options.Encoder.JAVA;
        PixelBuffer bgPixels = java ? c.background.toRgb() : c.background;
        PixelBuffer fgPixels = java ? c.foreground.toRgb() : c.foreground;
        boolean jp2 = Jpeg2000Encoder.isAvailable(options.jpeg2000);
        byte[] bg = jp2
                ? Jpeg2000Encoder.encode(bgPixels, 'b', options.jpeg2000)
                : ImageCodec.encodePng(bgPixels);
        byte[] fg = jp2
                ? Jpeg2000Encoder.encode(fgPixels, 'f', options.jpeg2000)
                : ImageCodec.encodePng(fgPixels);
        long t2 = System.currentTimeMillis();

        if (options.dumpDir != null) {
            Files.createDirectories(options.dumpDir.toPath());
            Files.write(options.dumpDir.toPath().resolve(name + "-mask.png"), Png1Bit.encode(c.mask));
            Files.write(options.dumpDir.toPath().resolve(name + "-fg.png"), ImageCodec.encodePng(c.foreground));
            Files.write(options.dumpDir.toPath().resolve(name + "-bg.png"), ImageCodec.encodePng(c.background));
            Files.write(options.dumpDir.toPath().resolve(name + "-bg.jp2"), bg);
            Files.write(options.dumpDir.toPath().resolve(name + "-fg.jp2"), fg);
        }

        if (options.debug) {
            System.out.printf(Locale.ROOT, "  %s %dx%d mask=%d fg=%dx%d bg=%dx%d mrc=%dms enc=%dms%n",
                    name, w, h, c.mask.count(), c.foreground.getWidth(), c.foreground.getHeight(),
                    c.background.getWidth(), c.background.getHeight(), t1 - t0, t2 - t1);
        }
        return new ImageResult(name, pageNumber, w, h,
                bgPixels.getWidth(), bgPixels.getHeight(),
                fgPixels.getWidth(), fgPixels.getHeight(),
                bgPixels.getChannels(), fgPixels.getChannels(),
                c.mask, maskJbig2, options.maskFallback, bg, fg);
    }

    /**
     * Effective DPI for the Sauvola window: the image's own resolution, or the
     * configured fallback for images without one (PDF images often carry no
     * resolution, unlike the scan images the reference tool works on).
     */
    int resolveDpi(PDImageXObject image) {
        float w = image.getWidth();
        // PDF image dictionaries do not have a standard resolution entry, so
        // this is the configured fallback (like the 72 dpi PyMuPDF reports for
        // PDF images without one; the reference tool then falls back to 51px).
        return options.mrc.dpi;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("usage: java -jar mrc4j.jar input.pdf output.pdf [options]");
            System.err.println("see com.xulihang.mrc4j.PdfCompressor for the option list");
            System.exit(2);
        }
        Options o = new Options();
        File in = new File(args[0]);
        File out = new File(args[1]);
        for (int i = 2; i < args.length; i++) {
            String a = args[i];
            switch (a) {
                case "--kdu":
                    o.jpeg2000.command = args[++i];
                    break;
                case "--jbig2":
                    o.jbig2Command = args[++i];
                    break;
                case "--no-jbig2":
                    o.useJbig2 = false;
                    break;
                case "--normal-jbig2":
                    o.useJbig2 = true;
                    break;
                case "--mask-compression": {
                    String mask = args[++i].toLowerCase(Locale.ROOT);
                    if ("jbig2".equals(mask)) {
                        o.useJbig2 = true;
                    } else {
                        o.useJbig2 = false;
                        o.maskFallback = "flate".equals(mask)
                                ? MaskFallback.FLATE : MaskFallback.G4;
                    }
                    break;
                }
                case "--preset": {
                    try {
                        Preset preset = Preset.parse(args[++i]);
                        preset.apply(o);
                        System.out.printf(Locale.ROOT,
                                "preset %s: fg-bpp %.2f bg-bpp %.2f "
                                        + "fg-downsample %d bg-downsample %d%n",
                                preset.optionName(), o.jpeg2000.fgBitsPerPixel,
                                o.jpeg2000.bgBitsPerPixel,
                                o.mrc.fgDownsample, o.mrc.bgDownsample);
                    } catch (IllegalArgumentException e) {
                        System.err.println(e.getMessage());
                        System.exit(2);
                    }
                    break;
                }
                case "--fg-slope":
                    o.jpeg2000.fgSlope = Integer.parseInt(args[++i]);
                    break;
                case "--bg-slope":
                    o.jpeg2000.bgSlope = Integer.parseInt(args[++i]);
                    break;
                case "--fg-rates":
                    o.jpeg2000.fgRate = Double.parseDouble(args[++i]);
                    break;
                case "--bg-rates":
                    o.jpeg2000.bgRate = Double.parseDouble(args[++i]);
                    break;
                case "--fg-bpp":
                    o.jpeg2000.fgBitsPerPixel = Double.parseDouble(args[++i]);
                    break;
                case "--bg-bpp":
                    o.jpeg2000.bgBitsPerPixel = Double.parseDouble(args[++i]);
                    break;
                case "--encoder":
                    o.jpeg2000.encoder = Jpeg2000Options.Encoder.valueOf(
                            args[++i].toUpperCase(Locale.ROOT));
                    break;
                case "--bg-downsample":
                    o.mrc.bgDownsample = Integer.parseInt(args[++i]);
                    break;
                case "--bg-dilate":
                    o.mrc.bgDilate = Integer.parseInt(args[++i]);
                    break;
                case "--fg-downsample":
                    o.mrc.fgDownsample = Integer.parseInt(args[++i]);
                    break;
                case "--dpi":
                    o.mrc.dpi = Integer.parseInt(args[++i]);
                    break;
                case "--sauvola-k":
                    o.mrc.sauvolaK = Double.parseDouble(args[++i]);
                    break;
                case "--no-denoise":
                    o.mrc.denoiseMask = false;
                    break;
                case "--threads":
                    o.jpeg2000.threads = Integer.parseInt(args[++i]);
                    break;
                case "--dump":
                    o.dumpDir = new File(args[++i]);
                    break;
                case "--debug":
                    o.debug = true;
                    o.jpeg2000.debug = true;
                    break;
                default:
                    System.err.println("unknown option: " + a);
                    System.exit(2);
                    break;
            }
        }
        long t0 = System.currentTimeMillis();
        List<ImageResult> results = compress(in, out, o);
        long t1 = System.currentTimeMillis();
        long inSize = in.length();
        long outSize = out.length();
        for (ImageResult r : results) {
            String maskKind = r.jbig2() ? "JBIG2 mask"
                    : (r.maskFallback() == MaskFallback.G4 ? "G4 mask" : "Flate mask");
            System.out.printf(Locale.ROOT, "page %d %s: %dx%d -> bg %dx%d + fg %dx%d (%s)%n",
                    r.pageNumber(), r.name(), r.width(), r.height(), r.bgWidth(), r.bgHeight(),
                    r.fgWidth(), r.fgHeight(), maskKind);
        }
        System.out.printf(Locale.ROOT, "compressed %d image(s) in %.2fs: %d -> %d bytes%n",
                results.size(), (t1 - t0) / 1000.0, inSize, outSize);
    }
}
