package com.xulihang.mrc4j;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Builds an MRC PDF from a directory of page images.
 *
 * <p>Usage: {@code MrcPdfFromImages <imagesDir> <outPdf> [sourcePdf|-] [dpi=300] [bgBpp=0.4] [fgBpp=0.05] [key=value...]}</p>
 *
 * <p>If a source PDF is given, its per-page displayed sizes are reused as page
 * sizes; otherwise sizes are derived from the pixel dimensions and the DPI.</p>
 *
 * <p>Extra {@code key=value} options: {@code mode=mrc|lossless|textmask},
 * {@code intermediate=<dir>} (textmask), {@code bgFormat=jpeg|jpx},
 * {@code fgFormat=jpx|jpeg}, {@code bgScale=2}, {@code bgQuality=0.6},
 * {@code fgQuality=0.7}, {@code mask=g4|flate}, {@code fgMode=auto|image|stencil},
 * {@code stencilSat=24}, {@code stencilVal=110}, {@code blockSize=31},
 * {@code thresholdC=10}, {@code bgBlur=9}, {@code maskOpen=3},
 * {@code clearDilation=3}, {@code textMaskDilation=1}, {@code textFgBpp=0.2},
 * {@code bgLossless=0|1}.</p>
 *
 * <p>{@code mode=textmask} reads ImageTrans intermediates named
 * {@code <image>-mask.png} and {@code <image>-text-removed.png} from
 * {@code intermediate}: the text-free image becomes the background, the
 * original text pixels selected by the mask become a lossless JPEG 2000
 * foreground, and the mask is its G4 soft mask.</p>
 *
 * <p>{@code mode=stencil} uses the same intermediates when available, or the
 * original images alone: neutral line art and black text become a 1-bit black
 * stencil, light text becomes a 1-bit white stencil (when a text mask is
 * available), and the background is a JPEG 2000 color image. Without
 * intermediates the background is the original with the stencil areas
 * cleared, and light text stays in the color background.</p>
 */
public final class MrcPdfFromImages {
    private enum Mode {
        MRC,
        LOSSLESS,
        TEXTMASK,
        STENCIL
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            printUsage();
            System.exit(2);
        }
        Path imagesDir = Paths.get(args[0]).toAbsolutePath().normalize();
        Path outPdf = Paths.get(args[1]).toAbsolutePath().normalize();
        MrcPdfBuilder.Options options = new MrcPdfBuilder.Options();
        String sourcePdf = "-";
        float dpi = 300f;
        Mode mode = Mode.MRC;
        Path intermediateDir = null;

        // Legacy positional arguments, then key=value overrides.
        int i = 2;
        if (i < args.length && !isOption(args[i])) {
            sourcePdf = args[i++];
        }
        if (i < args.length && !isOption(args[i])) {
            dpi = Float.parseFloat(args[i++]);
        }
        if (i < args.length && !isOption(args[i])) {
            options.backgroundBitsPerPixel = Double.parseDouble(args[i++]);
        }
        if (i < args.length && !isOption(args[i])) {
            options.foregroundBitsPerPixel = Double.parseDouble(args[i++]);
        }
        for (; i < args.length; i++) {
            String arg = args[i];
            String lower = arg.toLowerCase(Locale.ROOT);
            if (lower.startsWith("mode=")) {
                mode = parseMode(arg.substring("mode=".length()));
            } else if (lower.startsWith("intermediate=")) {
                intermediateDir = Paths.get(arg.substring("intermediate=".length()))
                        .toAbsolutePath().normalize();
            } else {
                applyOption(options, arg);
            }
        }

