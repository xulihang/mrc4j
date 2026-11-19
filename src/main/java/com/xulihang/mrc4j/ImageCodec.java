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

import java.awt.Dimension;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.awt.image.ColorModel;
import java.awt.image.DataBuffer;
import java.awt.image.DataBufferByte;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.spi.IIORegistry;
import javax.imageio.spi.IIOServiceProvider;
import javax.imageio.spi.ImageReaderSpi;
import javax.imageio.spi.ImageWriterSpi;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.MemoryCacheImageInputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/**
 * Image (de)serialisation helpers: decode arbitrary PDF image streams (JPEG,
 * JPEG2000/JPX, PNG, TIFF, CCITT G4, ...) into a {@link PixelBuffer}, and write
 * 8-bit TIFF files for handing images to {@code kdu_compress}.
 *
 * <p>JPEG2000 support comes from the {@code jai-imageio-jpeg2000} plugin and is
 * optional at runtime; calls that need it fail with a clear message when the
 * plugin is missing.</p>
 */
public final class ImageCodec {

    private static boolean registered;

    private ImageCodec() {
    }

    /** SPI classes of the optional jai-imageio JPEG2000 plugin. */
    private static final String J2K_READER_SPI = "com.github.jaiimageio.jpeg2000.impl.J2KImageReaderSpi";
    private static final String J2K_WRITER_SPI = "com.github.jaiimageio.jpeg2000.impl.J2KImageWriterSpi";

    public static synchronized void registerImageIoPlugins() {
        if (registered) {
            return;
        }
        // SPI registration happens automatically when the jars are on the
        // classpath; force ImageIO to (re)scan if nothing is registered yet.
        try {
            if (!ImageIO.getImageReadersByFormatName("JPEG2000").hasNext()
                    || !ImageIO.getImageWritersByFormatName("JPEG2000").hasNext()) {
                ImageIO.scanForPlugins();
            }
        } catch (RuntimeException | LinkageError e) {
            // e.g. ServiceConfigurationError for a provider entry pointing at
            // a class the (partially bundled) build does not contain
        }
        // Service files under META-INF/services are often lost when jars are
        // shaded or bundled into another application (B4J's jar packaging, for
        // one, keeps a single provider file per category). The plugin classes
        // can then be on the classpath yet invisible to ImageIO, which makes
        // JPEG2000 encoding silently fall back to PNG and PDFBox's JPEG2000
        // reader fail. Register the SPIs directly so the plugin works
        // regardless of packaging.
        registerSpi(J2K_READER_SPI, ImageReaderSpi.class);
        registerSpi(J2K_WRITER_SPI, ImageWriterSpi.class);
        registered = true;
    }

    /**
     * Register the SPI class {@code className} unless an instance of the same
     * class is already registered. Does nothing when the class is absent.
     */
    private static <T extends IIOServiceProvider> void registerSpi(String className, Class<T> category) {
        try {
            for (Iterator<T> it = getServiceProviders(category); it.hasNext(); ) {
                if (it.next().getClass().getName().equals(className)) {
                    return;
                }
            }
            T spi = category.cast(Class.forName(className).getDeclaredConstructor().newInstance());
            IIORegistry.getDefaultInstance().registerServiceProvider(spi, category);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
            // Plugin not on the classpath; callers report the missing support.
        }
    }

    /**
     * Registered SPIs of {@code category}. A broken service file under
     * META-INF/services makes ImageIO fail lazily, one bad provider at a
     * time, so keep querying past the failures.
     */
    private static <T extends IIOServiceProvider> Iterator<T> getServiceProviders(Class<T> category) {
        IIORegistry registry = IIORegistry.getDefaultInstance();
        List<T> out = new ArrayList<>();
        for (Iterator<T> it = registry.getServiceProviders(category, true); ; ) {
            try {
                if (!it.hasNext()) {
                    break;
                }
                out.add(it.next());
            } catch (RuntimeException | LinkageError e) {
                // skip the provider that cannot be loaded
            }
        }
        return out.iterator();
    }

    public static boolean hasJpeg2000Support() {
        registerImageIoPlugins();
        return ImageIO.getImageReadersByFormatName("JPEG2000").hasNext();
    }

    /** Decode PNG/JPEG/TIFF/JPEG2000 bytes into a pixel buffer. */
    public static PixelBuffer decode(byte[] bytes) throws IOException {
        registerImageIoPlugins();
        try (ImageInputStream iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = ImageIO.getImageReaders(iis).hasNext() ? ImageIO.getImageReaders(iis).next() : null;
            if (reader == null) {
                throw new IOException("No ImageIO reader for image data");
            }
            try {
                reader.setInput(iis, true, true);
                BufferedImage img = reader.read(0);
                return fromBufferedImage(img);
            } finally {
                reader.dispose();
            }
        }
    }

