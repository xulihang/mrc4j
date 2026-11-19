package com.xulihang.mrc4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.util.Matrix;

/**
 * Finds the images a page draws ({@code /name Do}) with the CTM in effect at
 * that point, tracking q/Q/cm across content streams and into form XObjects,
 * like {@code Page.get_image_bbox()} in PyMuPDF. Also removes image drawings
 * again ({@code remove_images()} in bin/compress-pdf-images).
 */
final class DrawScanner {

    private DrawScanner() {
    }

    /** One image drawing operation. */
    static final class Draw {
        final COSName name;
        final PDImageXObject image;
        final Matrix ctm;

        Draw(COSName name, PDImageXObject image, Matrix ctm) {
            this.name = name;
            this.image = image;
            this.ctm = ctm;
        }
    }

    private static final int MAX_FORM_DEPTH = 12;

    static List<Draw> scan(PDPage page) throws IOException {
        List<Draw> out = new ArrayList<>();
        PDResources res = page.getResources();
        for (java.util.Iterator<?> it = page.getContentStreams(); it.hasNext(); ) {
            COSStream stream = (COSStream) ((PDStream) it.next()).getCOSObject();
            walkStream(stream, res == null ? new PDResources() : res, new Matrix(), out, 0);
        }
        return out;
    }

    private static void walkStream(COSStream stream, PDResources res, Matrix base,
                                   List<Draw> out, int depth) throws IOException {
        List<Object> tokens;
        try {
            PDFStreamParser parser = new PDFStreamParser(decoded(stream));
            parser.parse();
            tokens = parser.getTokens();
        } catch (IOException e) {
            return;
        }
        Matrix ctm = base.clone();
        List<Matrix> stack = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            if (!(tokens.get(i) instanceof Operator op)) {
                continue;
            }
            switch (op.getName()) {
                case "q" -> stack.add(ctm.clone());
                case "Q" -> {
                    if (!stack.isEmpty()) {
                        ctm = stack.remove(stack.size() - 1);
                    }
                }
                case "cm" -> {
                    if (i >= 6) {
                        try {
                            Matrix m = new Matrix(num(tokens.get(i - 6)), num(tokens.get(i - 5)),
                                    num(tokens.get(i - 4)), num(tokens.get(i - 3)),
                                    num(tokens.get(i - 2)), num(tokens.get(i - 1)));
                            ctm = m.multiply(ctm);
                        } catch (IllegalArgumentException ignored) {
                            // not a well-formed cm
                        }
                    }
                }
                case "Do" -> {
                    if (i >= 1 && tokens.get(i - 1) instanceof COSName name) {
                        PDXObject x;
                        try {
                            x = res.getXObject(name);
                        } catch (IOException e) {
                            break;
                        }
                        if (x instanceof PDImageXObject img) {
                            out.add(new Draw(name, img, ctm.clone()));
                        } else if (x != null && depth < MAX_FORM_DEPTH) {
                            COSStream form = (COSStream) x.getCOSObject();
                            PDResources formRes = form.getCOSDictionary(COSName.RESOURCES) == null
                                    ? res : new PDResources(form.getCOSDictionary(COSName.RESOURCES));
                            for (COSStream sub : formStreams(form)) {
                                walkStream(sub, formRes, ctm, out, depth + 1);
                            }
                        }
                    }
                }
                default -> {
                    // other operators leave the CTM untouched or are irrelevant
                }
            }
        }
    }

    /** The content streams of a form XObject (normally a single stream). */
    static List<COSStream> formStreams(COSStream form) throws IOException {
        List<COSStream> out = new ArrayList<>();
        org.apache.pdfbox.cos.COSBase contents = form.getDictionaryObject(COSName.CONTENTS);
        if (contents instanceof org.apache.pdfbox.cos.COSArray arr) {
            for (int i = 0; i < arr.size(); i++) {
                if (arr.getObject(i) instanceof COSStream s) {
                    out.add(s);
                }
            }
        } else {
            out.add(form);
        }
        return out;
    }

    /** Fully decoded content stream bytes (filters applied). */
    static byte[] decoded(COSStream stream) throws IOException {
        var pd = new PDStream(stream);
        try (var in = pd.createInputStream()) {
            return in.readAllBytes();
        }
    }

    private static float num(Object token) {
        if (token instanceof COSNumber n) {
            return n.floatValue();
        }
        throw new IllegalArgumentException("not a number");
    }

    /** Remove every {@code /name Do} from the page's content streams. */
    static void removeDraws(PDPage page, COSName name) throws IOException {
        for (java.util.Iterator<?> it = page.getContentStreams(); it.hasNext(); ) {
            COSStream stream = (COSStream) ((PDStream) it.next()).getCOSObject();
            removeFromStream(stream, name);
        }
    }

    static void removeFromStream(COSStream stream, COSName name) {
        List<Object> tokens;
        try {
            PDFStreamParser parser = new PDFStreamParser(decoded(stream));
            parser.parse();
            tokens = parser.getTokens();
        } catch (IOException e) {
            return;
        }
        List<Object> kept = new ArrayList<>(tokens.size());
        boolean changed = false;
        for (int i = 0; i < tokens.size(); i++) {
            Object t = tokens.get(i);
            if (t instanceof Operator op && "Do".equals(op.getName()) && i > 0
                    && name.equals(tokens.get(i - 1))) {
                kept.remove(kept.size() - 1); // drop the name operand
                changed = true;
                continue;
            }
            kept.add(t);
        }
        if (changed) {
            try {
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                new ContentStreamWriter(bos).writeTokens(kept);
                byte[] content = bos.toByteArray();
                // We write the tokens uncompressed, so the original filter
                // (usually /FlateDecode) must be removed; otherwise the PDF
                // declares a filter that is not present in the stream.
                stream.removeItem(COSName.FILTER);
                stream.removeItem(COSName.DECODE_PARMS);
                stream.setItem(COSName.LENGTH, org.apache.pdfbox.cos.COSInteger.get(content.length));
                try (var os = stream.createRawOutputStream()) {
                    os.write(content);
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot rewrite content stream", e);
            }
        }
    }
}
