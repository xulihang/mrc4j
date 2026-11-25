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
import java.nio.file.StandardCopyOption;
import java.util.List;

import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Small B4J/Java-friendly entry point for hosts that already ship the
 * dependencies (PDFBox 2.0.31, jai-imageio 1.4.0, ...).
 *
 * <p>ImageTrans bundles exactly those jars, so add the <em>thin</em> jar
 * ({@code mvn -Pthin package}) instead of the shaded jar; the thin jar contains
 * only {@code com.xulihang.mrc4j} classes and does not duplicate PDFBox/JAI.</p>
 *
 * <p>Per-image use (inside the export loop, so progress can be shown per page):</p>
 * <pre>
 * Dim mrc As JavaObject
 * mrc.InitializeStatic("com.xulihang.mrc4j.Mrc4j")
 * mrc.RunMethod("addPageImageFromFile",
 *         Array(doc, contentStream, imagePath, x, y, width, height))
 * </pre>
 */
public final class Mrc4j {

    private Mrc4j() {
    }

    /**
     * Compress one image file into MRC layers and draw the background +
     * foreground (with its mask) into an already open page content stream.
     *
     * @return the encoded layer bytes (background + foreground + mask)
     */
    public static long addPageImageFromFile(PDDocument doc, PDPageContentStream contentStream,
                                            String imagePath, double x, double y,
                                            double width, double height) throws IOException {
        return addPageImageFromFile(doc, contentStream, imagePath, x, y, width, height,
                0.4, 0.2, 3);
    }

    /** Per-image MRC drawing from an image file, with explicit rates. */
    public static long addPageImageFromFile(PDDocument doc, PDPageContentStream contentStream,
                                            String imagePath, double x, double y,
                                            double width, double height,
                                            double fgBitsPerPixel, double bgBitsPerPixel,
                                            int bgDownsample) throws IOException {
        PixelBuffer pixels = ImageCodec.decode(Files.readAllBytes(new File(imagePath).toPath()));
        return addPageImage(doc, contentStream, pixels, x, y, width, height,
                fgBitsPerPixel, bgBitsPerPixel, bgDownsample);
    }

    /** Per-image MRC drawing from an already decoded PDF image. */
    public static long addPageImage(PDDocument doc, PDPageContentStream contentStream,
                                    PDImageXObject source, double x, double y,
                                    double width, double height) throws IOException {
        return addPageImage(doc, contentStream, source, x, y, width, height,
                0.4, 0.2, 3);
    }

    /** Per-image MRC drawing from an already decoded PDF image, with rates. */
    public static long addPageImage(PDDocument doc, PDPageContentStream contentStream,
                                    PDImageXObject source, double x, double y,
                                    double width, double height,
                                    double fgBitsPerPixel, double bgBitsPerPixel,
                                    int bgDownsample) throws IOException {
        PixelBuffer pixels = ImageCodec.fromBufferedImage(source.getImage());
        return addPageImage(doc, contentStream, pixels, x, y, width, height,
                fgBitsPerPixel, bgBitsPerPixel, bgDownsample);
    }

    private static long addPageImage(PDDocument doc, PDPageContentStream contentStream,
                                     PixelBuffer pixels, double x, double y,
                                     double width, double height,
                                     double fgBitsPerPixel, double bgBitsPerPixel,
                                     int bgDownsample) throws IOException {
        PdfCompressor.Options options = options(fgBitsPerPixel, bgBitsPerPixel, bgDownsample);
        PdfCompressor.ImageResult result = PdfCompressor.encodePixels(
                pixels, pixels.getWidth(), pixels.getHeight(), 0, "mrc", options);
        PDImageXObject background = MrcImages.background(doc, result);
        PDImageXObject foreground = MrcImages.foreground(doc, result);
        contentStream.drawImage(background, (float) x, (float) y,
                (float) width, (float) height);
        contentStream.drawImage(foreground, (float) x, (float) y,
                (float) width, (float) height);
        long bytes = background.getCOSObject().getLength()
                + foreground.getCOSObject().getLength();
        COSBase softMask = foreground.getCOSObject().getDictionaryObject(COSName.SMASK);
        if (softMask instanceof COSStream) {
            bytes += ((COSStream) softMask).getLength();
        }
        return bytes;
    }

