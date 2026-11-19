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
        /** 0 = drawn by the page itself, >0 = inside a form XObject. */
        final int depth;

        Draw(COSName name, PDImageXObject image, Matrix ctm, int depth) {
            this.name = name;
            this.image = image;
            this.ctm = ctm;
            this.depth = depth;
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
            Object token = tokens.get(i);
            if (!(token instanceof Operator)) {
                continue;
            }
            Operator op = (Operator) token;
            switch (op.getName()) {
                case "q":
                    stack.add(ctm.clone());
                    break;
                case "Q":
                    if (!stack.isEmpty()) {
                        ctm = stack.remove(stack.size() - 1);
                    }
                    break;
                case "cm":
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
                    break;
                case "Do":
                    if (i >= 1 && tokens.get(i - 1) instanceof COSName) {
                        COSName name = (COSName) tokens.get(i - 1);
                        PDXObject x;
                        try {
                            x = res.getXObject(name);
                        } catch (IOException e) {
                            break;
                        }
                        if (x instanceof PDImageXObject) {
                            PDImageXObject img = (PDImageXObject) x;
                            out.add(new Draw(name, img, ctm.clone(), depth));
                        } else if (x != null && depth < MAX_FORM_DEPTH) {
                            COSStream form = (COSStream) x.getCOSObject();
                            PDResources formRes = form.getCOSDictionary(COSName.RESOURCES) == null
                                    ? res : new PDResources(form.getCOSDictionary(COSName.RESOURCES));
                            for (COSStream sub : formStreams(form)) {
                                walkStream(sub, formRes, ctm, out, depth + 1);
                            }
                        }
                    }
                    break;
                default:
                    // other operators leave the CTM untouched or are irrelevant
                    break;
            }
        }
    }

    /** The content streams of a form XObject (normally a single stream). */
    static List<COSStream> formStreams(COSStream form) throws IOException {
        List<COSStream> out = new ArrayList<>();
        org.apache.pdfbox.cos.COSBase contents = form.getDictionaryObject(COSName.CONTENTS);
        if (contents instanceof org.apache.pdfbox.cos.COSArray) {
            org.apache.pdfbox.cos.COSArray arr = (org.apache.pdfbox.cos.COSArray) contents;
            for (int i = 0; i < arr.size(); i++) {
                if (arr.getObject(i) instanceof COSStream) {
                    COSStream s = (COSStream) arr.getObject(i);
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
        if (token instanceof COSNumber) {
            COSNumber n = (COSNumber) token;
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
            boolean drop = false;
            if (t instanceof Operator) {
                Operator op = (Operator) t;
                drop = "Do".equals(op.getName()) && i > 0
                        && name.equals(tokens.get(i - 1));
            }
            if (drop) {
                kept.remove(kept.size() - 1); // drop the name operand
                changed = true;
                continue;
            }
            kept.add(t);
        }
        if (changed) {
            writeTokens(stream, kept);
        }
    }

    /**
     * Replace every {@code /name Do} whose image was compressed with
     * {@code /bg Do /fg Do}, in place. Keeping the draw at its original
     * content-stream position preserves the order of text and vector art.
     */
    static void replaceImageDraws(COSStream stream,
                                  java.util.Map<COSName, PageCompressor.Layers> replacements) {
        if (replacements.isEmpty()) {
            return;
        }
        List<Object> tokens;
        try {
            PDFStreamParser parser = new PDFStreamParser(decoded(stream));
            parser.parse();
            tokens = parser.getTokens();
        } catch (IOException e) {
            return;
        }
        List<Object> kept = new ArrayList<>(tokens.size() + replacements.size() * 2);
        boolean changed = false;
        for (int i = 0; i < tokens.size(); i++) {
            Object t = tokens.get(i);
            if (t instanceof Operator && "Do".equals(((Operator) t).getName()) && i > 0
                    && tokens.get(i - 1) instanceof COSName) {
                COSName name = (COSName) tokens.get(i - 1);
                PageCompressor.Layers layers = replacements.get(name);
                if (layers != null) {
                    kept.remove(kept.size() - 1); // remove the original name
                    kept.add(COSName.getPDFName(layers.bg()));
                    kept.add(t);
                    kept.add(COSName.getPDFName(layers.fg()));
                    kept.add(t);
                    changed = true;
                    continue;
                }
            }
            kept.add(t);
        }
        if (changed) {
            writeTokens(stream, kept);
        }
    }

    private static void writeTokens(COSStream stream, List<Object> tokens) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            new ContentStreamWriter(bos).writeTokens(tokens);
            byte[] content = bos.toByteArray();
            // The tokens are written uncompressed, so the original filter
            // (usually /FlateDecode) must be removed.
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
