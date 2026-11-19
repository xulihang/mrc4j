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
import java.util.Locale;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
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

    public static synchronized void registerImageIoPlugins() {
        if (registered) {
            return;
        }
        // SPI registration happens automatically when the jars are on the
        // classpath; force ImageIO to (re)scan if nothing is registered yet.
        if (ImageIO.getImageReadersByFormatName("JPEG2000").hasNext() == false) {
            ImageIO.scanForPlugins();
        }
        registered = true;
    }

    public static boolean hasJpeg2000Support() {
        registerImageIoPlugins();
        return ImageIO.getImageReadersByFormatName("JPEG2000").hasNext();
    }

    /** Decode PNG/JPEG/TIFF/JPEG2000/... image bytes into a pixel buffer. */
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
