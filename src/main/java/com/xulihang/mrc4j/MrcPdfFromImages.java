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
 * <p>Usage: {@code MrcPdfFromImages <imagesDir> <outPdf> [sourcePdf|-] [dpi=300] [bgBpp=0.4] [fgBpp=0.3]}</p>
 *
 * <p>If a source PDF is given, its per-page displayed sizes are reused as page
 * sizes; otherwise sizes are derived from the pixel dimensions and the DPI.</p>
 */
public final class MrcPdfFromImages {
    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.err.println("Usage: MrcPdfFromImages <imagesDir> <outPdf> [sourcePdf|-] [dpi=300] [bgBpp=0.4] [fgBpp=0.3]");
            System.exit(2);
        }
        Path imagesDir = Paths.get(args[0]).toAbsolutePath().normalize();
        Path outPdf = Paths.get(args[1]).toAbsolutePath().normalize();
        String sourcePdf = args.length >= 3 ? args[2] : "-";
        float dpi = args.length >= 4 ? Float.parseFloat(args[3]) : 300f;
        MrcPdfBuilder.Options options = new MrcPdfBuilder.Options();
        if (args.length >= 5) {
            options.backgroundBitsPerPixel = Double.parseDouble(args[4]);
        }
        if (args.length >= 6) {
            options.foregroundBitsPerPixel = Double.parseDouble(args[5]);
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

        Files.createDirectories(outPdf.getParent());
        long totalStart = System.currentTimeMillis();
        try (MrcPdfBuilder builder = new MrcPdfBuilder(options)) {
            for (int i = 0; i < images.size(); i++) {
                Path image = images.get(i);
                int[] pixelSize = readImageSize(image);
                float widthPt;
                float heightPt;
                if (pageSizes != null) {
                    widthPt = pageSizes.get(i)[0];
                    heightPt = pageSizes.get(i)[1];
                } else {
                    widthPt = pixelSize[0] * 72f / dpi;
                    heightPt = pixelSize[1] * 72f / dpi;
                }
                long start = System.currentTimeMillis();
                builder.addPage(image, widthPt, heightPt);
                System.out.printf(Locale.ROOT, "page %2d/%d  %s  %dx%d px -> %.2f x %.2f pt  %d ms%n",
                        i + 1, images.size(), image.getFileName(), pixelSize[0], pixelSize[1],
                        widthPt, heightPt, System.currentTimeMillis() - start);
            }
            builder.save(outPdf);
        }
        System.out.printf(Locale.ROOT, "saved %s (%d bytes, %.2f MB) in %.1f s%n",
                outPdf, Files.size(outPdf), Files.size(outPdf) / 1048576.0,
                (System.currentTimeMillis() - totalStart) / 1000.0);
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
