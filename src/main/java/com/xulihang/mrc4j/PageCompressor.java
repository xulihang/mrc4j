package com.xulihang.mrc4j;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.util.Matrix;

/**
 * Port of {@code compress_page_images()} in bin/compress-pdf-images.
 *
 * <p>Every image that is drawn on the page is extracted, MRC-compressed and
 * removed from the page content. The compressed background and foreground
 * (with the 1-bit mask as {@code /SMask}) are then appended with the same
 * placement rectangle, so the visual result is the same but far smaller, and
 * the original text layer stays where it was.</p>
 */
final class PageCompressor {

    private final PDDocument doc;
    private final PdfCompressor compressor;
    private final PdfCompressor.Options options;

    PageCompressor(PDDocument doc, PdfCompressor compressor, PdfCompressor.Options options) {
        this.doc = doc;
        this.compressor = compressor;
        this.options = options;
    }

    /** Replacements for one drawing of an image. */
    static final class Layers {
        private final String bg;
        private final String fg;

        Layers(String bg, String fg) {
            this.bg = bg;
            this.fg = fg;
        }

        String bg() {
            return bg;
        }

        String fg() {
            return fg;
        }
    }

    List<PdfCompressor.ImageResult> compress(PDPage page, int pageNumber) throws IOException {
        List<PdfCompressor.ImageResult> results = new ArrayList<>();
        List<DrawScanner.Draw> draws = DrawScanner.scan(page);
        if (draws.isEmpty()) {
            return results;
        }

        // Compress each distinct image once (shared images are not uncommon).
        int slot = 0;
        List<Placement> placements = new ArrayList<>();
        java.util.Map<COSDictionary, PdfCompressor.ImageResult> shared = new java.util.IdentityHashMap<>();
        java.util.Map<COSDictionary, Layers> sharedLayers = new java.util.IdentityHashMap<>();
        for (DrawScanner.Draw draw : draws) {
            String name = "fzImg" + slot;
            PdfCompressor.ImageResult r = shared.get(draw.image.getCOSObject());
            if (r == null) {
                r = compressor.compressImage(draw.image, pageNumber, name);
                results.add(r);
                shared.put(draw.image.getCOSObject(), r);
            }
            placements.add(new Placement(draw, r, name, shared, sharedLayers));
            slot++;
        }

        // Take the originals off the page.
        for (Placement p : placements) {
            DrawScanner.removeDraws(page, p.draw.name);
            PDResources pageRes = page.getResources();
            var xobjects = pageRes == null ? null : pageRes.getCOSObject().getCOSDictionary(COSName.XOBJECT);
            if (xobjects != null) {
                xobjects.removeItem(p.draw.name);
            }
        }

        // Draw the MRC layers in the original order.
        PDResources res = page.getResources();
        if (res == null) {
            res = new PDResources();
            page.setResources(res);
        }
        PDPageContentStream cs = new PDPageContentStream(doc, page,
                PDPageContentStream.AppendMode.APPEND, true, true);
        try {
            for (Placement p : placements) {
                Layers layers = p.layers(doc, res);
                cs.saveGraphicsState();
                placeImage(cs, layers.bg(), p);
                placeImage(cs, layers.fg(), p);
                cs.restoreGraphicsState();
            }
        } finally {
            cs.close();
        }
        return results;
    }

    /**
     * Draw the replacement image with exactly the CTM the original image was
     * drawn with, so orientation, rotation and placement are preserved.
     */
    void placeImage(PDPageContentStream cs, String name, Placement p) throws IOException {
        Matrix m = p.draw.ctm;
        cs.appendRawCommands("q " + fmt(m.getScaleX()) + " " + fmt(m.getShearY()) + " "
                + fmt(m.getShearX()) + " " + fmt(m.getScaleY()) + " "
                + fmt(m.getTranslateX()) + " " + fmt(m.getTranslateY())
                + " cm /" + name + " Do Q\n");
    }

    static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.6f", v);
    }

    /** One compressed image placement. */
    static final class Placement {
        final DrawScanner.Draw draw;
        final PdfCompressor.ImageResult result;
        final String name;
        final java.util.Map<COSDictionary, PdfCompressor.ImageResult> shared;
        final java.util.Map<COSDictionary, Layers> sharedLayers;

        Placement(DrawScanner.Draw draw, PdfCompressor.ImageResult result, String name,
                  java.util.Map<COSDictionary, PdfCompressor.ImageResult> shared,
                  java.util.Map<COSDictionary, Layers> sharedLayers) {
            this.draw = draw;
            this.result = result;
            this.name = name;
            this.shared = shared;
            this.sharedLayers = sharedLayers;
        }

        Layers layers(PDDocument doc, PDResources res) throws IOException {
            Layers existing = sharedLayers.get(draw.image.getCOSObject());
            if (existing != null) {
                return existing;
            }
            String bg = MrcImages.addBackground(doc, res, name + "Bg", result);
            String fg = MrcImages.addForeground(doc, res, name, result);
            Layers l = new Layers(bg, fg);
            sharedLayers.put(draw.image.getCOSObject(), l);
            return l;
        }
    }
}