    /**
     * Compress the page-level images of an already loaded PDF page in place.
     * Image draws are replaced at the same content-stream position, so text
     * and vector content keep their order. Save the document afterwards.
     *
     * @return approximate encoded layer bytes for this page
     */
    public static long compressPage(PDDocument doc, int pageIndex) throws IOException {
        return compressPage(doc, pageIndex, 0.4, 0.2, 3);
    }

    /** Compress one page of an existing PDF, with explicit rates. */
    public static long compressPage(PDDocument doc, int pageIndex, double fgBitsPerPixel,
                                    double bgBitsPerPixel, int bgDownsample)
            throws IOException {
        PdfCompressor.Options options = options(fgBitsPerPixel, bgBitsPerPixel, bgDownsample);
        PdfCompressor compressor = new PdfCompressor(doc, options);
        List<PdfCompressor.ImageResult> results =
                new PageCompressor(doc, compressor).compress(doc.getPage(pageIndex), pageIndex + 1);
        long bytes = 0;
        for (PdfCompressor.ImageResult result : results) {
            bytes += result.bgJp2().length + result.fgJp2().length;
            if (result.maskJbig2() != null) {
                bytes += result.maskJbig2().length;
            }
        }
        return bytes;
    }

    /** Compress a PDF in place using JAI JPEG 2000, a built-in JBIG2 mask and 0.4/0.2 bpp. */
    public static void compressInPlace(String pdfPath) throws IOException {
        compressInPlace(pdfPath, 0.4, 0.2, 3);
    }    /** Compress a PDF in place. */
    public static void compressInPlace(String pdfPath, double fgBitsPerPixel,
                                       double bgBitsPerPixel, int bgDownsample)
            throws IOException {
        File in = new File(pdfPath).getAbsoluteFile();
        if (!in.isFile()) {
            throw new IOException("PDF not found: " + pdfPath);
        }
        File tmp = new File(in.getParentFile(), in.getName() + ".mrc-tmp.pdf");
        try {
            compress(in.getAbsolutePath(), tmp.getAbsolutePath(),
                    fgBitsPerPixel, bgBitsPerPixel, bgDownsample);
            Files.move(tmp.toPath(), in.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(tmp.toPath());
        }
    }

    /** Compress {@code inputPdf} to {@code outputPdf} with the default settings. */
    public static void compress(String inputPdf, String outputPdf) throws IOException {
        compress(inputPdf, outputPdf, 0.4, 0.2, 3);
    }

    /** Compress {@code inputPdf} to {@code outputPdf}. */
    public static void compress(String inputPdf, String outputPdf,
                                double fgBitsPerPixel, double bgBitsPerPixel,
                                int bgDownsample) throws IOException {
        PdfCompressor.compress(new File(inputPdf), new File(outputPdf),
                options(fgBitsPerPixel, bgBitsPerPixel, bgDownsample));
    }

    private static PdfCompressor.Options options(double fgBitsPerPixel,
                                                 double bgBitsPerPixel,
                                                 int bgDownsample) {
        PdfCompressor.Options options = new PdfCompressor.Options();
        options.maskFallback = PdfCompressor.MaskFallback.G4;
        options.jpeg2000.encoder = PdfCompressor.Jpeg2000Options.Encoder.JAVA;
        options.jpeg2000.fgBitsPerPixel = fgBitsPerPixel;
        options.jpeg2000.bgBitsPerPixel = bgBitsPerPixel;
        options.mrc.bgDownsample = bgDownsample;
        return options;
    }
}