        if (mode == Mode.TEXTMASK || mode == Mode.STENCIL) {
            if (!hasArgKey(args, "bgformat")) {
                options.backgroundFormat = MrcPdfBuilder.PlateFormat.JPX;
            }
            if (!hasArgKey(args, "bgscale")) {
                options.backgroundScale = 1.0;
            }
            if (!hasArgKey(args, "bgbpp")) {
                options.backgroundBitsPerPixel = 0.4;
            }
        }
        if (mode == Mode.TEXTMASK) {
            if (intermediateDir == null) {
                throw new IllegalArgumentException(
                        "mode=textmask requires intermediate=<ImageTrans intermediateResults dir>");
            }
            if (!Files.isDirectory(intermediateDir)) {
                throw new IllegalArgumentException("Not a directory: " + intermediateDir);
            }
        } else if (mode == Mode.STENCIL && intermediateDir != null
                && !Files.isDirectory(intermediateDir)) {
            throw new IllegalArgumentException("Not a directory: " + intermediateDir);
        }

        List<Path> images;
        try (Stream<Path> stream = Files.list(imagesDir)) {
            images = stream
                    .filter(p -> {
                        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return name.endsWith(".png") || name.endsWith(".jpg")
                                || name.endsWith(".jpeg");
                    })
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
        }
        if (images.isEmpty()) {
            throw new IllegalArgumentException("No PNG/JPEG images found in " + imagesDir);
        }

        List<float[]> pageSizes = null;
        if (!"-".equals(sourcePdf)) {
            pageSizes = readPageSizes(Paths.get(sourcePdf));
            if (pageSizes.size() != images.size()) {
                throw new IllegalArgumentException("Image count (" + images.size()
                        + ") does not match source page count (" + pageSizes.size() + ")");
            }
        }

        if (mode == Mode.LOSSLESS) {
            System.out.println("mode: lossless JPEG 2000, full resolution, bit-exact");
        } else if (mode == Mode.TEXTMASK) {
            String bgDetail = options.backgroundLossless ? "lossless"
                    : options.backgroundFormat == MrcPdfBuilder.PlateFormat.JPEG
                            ? "q=" + options.backgroundJpegQuality
                            : "bpp=" + options.backgroundBitsPerPixel;
            String fgDetail = options.backgroundLossless ? "lossless"
                    : "lossless/" + options.textForegroundBitsPerPixel + "bpp";
            System.out.printf(Locale.ROOT,
                    "mode: textmask (bg=%s scale %.2f %s, text fg %s, mask=%s, dilation %d)%n",
                    options.backgroundFormat, options.backgroundScale, bgDetail,
                    fgDetail, options.maskEncoding, options.textMaskDilation);
        } else if (mode == Mode.STENCIL) {
            System.out.printf(Locale.ROOT,
                    "mode: stencil%s (bg=%s scale %.2f %s, 1-bit stencils=%s, contrast %.0f S<=%.0f)%n",
                    intermediateDir == null ? " standalone (no ImageTrans intermediates)" : "",
                    options.backgroundFormat, options.backgroundScale,
                    options.backgroundFormat == MrcPdfBuilder.PlateFormat.JPEG
                            ? "q=" + options.backgroundJpegQuality
                            : "bpp=" + options.backgroundBitsPerPixel,
                    options.maskEncoding, options.stencilContrast,
                    options.stencilBlackMaxSaturation);
        } else {
            System.out.printf(Locale.ROOT,
                    "compress: bg=%s(scale %.2f, %s) fg=%s(%s) mask=%s ink=%s%n",
                    options.backgroundFormat, options.backgroundScale,
                    options.backgroundFormat == MrcPdfBuilder.PlateFormat.JPEG
                            ? "q=" + options.backgroundJpegQuality
                            : "bpp=" + options.backgroundBitsPerPixel,
                    options.foregroundFormat,
                    options.foregroundFormat == MrcPdfBuilder.PlateFormat.JPEG
                            ? "q=" + options.foregroundJpegQuality
                            : "bpp=" + options.foregroundBitsPerPixel,
                    options.maskEncoding, options.inkMode);
        }

