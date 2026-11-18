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
 * <p>Extra {@code key=value} options: {@code bgFormat=jpeg|jpx},
 * {@code fgFormat=jpx|jpeg}, {@code bgScale=2}, {@code bgQuality=0.6},
 * {@code fgQuality=0.7}, {@code mask=g4|flate}, {@code fgMode=auto|image|stencil},
 * {@code stencilSat=24}, {@code stencilVal=110}, {@code blockSize=31},
 * {@code thresholdC=10}, {@code bgBlur=9}, {@code maskOpen=3},
 * {@code clearDilation=3}.</p>
 */
public final class MrcPdfFromImages {
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
            applyOption(options, args[i]);
        }

        List<Path> images;
        try (Stream<Path> stream = Files.list(imagesDir)) {
            images = stream
                    .filter(p -> p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".png"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .collect(Collectors.toList());
        }
        if (images.isEmpty()) {
            throw new IllegalArgumentException("No PNG images found in " + imagesDir);
        }

        List<float[]> pageSizes = null;
        if (!"-".equals(sourcePdf)) {
            pageSizes = readPageSizes(Paths.get(sourcePdf));
            if (pageSizes.size() != images.size()) {
                throw new IllegalArgumentException("Image count (" + images.size()
                        + ") does not match source page count (" + pageSizes.size() + ")");
            }
        }

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
            builder.save(outPdf);
        }
        System.out.printf(Locale.ROOT, "saved %s (%d bytes, %.2f MB) in %.1f s%n",
                outPdf, Files.size(outPdf), Files.size(outPdf) / 1048576.0,
                (System.currentTimeMillis() - totalStart) / 1000.0);
    }

    private static boolean isOption(String arg) {
        return arg.indexOf('=') > 0;
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

    private static void printUsage() {
        System.err.println("Usage: MrcPdfFromImages <imagesDir> <outPdf> [sourcePdf|-] [dpi=300] [bgBpp=0.4] [fgBpp=0.05] [key=value...]");
        System.err.println("Options: bgFormat=jpeg|jpx bgScale=2 bgQuality=0.6 fgFormat=jpx|jpeg");
        System.err.println("         fgQuality=0.7 fgBpp=0.05 mask=g4|flate fgMode=auto|image|stencil");
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
