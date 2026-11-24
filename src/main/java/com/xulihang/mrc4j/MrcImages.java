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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.FilterFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceGray;
import org.apache.pdfbox.pdmodel.graphics.color.PDDeviceRGB;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

/**
 * Builds the PDF image objects for the MRC layers.
 *
 * <p>JPEG2000 data is embedded as raw {@code /JPXDecode} streams. The mask
 * is embedded as a 1-bit {@code /SMask}: JBIG2 ({@code jbig2 -p} output)
 * when jbig2enc is available, otherwise as CCITT Group 4.</p>
 */
final class MrcImages {

    private MrcImages() {
    }

    static String addBackground(PDDocument doc, PDResources res, String name,
                                PdfCompressor.ImageResult r) throws IOException {
        PDImageXObject img = background(doc, r);
        xobjects(res).setItem(COSName.getPDFName(name), img.getCOSObject());
        return name;
    }

    static String addForeground(PDDocument doc, PDResources res, String name,
                                PdfCompressor.ImageResult r) throws IOException {
        PDImageXObject img = foreground(doc, r);
        xobjects(res).setItem(COSName.getPDFName(name), img.getCOSObject());
        return name;
    }

    /** The background layer as an image XObject (for direct drawing). */
    static PDImageXObject background(PDDocument doc, PdfCompressor.ImageResult r) throws IOException {
        return image(doc, r.bgJp2(), r.bgWidth(), r.bgHeight(), r.bgChannels());
    }

    /** The foreground layer with the mask as its /SMask (for direct drawing). */
    static PDImageXObject foreground(PDDocument doc, PdfCompressor.ImageResult r) throws IOException {
        PDImageXObject img = image(doc, r.fgJp2(), r.fgWidth(), r.fgHeight(), r.fgChannels());
        img.getCOSObject().setItem(COSName.SMASK, mask(doc, r));
        return img;
    }

    private static org.apache.pdfbox.cos.COSDictionary xobjects(PDResources res) {
        var dict = res.getCOSObject().getCOSDictionary(COSName.XOBJECT);
        if (dict == null) {
            dict = new org.apache.pdfbox.cos.COSDictionary();
            res.getCOSObject().setItem(COSName.XOBJECT, dict);
        }
        return dict;
    }

    /** A new image XObject whose (still empty) data stream is ready to write. */
    private static PDImageXObject newImage(PDDocument doc) throws IOException {
        return new PDImageXObject(new PDStream(doc, InputStream.nullInputStream()), new PDResources());
    }

    private static void write(PDImageXObject img, byte[] data) throws IOException {
        img.getCOSObject().removeItem(COSName.LENGTH);
        try (OutputStream os = img.getCOSObject().createRawOutputStream()) {
            os.write(data);
        }
    }

    private static PDImageXObject image(PDDocument doc, byte[] jp2, int w, int h, int channels) throws IOException {
        PDImageXObject img = newImage(doc);
        // PDImageXObject(PDStream, ...) leaves Width/Height unset; fill the
        // standard entries explicitly.
        img.getCOSObject().setInt(COSName.WIDTH, w);
        img.getCOSObject().setInt(COSName.HEIGHT, h);
        img.getCOSObject().setItem(COSName.BITS_PER_COMPONENT, COSInteger.get(8));
        // Background/foreground are DeviceGray (1 component) or DeviceRGB (3).
        img.getCOSObject().setItem(COSName.COLORSPACE, channels > 1 ? PDDeviceRGB.INSTANCE : PDDeviceGray.INSTANCE);
        if (ImageCodec.isJpeg2000(jp2)) {
            img.getCOSObject().setItem(COSName.FILTER, COSName.JPX_DECODE);
            write(img, jp2);
        } else if (ImageCodec.isPng(jp2)) {
            img.getCOSObject().setItem(COSName.FILTER, COSName.FLATE_DECODE);
            var parms = new org.apache.pdfbox.cos.COSDictionary();
            parms.setInt(COSName.PREDICTOR, 12);
            parms.setInt(COSName.COLORS, channels);
            parms.setInt(COSName.BITS_PER_COMPONENT, 8);
            parms.setInt(COSName.COLUMNS, w);
            img.getCOSObject().setItem(COSName.DECODE_PARMS, parms);
            write(img, ImageCodec.pngToUpPredictedZlib(jp2));
        } else {
            throw new IOException("unsupported layer encoding");
        }
        return img;
    }

    private static PDImageXObject mask(PDDocument doc, PdfCompressor.ImageResult r) throws IOException {
        PDImageXObject mask = newImage(doc);
        mask.getCOSObject().setInt(COSName.WIDTH, r.width());
        mask.getCOSObject().setInt(COSName.HEIGHT, r.height());
        mask.getCOSObject().setItem(COSName.BITS_PER_COMPONENT, COSInteger.ONE);
        mask.getCOSObject().setItem(COSName.COLORSPACE, PDDeviceGray.INSTANCE);
        if (r.maskJbig2() != null) {
            mask.getCOSObject().setItem(COSName.FILTER, COSName.JBIG2_DECODE);
            write(mask, r.maskJbig2());
        } else if (r.mask() != null
                && r.maskFallback() == PdfCompressor.MaskFallback.G4) {
            writeG4(doc, mask, r.mask());
        } else if (r.mask() != null) {
            mask.getCOSObject().setItem(COSName.FILTER, COSName.FLATE_DECODE);
            write(mask, ImageCodec.deflate(Png1Bit.packBits(r.mask())));
        } else {
            // No mask at all: fully opaque
            mask.getCOSObject().setItem(COSName.FILTER, COSName.FLATE_DECODE);
        }
        return mask;
    }

    /**
     * Embed the 1-bit mask as CCITT Group 4. This is the pure-Java equivalent
     * of pdfbeads' / the archive tool's JBIG2 or CCITT mask and is much smaller
     * than a Flate-compressed bitmap.
     */
    private static void writeG4(PDDocument doc, PDImageXObject img, Mask mask) throws IOException {
        int width = mask.getWidth();
        int height = mask.getHeight();
        COSDictionary encodeParams = new COSDictionary();
        encodeParams.setInt(COSName.COLUMNS, width);
        encodeParams.setInt(COSName.ROWS, height);
        encodeParams.setInt(COSName.K, -1);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        FilterFactory.INSTANCE.getFilter(COSName.CCITTFAX_DECODE)
                .encode(new ByteArrayInputStream(Png1Bit.packBits(mask)), encoded,
                        encodeParams, 0);

        COSDictionary decodeParams = new COSDictionary();
        decodeParams.setInt(COSName.K, -1);
        decodeParams.setInt(COSName.COLUMNS, width);
        decodeParams.setInt(COSName.ROWS, height);
        // Mask bits are 1 for text; as an SMask that must decode to white
        // (opaque).
        decodeParams.setBoolean(COSName.BLACK_IS_1, true);
        img.getCOSObject().setItem(COSName.FILTER, COSName.CCITTFAX_DECODE);
        img.getCOSObject().setItem(COSName.DECODE_PARMS, decodeParams);
        write(img, encoded.toByteArray());
    }
}
