package com.xulihang.mrc4j;

import java.io.IOException;
import java.io.InputStream;

import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
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
 * <p>JPEG2000 data is embedded as raw {@code /JPXDecode} streams, exactly like
 * the Python tool does when it hands the {@code .jp2} file to
 * {@code page.insert_image}. The mask is embedded as a 1-bit {@code /SMask}:
 * JBIG2 ({@code jbig2 -p} output) when jbig2enc is available, otherwise as
 * CCITT Group 4, which is what the Python tool's PNG-mask fallback becomes
 * inside PDFs as well.</p>
 */
final class MrcImages {

    private MrcImages() {
    }

    static String addBackground(PDDocument doc, PDResources res, String name,
                                PdfCompressor.ImageResult r) throws IOException {
        PDImageXObject img = image(doc, r.bgJp2(), r.bgWidth(), r.bgHeight());
        xobjects(res).setItem(COSName.getPDFName(name), img.getCOSObject());
        return name;
    }

    static String addForeground(PDDocument doc, PDResources res, String name,
                                PdfCompressor.ImageResult r) throws IOException {
        PDImageXObject img = image(doc, r.fgJp2(), r.fgWidth(), r.fgHeight());
        img.getCOSObject().setItem(COSName.SMASK, mask(doc, r));
        xobjects(res).setItem(COSName.getPDFName(name), img.getCOSObject());
        return name;
    }

    private static org.apache.pdfbox.cos.COSDictionary xobjects(PDResources res) {
        var dict = res.getCOSObject().getCOSDictionary(COSName.XOBJECT);
        if (dict == null) {
            dict = new org.apache.pdfbox.cos.COSDictionary();
            res.getCOSObject().setItem(COSName.XOBJECT, dict);
        }
        return dict;
    }

    private static PDImageXObject image(PDDocument doc, byte[] jp2, int w, int h) throws IOException {
        PDImageXObject img = new PDImageXObject(new PDStream(doc, java.io.InputStream.nullInputStream()),
                new PDResources());
        // PDImageXObject(PDStream, ...) leaves Width/Height unset; fill the
        // standard entries explicitly.
        img.getCOSObject().setInt(COSName.WIDTH, w);
        img.getCOSObject().setInt(COSName.HEIGHT, h);
        img.getCOSObject().setItem(COSName.BITS_PER_COMPONENT, org.apache.pdfbox.cos.COSInteger.get(8));
        img.getCOSObject().setItem(COSName.COLORSPACE, PDDeviceRGB.INSTANCE);
        if (ImageCodec.isJpeg2000(jp2)) {
            img.getCOSObject().setItem(COSName.FILTER, COSName.JPX_DECODE);
        } else if (ImageCodec.isPng(jp2)) {
            img.getCOSObject().setItem(COSName.FILTER, COSName.FLATE_DECODE);
            var parms = new org.apache.pdfbox.cos.COSDictionary();
            parms.setInt(COSName.PREDICTOR, 12);
            parms.setInt(COSName.COLORS, 3);
            parms.setInt(COSName.BITS_PER_COMPONENT, 8);
            parms.setInt(COSName.COLUMNS, w);
            img.getCOSObject().setItem(COSName.DECODE_PARMS, parms);
            try (var os = img.getCOSObject().createRawOutputStream()) {
                os.write(ImageCodec.pngToUpPredictedZlib(jp2));
            }
            return img;
        } else {
            throw new IOException("unsupported layer encoding");
        }
        img.getCOSObject().removeItem(COSName.LENGTH);
        try (var os = img.getCOSObject().createRawOutputStream()) {
            os.write(jp2);
        }
        return img;
    }

    private static PDImageXObject mask(PDDocument doc, PdfCompressor.ImageResult r) throws IOException {
        PDImageXObject mask = new PDImageXObject(new PDStream(doc, InputStream.nullInputStream()), new PDResources());
        mask.getCOSObject().setInt(COSName.WIDTH, r.width());
        mask.getCOSObject().setInt(COSName.HEIGHT, r.height());
        mask.getCOSObject().setItem(COSName.BITS_PER_COMPONENT, COSInteger.ONE);
        mask.getCOSObject().setItem(COSName.COLORSPACE, PDDeviceGray.INSTANCE);
        mask.getCOSObject().removeItem(COSName.LENGTH);
        if (r.mask() != null && r.maskJbig2() == null) {
            // Without jbig2enc the mask is stored as a Flate-compressed 1-bit
            // image: bigger than JBIG2 but supported everywhere. (The Python
            // tool's PNG fallback ends up as CCITT G4 in the PDF.)
            mask.getCOSObject().setItem(COSName.FILTER, COSName.FLATE_DECODE);
            try (var os = mask.getCOSObject().createRawOutputStream()) {
                os.write(ImageCodec.deflate(Png1Bit.packBits(r.mask())));
            }
        } else if (r.maskJbig2() != null) {
            mask.getCOSObject().setItem(COSName.FILTER, COSName.JBIG2_DECODE);
            try (var os = mask.getCOSObject().createRawOutputStream()) {
                os.write(r.maskJbig2());
            }
        } else {
            // No mask at all: fully opaque
            mask.getCOSObject().setItem(COSName.FILTER, COSName.FLATE_DECODE);
        }
        return mask;
    }
}