    /**
     * Image pixels of a PDF image XObject.
     *
     * <p>PDFBox's own JPEG2000 decoding looks the ImageIO reader up through
     * {@code ServiceLoader} only, so it ignores the SPIs registered here. When
     * a shaded or bundled jar lost the plugin's {@code META-INF/services}
     * entries, writing still works but such PDFs cannot be read back.</p>
     */
    public static BufferedImage getImage(PDImageXObject image) throws IOException {
        // Pixels come from the raw data, so the image need not be backed by a
        // COSStream (PDFBox lazily wraps loaded images in PDImageXObject-like
        // views that open their stream up front; a broken service file can
        // make ImageIO throw while they are set up).
        COSDictionary dict = image.getCOSObject();
        COSBase filter = dict.getDictionaryObject(COSName.FILTER);
        boolean jpx = COSName.JPX_DECODE.equals(filter)
                || (filter instanceof COSArray
                    && ((COSArray) filter).getObject(0) instanceof COSName
                    && COSName.JPX_DECODE.equals(((COSArray) filter).getObject(0)));
        if (!jpx) {
            return image.getImage();
        }
        int w = dict.getInt(COSName.WIDTH, 0);
        int h = dict.getInt(COSName.HEIGHT, 0);
        COSBase cs = dict.getDictionaryObject(COSName.COLORSPACE);
        // Only gray and plain RGB are converted here; other color spaces
        // (CMYK, ICC based) are left to PDFBox's conversion.
        if (!COSName.DEVICEGRAY.equals(cs) && !COSName.DEVICERGB.equals(cs)) {
            return image.getImage();
        }
        try {
            PixelBuffer px;
            try (InputStream is = image.getStream().getCOSObject().createRawInputStream();
                 ImageInputStream iis = new MemoryCacheImageInputStream(is)) {
                px = decodeImage(iis);
            }
            if (COSName.DEVICERGB.equals(cs)) {
                if (px.getChannels() == 1) {
                    px = px.toRgb();
                }
            } else {
                px = px.toGrayscale();
            }
            return toBufferedImage(px.getWidth() == w && px.getHeight() == h ? px : px.resize(w, h));
        } catch (RuntimeException e) {
            throw new IOException("Could not decode JPEG2000 image", e);
        }
    }

    /** Decode JPEG2000 bytes through ImageIO, with the SPI registered by hand. */
    private static PixelBuffer decodeImage(ImageInputStream iis) throws IOException {
        registerImageIoPlugins();
        if (!ImageIO.getImageReaders(iis).hasNext()) {
            throw new IOException("No ImageIO reader for image data");
        }
        ImageReader reader = ImageIO.getImageReaders(iis).next();
        try {
            reader.setInput(iis, true, true);
            return fromBufferedImage(reader.read(0));
        } finally {
            reader.dispose();
        }
    }

    /**
     * Pixel dimensions of an encoded image, without fully decoding JPEG2000
     * data (PDF sizes masks with these).
     */
    public static Dimension dimensions(byte[] bytes) throws IOException {
        registerImageIoPlugins();
        try (ImageInputStream iis = new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
            ImageReader reader = ImageIO.getImageReaders(iis).hasNext() ? ImageIO.getImageReaders(iis).next() : null;
            if (reader == null) {
                throw new IOException("No ImageIO reader for image data");
            }
            try {
                reader.setInput(iis, true, true);
                return new Dimension(reader.getWidth(0), reader.getHeight(0));
            } finally {
                reader.dispose();
            }
        }
    }