        Files.createDirectories(outPdf.getParent());
        long totalStart = System.currentTimeMillis();
        try (MrcPdfBuilder builder = new MrcPdfBuilder(options)) {
            for (int n = 0; n < images.size(); n++) {
                Path image = images.get(n);
                int[] pixelSize = readImageSize(image);
                float widthPt;
                float heightPt;
                if (pageSizes != null) {
                    widthPt = pageSizes.get(n)[0];
                    heightPt = pageSizes.get(n)[1];
                } else {
                    widthPt = pixelSize[0] * 72f / dpi;
                    heightPt = pixelSize[1] * 72f / dpi;
                }
                long start = System.currentTimeMillis();
                if (mode == Mode.LOSSLESS) {
                    long bytes = builder.addLosslessPage(image, widthPt, heightPt);
                    System.out.printf(Locale.ROOT,
                            "page %2d/%d  %s  %dx%d px -> %.2f x %.2f pt  lossless %d KB  %d ms%n",
                            n + 1, images.size(), image.getFileName(), pixelSize[0], pixelSize[1],
                            widthPt, heightPt, bytes / 1024,
                            System.currentTimeMillis() - start);
                } else if (mode == Mode.TEXTMASK) {
                    Path[] intermediates = requireIntermediates(image, intermediateDir);
                    MrcPdfBuilder.TextMaskStats stats = builder.addTextMaskedPage(
                            image, intermediates[0], intermediates[1], widthPt, heightPt);
                    System.out.printf(Locale.ROOT,
                            "page %2d/%d  %s  text=%dpx bg=%dKB fg=%dKB mask=%dKB  %.2f x %.2f pt  %d ms%n",
                            n + 1, images.size(), image.getFileName(), stats.getTextPixels(),
                            stats.getBackgroundBytes() / 1024, stats.getForegroundBytes() / 1024,
                            stats.getMaskBytes() / 1024, widthPt, heightPt,
                            System.currentTimeMillis() - start);
                } else if (mode == Mode.STENCIL) {
                    MrcPdfBuilder.StencilStats stats;
                    if (intermediateDir == null) {
                        stats = builder.addStencilPage(image, widthPt, heightPt);
                    } else {
                        Path[] intermediates = requireIntermediates(image, intermediateDir);
                        stats = builder.addStencilPage(image, intermediates[0], intermediates[1],
                                widthPt, heightPt);
                    }
                    System.out.printf(Locale.ROOT,
                            "page %2d/%d  %s  black=%dpx/%dKB white=%dpx/%dKB bg=%dKB  %.2f x %.2f pt  %d ms%n",
                            n + 1, images.size(), image.getFileName(),
                            stats.getBlackPixels(), stats.getBlackBytes() / 1024,
                            stats.getWhitePixels(), stats.getWhiteBytes() / 1024,
                            stats.getBackgroundBytes() / 1024, widthPt, heightPt,
                            System.currentTimeMillis() - start);
                } else {
                    MrcPdfBuilder.PageLayers layers = builder.addPage(image, widthPt, heightPt);
                    boolean stencil = MrcPdfBuilder.useStencilFor(layers, options);
                    System.out.printf(Locale.ROOT,
                            "page %2d/%d  %s  %dx%d px -> %.2f x %.2f pt  ink=%5.1f%% sat=%5.1f val=%5.1f %s  %d ms%n",
                            n + 1, images.size(), image.getFileName(), pixelSize[0], pixelSize[1],
                            widthPt, heightPt, layers.getInkRatio() * 100,
                            layers.getInkMeanSaturation(), layers.getInkMeanValue(),
                            stencil ? "stencil" : "fg-plate",
                            System.currentTimeMillis() - start);
                }
            }
            builder.save(outPdf);
        }
        System.out.printf(Locale.ROOT, "saved %s (%d bytes, %.2f MB) in %.1f s%n",
                outPdf, Files.size(outPdf), Files.size(outPdf) / 1048576.0,
                (System.currentTimeMillis() - totalStart) / 1000.0);
    }

    private static boolean isOption(String arg) {
        return arg.indexOf('=') > 0;
    }

    private static boolean hasArgKey(String[] args, String key) {
        for (String arg : args) {
            int eq = arg.indexOf('=');
            if (eq > 0 && arg.substring(0, eq).equalsIgnoreCase(key)) {
                return true;
            }
        }
        return false;
    }

    private static Mode parseMode(String value) {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "mrc":
                return Mode.MRC;
            case "lossless":
                return Mode.LOSSLESS;
            case "textmask":
                return Mode.TEXTMASK;
            case "stencil":
                return Mode.STENCIL;
            default:
                throw new IllegalArgumentException("Unknown mode: " + value);
        }
    }

    private static Path[] requireIntermediates(Path image, Path intermediateDir) throws IOException {
        String base = image.getFileName().toString();
        Path maskPath = findIntermediate(intermediateDir, base + "-mask", ".png", ".jpg", ".jpeg");
        Path removedPath = findIntermediate(intermediateDir, base + "-text-removed",
                ".png", ".jpg", ".jpeg");
        if (maskPath == null || removedPath == null) {
            throw new IOException("Missing ImageTrans intermediates for " + base
                    + " (expected " + base + "-mask.png and " + base
                    + "-text-removed.png/.jpg)");
        }
        return new Path[] { maskPath, removedPath };
    }

    private static Path findIntermediate(Path dir, String base, String... extensions) {
        for (String extension : extensions) {
            Path path = dir.resolve(base + extension);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        return null;
    }

    private static void applyOption(MrcPdfBuilder.Options options, String arg) {
        int eq = arg.indexOf('=');
        if (eq <= 0) {
            throw new IllegalArgumentException("Expected key=value option, got: " + arg);
        }
        String key = arg.substring(0, eq).toLowerCase(Locale.ROOT);
        String value = arg.substring(eq + 1);
        switch (key) {
            case "bgformat":
                options.backgroundFormat = parsePlateFormat(value);
                break;
            case "fgformat":
                options.foregroundFormat = parsePlateFormat(value);
                break;
            case "bgscale":
                options.backgroundScale = Double.parseDouble(value);
                break;
            case "bgquality":
                options.backgroundJpegQuality = Float.parseFloat(value);
                break;
            case "fgquality":
                options.foregroundJpegQuality = Float.parseFloat(value);
                break;
            case "bgbpp":
                options.backgroundBitsPerPixel = Double.parseDouble(value);
                break;
            case "fgbpp":
                options.foregroundBitsPerPixel = Double.parseDouble(value);
                break;
            case "mask":
                options.maskEncoding = parseMaskEncoding(value);
                break;
            case "textmaskdilation":
                options.textMaskDilation = Integer.parseInt(value);
                break;
            case "textfgbpp":
                options.textForegroundBitsPerPixel = Double.parseDouble(value);
                break;
            case "textfgfillsigma":
                options.textForegroundFillSigma = Double.parseDouble(value);
                break;
            case "textmasktightenmargin":
                options.textMaskTightenMargin = Double.parseDouble(value);
                break;
            case "bglossless":
                options.backgroundLossless = parseBoolean(value);
                break;
            case "stencilcontrast":
                options.stencilContrast = Double.parseDouble(value);
                break;
            case "stencilmaxs":
                options.stencilBlackMaxSaturation = Double.parseDouble(value);
                break;
            case "stencildilation":
                options.stencilDilation = Integer.parseInt(value);
                break;
            case "stencilcleardilation":
                options.stencilClearDilation = Integer.parseInt(value);
                break;
            case "stencilclear":
                options.stencilClearBackground = parseBoolean(value);
                break;
            case "textdiffmargin":
                options.textDiffMargin = Double.parseDouble(value);
                break;
            case "textbinthreshold":
                options.textBinarizeThreshold = Double.parseDouble(value);
                break;
            case "fgmode":
                options.inkMode = parseInkMode(value);
                break;
            case "stencilsat":
                options.stencilMaxSaturation = Double.parseDouble(value);
                break;
            case "stencilval":
                options.stencilMaxValue = Double.parseDouble(value);
                break;
            case "blocksize":
                options.thresholdBlockSize = Integer.parseInt(value);
                break;
            case "thresholdc":
                options.thresholdC = Double.parseDouble(value);
                break;
            case "bgblur":
                options.backgroundBlurKernel = Integer.parseInt(value);
                break;
            case "maskopen":
                options.maskOpenKernel = Integer.parseInt(value);
                break;
            case "cleardilation":
                options.backgroundClearDilation = Integer.parseInt(value);
                break;
            default:
                throw new IllegalArgumentException("Unknown option: " + key);
        }
    }

    private static MrcPdfBuilder.PlateFormat parsePlateFormat(String value) {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "jpeg":
            case "jpg":
                return MrcPdfBuilder.PlateFormat.JPEG;
            case "jpx":
            case "jp2":
            case "jpeg2000":
                return MrcPdfBuilder.PlateFormat.JPX;
            default:
                throw new IllegalArgumentException("Unknown plate format: " + value);
        }
    }

    private static MrcPdfBuilder.MaskEncoding parseMaskEncoding(String value) {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "g4":
            case "ccitt":
            case "ccittg4":
                return MrcPdfBuilder.MaskEncoding.CCITT_G4;
            case "flate":
            case "zip":
                return MrcPdfBuilder.MaskEncoding.FLATE;
            default:
                throw new IllegalArgumentException("Unknown mask encoding: " + value);
        }
    }

    private static MrcPdfBuilder.InkMode parseInkMode(String value) {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "auto":
                return MrcPdfBuilder.InkMode.AUTO;
            case "image":
                return MrcPdfBuilder.InkMode.IMAGE;
            case "stencil":
                return MrcPdfBuilder.InkMode.STENCIL;
            default:
                throw new IllegalArgumentException("Unknown ink mode: " + value);
        }
    }

    private static boolean parseBoolean(String value) {
        switch (value.toLowerCase(Locale.ROOT)) {
            case "1":
            case "true":
            case "yes":
            case "on":
                return true;
            case "0":
            case "false":
            case "no":
            case "off":
                return false;
            default:
                throw new IllegalArgumentException("Expected boolean, got: " + value);
        }
    }

    private static void printUsage() {
        System.err.println("Usage: MrcPdfFromImages <imagesDir> <outPdf> [sourcePdf|-] [dpi=300] [bgBpp=0.4] [fgBpp=0.05] [key=value...]");
        System.err.println("Options: mode=mrc|lossless|textmask|stencil");
        System.err.println("         textmask: intermediate=<ImageTrans intermediateResults dir> (required)");
        System.err.println("         stencil:  intermediate=<dir> is optional; without it the");
        System.err.println("                   background is built from the original image");
        System.err.println("         stencil: stencilContrast=50 stencilMaxS=90 stencilDilation=0");
        System.err.println("                  stencilClear=0 stencilClearDilation=1");
        System.err.println("                  textDiffMargin=8 textBinThreshold=128");
        System.err.println("         bgFormat=jpeg|jpx bgScale=2 bgQuality=0.6 fgFormat=jpx|jpeg");
        System.err.println("         fgQuality=0.7 fgBpp=0.05 mask=g4|flate fgMode=auto|image|stencil");
        System.err.println("         textMaskDilation=1 textFgBpp=0.2 (0 = lossless)");
        System.err.println("         textFgFillSigma=0 textMaskTightenMargin=0");
        System.err.println("         bgLossless=1 (bit-exact textmask output, bgScale must be 1)");
        System.err.println("         stencilSat=24 stencilVal=110 blockSize=31 thresholdC=10");
        System.err.println("         bgBlur=9 maskOpen=3 clearDilation=3");
    }

    private static List<float[]> readPageSizes(Path pdf) throws IOException {
        List<float[]> sizes = new ArrayList<>();
        try (PDDocument doc = PDDocument.load(pdf.toFile())) {
            for (PDPage page : doc.getPages()) {
                PDRectangle box = page.getCropBox();
                int rot = ((page.getRotation() % 360) + 360) % 360;
                boolean swapped = rot == 90 || rot == 270;
                sizes.add(new float[] {
                        swapped ? box.getHeight() : box.getWidth(),
                        swapped ? box.getWidth() : box.getHeight()
                });
            }
        }
        return sizes;
    }

    private static int[] readImageSize(Path image) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(image.toFile())) {
            ImageReader reader = ImageIO.getImageReaders(in).next();
            try {
                reader.setInput(in);
                return new int[] { reader.getWidth(0), reader.getHeight(0) };
            } finally {
                reader.dispose();
            }
        }
    }
}
