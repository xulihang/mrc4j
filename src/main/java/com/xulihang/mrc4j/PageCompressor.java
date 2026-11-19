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

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;

/**
 * Replaces the page-level images with MRC layers in place.
 *
 * <p>Each {@code /Name Do} image draw is rewritten to draw the background and
 * then the foreground (with its mask) at the same position in the content
 * stream. Unlike appending the layers at the end of the page, this preserves
 * the original drawing order, so a text layer or vector art drawn after the
 * image stays visible on top.</p>
 */
final class PageCompressor {

    private final PDDocument doc;
    private final PdfCompressor compressor;

    PageCompressor(PDDocument doc, PdfCompressor compressor) {
        this.doc = doc;
        this.compressor = compressor;
    }

    /** Resource names for one image's MRC background and foreground. */
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

        PDResources res = page.getResources();
        if (res == null) {
            res = new PDResources();
            page.setResources(res);
        }

        // Compress each distinct image once (shared images are not uncommon).
        Map<COSDictionary, PdfCompressor.ImageResult> shared = new IdentityHashMap<>();
        Map<COSDictionary, Layers> sharedLayers = new IdentityHashMap<>();
        Map<COSName, Layers> replacements = new HashMap<>();
        int slot = 0;
        for (DrawScanner.Draw draw : draws) {
            // Images inside form XObjects need the form's resources and
            // content stream rewritten as well; leave them untouched here.
            if (draw.depth != 0) {
                continue;
            }
            PdfCompressor.ImageResult result = shared.get(draw.image.getCOSObject());
            if (result == null) {
                result = compressor.compressImage(draw.image, pageNumber, "mrc" + slot);
                results.add(result);
                shared.put(draw.image.getCOSObject(), result);
            }
            Layers layers = sharedLayers.get(draw.image.getCOSObject());
            if (layers == null) {
                String bgName = uniqueName(res, "mrcBg" + slot);
                String fgName = uniqueName(res, "mrcFg" + slot);
                MrcImages.addBackground(doc, res, bgName, result);
                MrcImages.addForeground(doc, res, fgName, result);
                layers = new Layers(bgName, fgName);
                sharedLayers.put(draw.image.getCOSObject(), layers);
            }
            replacements.put(draw.name, layers);
            slot++;
        }
        if (replacements.isEmpty()) {
            return results;
        }

        // Replace each image draw in place, preserving the content order.
        for (java.util.Iterator<?> it = page.getContentStreams(); it.hasNext(); ) {
            COSStream stream = (COSStream) ((PDStream) it.next()).getCOSObject();
            DrawScanner.replaceImageDraws(stream, replacements);
        }

        // The original image XObjects are no longer referenced by this page.
        COSDictionary xobjects = res.getCOSObject().getCOSDictionary(COSName.XOBJECT);
        if (xobjects != null) {
            for (COSName original : replacements.keySet()) {
                xobjects.removeItem(original);
            }
        }
        return results;
    }

    private static String uniqueName(PDResources res, String base) {
        COSDictionary xobjects = res.getCOSObject().getCOSDictionary(COSName.XOBJECT);
        if (xobjects == null) {
            return base;
        }
        String name = base;
        int suffix = 1;
        while (xobjects.containsKey(COSName.getPDFName(name))) {
            name = base + "_" + suffix++;
        }
        return name;
    }
}
