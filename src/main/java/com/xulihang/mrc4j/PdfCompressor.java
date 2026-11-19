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
 * Java port of {@code bin/compress-pdf-images} from Internet Archive's
 * archive-pdf-tools.
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
 *   --jbig2 PATH        jbig2 binary (default: from PATH)
 *   --no-jbig2          do not use jbig2enc; masks are embedded as Flate 1-bit
 *   --fg-slope N        Kakadu foreground slope (default 44500)
 *   --bg-slope N        Kakadu background slope (default 44250)
 *   --fg-rates R        OpenJPEG/Grok foreground compression ratio (default 750)
 *   --bg-rates R        OpenJPEG/Grok background compression ratio (default 500)
 *   --fg-bpp N          Java (JAI) foreground rate, bits per pixel (default 0.4)
 *   --bg-bpp N          Java (JAI) background rate, bits per pixel (default 0.2)
 *   --encoder NAME      kakadu | openjpeg | grok | java | none
 *   --bg-downsample N   background downsample factor (default 3)
 *   --dpi N             fallback DPI when the image has none (default 300)
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
        /** OpenJPEG/Grok compression ratios (archive-pdf-tools defaults). */
        public double fgRate = 750;
        public double bgRate = 500;
        /** Java (JAI) encoder target rates, bits per pixel. */
        public double fgBitsPerPixel = 0.4;
        public double bgBitsPerPixel = 0.2;
        public int threads = 1;
        public boolean debug = false;
    }

    /** Compression configuration. */
    public static final class Options {
        public String jbig2Command = "jbig2";
        public boolean useJbig2 = true;
        public Jpeg2000Options jpeg2000 = new Jpeg2000Options();
        public MrcDecomposer.Options mrc = MrcDecomposer.Options.defaults();
        public File dumpDir = null;
        public boolean debug = false;
    }

    /** Result for one compressed page image. */
    public record ImageResult(String name, int pageNumber, int width, int height,
                              int bgWidth, int bgHeight, int fgWidth, int fgHeight,
                              Mask mask, byte[] maskJbig2,
                              byte[] bgJp2, byte[] fgJp2) {

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
        int pageNumber = 1;
        PageCompressor pageCompressor = new PageCompressor(doc, this, options);
        for (PDPage page : doc.getPages()) {
            if (options.debug) {
                System.out.println("page " + pageNumber);
            }
            results.addAll(pageCompressor.compress(page, pageNumber));
            pageNumber++;
        }
        return results;
    }

    /** Extract, decompose and encode one page image. */
    ImageResult compressImage(PDImageXObject image, int pageNumber, String name) throws IOException {
        PixelBuffer pixels = ImageCodec.fromBufferedImage(image.getImage());
        int w = image.getWidth();
        int h = image.getHeight();

        MrcDecomposer.Options mrcOptions = options.mrc.copy();
        mrcOptions.dpi = resolveDpi(image);
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

        byte[] bg = Jpeg2000Encoder.isAvailable(options.jpeg2000)
                ? Jpeg2000Encoder.encode(c.background, 'b', options.jpeg2000)
                : ImageCodec.encodePng(c.background);
        byte[] fg = Jpeg2000Encoder.isAvailable(options.jpeg2000)
                ? Jpeg2000Encoder.encode(c.foreground, 'f', options.jpeg2000)
                : ImageCodec.encodePng(c.foreground);
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
                c.background.getWidth(), c.background.getHeight(),
                c.foreground.getWidth(), c.foreground.getHeight(),
                c.mask, maskJbig2, bg, fg);
    }

    /**
     * Effective DPI for the Sauvola window: the image's own resolution, or the
     * configured fallback for images without one (PDF images often carry no
     * resolution, unlike the scan images the Python tool works on).
     */
    int resolveDpi(PDImageXObject image) {
        float w = image.getWidth();
        // PDF image dictionaries do not have a standard resolution entry, so
        // this is the configured fallback (like the 72 dpi PyMuPDF reports for
        // PDF images without one; the Python tool then falls back to 51px).
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
                case "--kdu" -> o.jpeg2000.command = args[++i];
                case "--jbig2" -> o.jbig2Command = args[++i];
                case "--no-jbig2" -> o.useJbig2 = false;
                case "--normal-jbig2" -> o.useJbig2 = true;
                case "--fg-slope" -> o.jpeg2000.fgSlope = Integer.parseInt(args[++i]);
                case "--bg-slope" -> o.jpeg2000.bgSlope = Integer.parseInt(args[++i]);
                case "--fg-rates" -> o.jpeg2000.fgRate = Double.parseDouble(args[++i]);
                case "--bg-rates" -> o.jpeg2000.bgRate = Double.parseDouble(args[++i]);
                case "--fg-bpp" -> o.jpeg2000.fgBitsPerPixel = Double.parseDouble(args[++i]);
                case "--bg-bpp" -> o.jpeg2000.bgBitsPerPixel = Double.parseDouble(args[++i]);
                case "--encoder" -> o.jpeg2000.encoder = Jpeg2000Options.Encoder.valueOf(args[++i].toUpperCase(Locale.ROOT));
                case "--bg-downsample" -> o.mrc.bgDownsample = Integer.parseInt(args[++i]);
                case "--fg-downsample" -> o.mrc.fgDownsample = Integer.parseInt(args[++i]);
                case "--dpi" -> o.mrc.dpi = Integer.parseInt(args[++i]);
                case "--threads" -> o.jpeg2000.threads = Integer.parseInt(args[++i]);
                case "--dump" -> o.dumpDir = new File(args[++i]);
                case "--debug" -> {
                    o.debug = true;
                    o.jpeg2000.debug = true;
                }
                default -> {
                    System.err.println("unknown option: " + a);
                    System.exit(2);
                }
            }
        }
        long t0 = System.currentTimeMillis();
        List<ImageResult> results = compress(in, out, o);
        long t1 = System.currentTimeMillis();
        long inSize = in.length();
        long outSize = out.length();
        for (ImageResult r : results) {
            System.out.printf(Locale.ROOT, "page %d %s: %dx%d -> bg %dx%d + fg %dx%d (%s)%n",
                    r.pageNumber(), r.name(), r.width(), r.height(), r.bgWidth(), r.bgHeight(),
                    r.fgWidth(), r.fgHeight(), r.jbig2() ? "JBIG2 mask" : "Flate 1-bit mask");
        }
        System.out.printf(Locale.ROOT, "compressed %d image(s) in %.2fs: %d -> %d bytes%n",
                results.size(), (t1 - t0) / 1000.0, inSize, outSize);
    }
}