    public static PixelBuffer fromBufferedImage(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        int t = img.getType();
        if (t == BufferedImage.TYPE_3BYTE_BGR || t == BufferedImage.TYPE_4BYTE_ABGR) {
            Raster r = img.getRaster();
            boolean alpha = t == BufferedImage.TYPE_4BYTE_ABGR;
            PixelBuffer out = new PixelBuffer(w, h, alpha ? 4 : 3);
            int[] px = new int[alpha ? 4 : 3];
            byte[] d = out.getData();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    // getPixel() returns samples in the color model's band
                    // order, i.e. R,G,B[,A] for both BGR and ABGR images.
                    r.getPixel(x, y, px);
                    int p = (y * w + x) * out.getChannels();
                    d[p] = (byte) px[0];
                    d[p + 1] = (byte) px[1];
                    d[p + 2] = (byte) px[2];
                    if (alpha) {
                        d[p + 3] = (byte) px[3];
                    }
                }
            }
            return out;
        }
        if (t == BufferedImage.TYPE_BYTE_GRAY) {
            Raster r = img.getRaster();
            PixelBuffer out = new PixelBuffer(w, h, 1);
            byte[] d = out.getData();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    d[y * w + x] = (byte) r.getSample(x, y, 0);
                }
            }
            return out;
        }
        if (t == BufferedImage.TYPE_BYTE_BINARY || t == BufferedImage.TYPE_BYTE_INDEXED) {
            ColorModel cm = img.getColorModel();
            PixelBuffer out = new PixelBuffer(w, h, 3);
            byte[] d = out.getData();
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int rgb = img.getRGB(x, y);
                    int p = (y * w + x) * 3;
                    d[p] = (byte) (rgb >> 16);
                    d[p + 1] = (byte) (rgb >> 8);
                    d[p + 2] = (byte) rgb;
                }
            }
            return out;
        }
        // Generic fallback: go through the sRGB integer representation.
        PixelBuffer out = new PixelBuffer(w, h, 3);
        byte[] d = out.getData();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int p = (y * w + x) * 3;
                d[p] = (byte) (rgb >> 16);
                d[p + 1] = (byte) (rgb >> 8);
                d[p + 2] = (byte) rgb;
            }
        }
        return out;
    }

    public static BufferedImage toBufferedImage(PixelBuffer buf) {
        int w = buf.getWidth();
        int h = buf.getHeight();
        byte[] d = buf.getData();
        if (buf.getChannels() == 1) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
            byte[] out = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            System.arraycopy(d, 0, out, 0, w * h);
            return img;
        }
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        byte[] out = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
        for (int i = 0, n = w * h; i < n; i++) {
            int p = i * buf.getChannels();
            out[i * 3] = d[p + 2];
            out[i * 3 + 1] = d[p + 1];
            out[i * 3 + 2] = d[p];
        }
        return img;
    }

    public static byte[] encodePng(PixelBuffer buf) throws IOException {
        return encode(buf, "png");
    }

    public static byte[] encodeJpeg(PixelBuffer buf, float quality) throws IOException {
        registerImageIoPlugins();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream mos = new MemoryCacheImageOutputStream(bos)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.setOutput(mos);
            writer.write(null, new IIOImage(toBufferedImage(buf), null, null), param);
        } finally {
            writer.dispose();
        }
        return bos.toByteArray();
    }

    public static byte[] encode(PixelBuffer buf, String formatName) throws IOException {
        registerImageIoPlugins();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        if (!ImageIO.write(toBufferedImage(buf), formatName, bos)) {
            throw new IOException("No writer for format " + formatName);
        }
        return bos.toByteArray();
    }

    /** Write an uncompressed 8-bit TIFF (gray or RGB) for kdu_compress' -i. */
    public static void writeTiff(PixelBuffer buf, java.io.File file) throws IOException {
        registerImageIoPlugins();
        BufferedImage img = toBufferedImage(buf);
        if (!ImageIO.write(img, "tiff", file)) {
            throw new IOException("No TIFF writer available");
        }
    }

    /** Whether the bytes look like a JPEG2000 file (JP2 or raw J2K codestream). */
    public static boolean isJpeg2000(byte[] data) {
        if (data.length < 12) {
            return false;
        }
        // 0x0000000C 6A502020 0D0A870A (JP2 signature box)
        if (data[0] == 0 && data[1] == 0 && data[2] == 0 && data[3] == 12
                && data[4] == 'j' && data[5] == 'P' && data[6] == ' ' && data[7] == ' ') {
            return true;
        }
        // Raw codestream: SOC (FF4F) followed by SIZ (FF51)
        return (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0x4f
                && (data[2] & 0xff) == 0xff && (data[3] & 0xff) == 0x51;
    }

    /** PNG signature check. */
    public static boolean isPng(byte[] data) {
        return data.length > 8 && (data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
    }

    /**
     * Re-encode a PNG as raw "Up"-predicted rows (PNG filter type 2 on every
     * row), the layout a PDF /FlateDecode stream with /Predictor 12 expects,
     * and return the decoded pixels together with the deflate body.
     */
    public static byte[] pngToUpPredictedZlib(byte[] png) throws IOException {
        PixelBuffer px = decode(png);
        int w = px.getWidth();
        int h = px.getHeight();
        int stride = w * px.getChannels();
        byte[] d = px.getData();
        byte[] rows = new byte[(stride + 1) * h];
        for (int y = 0; y < h; y++) {
            int o = y * (stride + 1);
            rows[o] = 2; // PNG filter "Up"
            for (int i = 0; i < stride; i++) {
                int cur = d[y * stride + i] & 0xff;
                int up = y > 0 ? d[(y - 1) * stride + i] & 0xff : 0;
                rows[o + 1 + i] = (byte) (cur - up);
            }
        }
        return deflate(rows);
    }

    /** zlib-compressed data (PDF /FlateDecode). */
    public static byte[] deflate(byte[] data) throws IOException {
        java.util.zip.Deflater deflater = new java.util.zip.Deflater(java.util.zip.Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!deflater.finished()) {
                bos.write(buf, 0, deflater.deflate(buf));
            }
            return bos.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * JPEG "scan data" is data we cannot re-encode losslessly, so we only need
     * its decoded pixels; used for JPEG decoding in combination with the
     * caller's own stride handling.
     */
    public static PixelBuffer decodeJpeg(byte[] bytes) throws IOException {
        return decode(bytes);
    }

    /**
     * Cut a {@code width x height} image out of a decoded image's raster at
     * integer offsets (PDF image placement helper).
     */
    public static PixelBuffer crop(PixelBuffer buf, Rectangle r) {
        return buf.subImage(r.x, r.y, r.width, r.height);
    }

    static String describe(PixelBuffer buf) {
        return String.format(Locale.ROOT, "%dx%d x %d", buf.getWidth(), buf.getHeight(), buf.getChannels());
    }
}
