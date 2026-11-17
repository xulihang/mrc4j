package com.xulihang.mrc4j;

import com.github.jaiimageio.jpeg2000.J2KImageWriteParam;
import nu.pattern.OpenCV;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.opencv.core.Core;
import org.opencv.core.CvType;
import org.opencv.core.Mat;
import org.opencv.core.Scalar;
import org.opencv.core.Size;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.imgproc.Imgproc;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

/**
 * Builds MRC-style (layered) PDF pages: a JPEG 2000 color background plate, a
 * JPEG 2000 foreground plate carrying the inked pixels, and a 1-bit mask used
 * as the foreground's soft mask. Line art stays binary and crisp while the
 * color plates can be compressed aggressively.
 *
 * <pre>
 * MrcPdfBuilder.Options options = new MrcPdfBuilder.Options();
 * try (MrcPdfBuilder builder = new MrcPdfBuilder(options)) {
 *     builder.addPage(Path.of("page-001.png"), 460.8f, 708.2f);
 *     builder.save(Path.of("out.pdf"));
 * }
 * </pre>
 */
public final class MrcPdfBuilder implements AutoCloseable {
    static {
        OpenCV.loadLocally();
    }

    /** Tunables for layer extraction and compression. */
    public static final class Options {
        /** Adaptive threshold window (odd, in pixels). */
        public int thresholdBlockSize = 31;
        /** Adaptive threshold constant. */
        public double thresholdC = 10.0;
        /** Median blur kernel used to build the background plate (odd). */
        public int backgroundBlurKernel = 9;
        /** Mask dilation in pixels when clearing ink from the background plate. */
        public int backgroundClearDilation = 3;
        /** JPEG 2000 target rate for the background plate, bits per pixel. */
        public double backgroundBitsPerPixel = 0.4;
        /** JPEG 2000 target rate for the foreground plate, bits per pixel. */
        public double foregroundBitsPerPixel = 0.3;

        public Options() {
        }
    }

    /** Extracted layers for one page. */
    public static final class PageLayers {
        private final BufferedImage background;
        private final BufferedImage foreground;
        private final byte[] maskPacked;
        private final int width;
        private final int height;
        private final double inkRatio;

        PageLayers(BufferedImage background, BufferedImage foreground, byte[] maskPacked,
                   int width, int height, double inkRatio) {
            this.background = background;
            this.foreground = foreground;
            this.maskPacked = maskPacked;
            this.width = width;
            this.height = height;
            this.inkRatio = inkRatio;
        }

        public BufferedImage getBackground() {
            return background;
        }

        public BufferedImage getForeground() {
            return foreground;
        }

        /** 1-bit mask, ink = 1, rows padded to a byte boundary. */
        public byte[] getMaskPacked() {
            return maskPacked;
        }

        public int getWidth() {
            return width;
        }

        public int getHeight() {
            return height;
        }

        public double getInkRatio() {
            return inkRatio;
        }
    }

    private final Options options;
    private final PDDocument document = new PDDocument();
    private int pageCount;

    public MrcPdfBuilder(Options options) {
        validate(options);
        this.options = options;
    }

    /** Adds one page from an image file, scaled onto a page of the given size in points. */
    public void addPage(Path imagePath, float widthPt, float heightPt) throws IOException {
        Mat mat = Imgcodecs.imread(imagePath.toString(), Imgcodecs.IMREAD_COLOR);
        if (mat.empty()) {
            throw new IOException("Cannot read image: " + imagePath);
        }
        try {
            addPage(toBufferedImage(mat), widthPt, heightPt);
        } finally {
            mat.release();
        }
    }

    /** Adds one page from a BufferedImage, scaled onto a page of the given size in points. */
    public void addPage(BufferedImage image, float widthPt, float heightPt) throws IOException {
        PageLayers layers = extractLayers(image, options);

        byte[] backgroundJp2 = encodeJpx(layers.getBackground(), options.backgroundBitsPerPixel);
        byte[] foregroundJp2 = encodeJpx(layers.getForeground(), options.foregroundBitsPerPixel);
        byte[] maskFlate = deflate(layers.getMaskPacked());

        PDImageXObject background = jpxImage(backgroundJp2, layers.getWidth(), layers.getHeight());
        PDImageXObject foreground = jpxImage(foregroundJp2, layers.getWidth(), layers.getHeight());
        PDImageXObject mask = flateMask(maskFlate, layers.getWidth(), layers.getHeight());
        foreground.getCOSObject().setItem(COSName.SMASK, mask.getCOSObject());

        PDRectangle box = new PDRectangle(widthPt, heightPt);
        PDPage page = new PDPage(box);
        page.setCropBox(box);
        page.setRotation(0);
        try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
            cs.drawImage(background, 0, 0, widthPt, heightPt);
            cs.drawImage(foreground, 0, 0, widthPt, heightPt);
        }
        document.addPage(page);
        pageCount++;
    }

    public int getPageCount() {
        return pageCount;
    }

    public void save(Path output) throws IOException {
        document.save(output.toFile());
    }

    @Override
    public void close() throws IOException {
        document.close();
    }

    /**
     * Splits an image into the three MRC layers. Public so callers can inspect
     * or re-encode the layers themselves.
     */
    public static PageLayers extractLayers(BufferedImage source, Options options) {
        BufferedImage bgrImage = toBgr(source);
        int w = bgrImage.getWidth();
        int h = bgrImage.getHeight();

        Mat bgr = new Mat(h, w, CvType.CV_8UC3);
        byte[] pixels = new byte[w * h * 3];
        bgrImage.getRaster().getDataElements(0, 0, w, h, pixels);
        // getDataElements returns band order (R,G,B); OpenCV expects B,G,R.
        for (int i = 0; i + 2 < pixels.length; i += 3) {
            byte b = pixels[i];
            pixels[i] = pixels[i + 2];
            pixels[i + 2] = b;
        }
        bgr.put(0, 0, pixels);

        Mat gray = new Mat();
        Mat mask = new Mat();
        Mat maskDil = new Mat();
        Mat blurred = new Mat();
        Mat inverse = new Mat();
        Mat background;
        Mat foreground;
        try {
            Imgproc.cvtColor(bgr, gray, Imgproc.COLOR_BGR2GRAY);
            Imgproc.adaptiveThreshold(gray, mask, 255,
                    Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C, Imgproc.THRESH_BINARY_INV,
                    options.thresholdBlockSize, options.thresholdC);

            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                    new Size(options.backgroundClearDilation, options.backgroundClearDilation));
            Imgproc.dilate(mask, maskDil, kernel);

            Imgproc.medianBlur(bgr, blurred, options.backgroundBlurKernel);
            background = bgr.clone();
            blurred.copyTo(background, maskDil);

            Core.bitwise_not(mask, inverse);
            foreground = bgr.clone();
            foreground.setTo(new Scalar(0, 0, 0), inverse);

            double inkRatio = Core.countNonZero(mask) / (double) (w * h);
            try {
                return new PageLayers(toBufferedImage(background), toBufferedImage(foreground),
                        packMask(mask), w, h, inkRatio);
            } finally {
                background.release();
                foreground.release();
            }
        } finally {
            gray.release();
            mask.release();
            maskDil.release();
            blurred.release();
            inverse.release();
            bgr.release();
        }
    }

    public static byte[] encodeJpx(BufferedImage image, double bitsPerPixel) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg2000");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG 2000 writer; "
                    + "add com.github.jai-imageio:jai-imageio-jpeg2000");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            J2KImageWriteParam param = new J2KImageWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setLossless(false);
            param.setEncodingRate(bitsPerPixel);
            // JAI's writer garbles TYPE_INT_RGB input; BGR is safe.
            writer.write(null, new IIOImage(toBgr(image), null, null), param);
            ios.flush();
            return baos.toByteArray();
        } finally {
            writer.dispose();
        }
    }

    private PDImageXObject jpxImage(byte[] jp2, int width, int height) throws IOException {
        PDStream stream = new PDStream(document, new ByteArrayInputStream(jp2));
        COSStream cos = stream.getCOSObject();
        cos.setItem(COSName.TYPE, COSName.XOBJECT);
        cos.setItem(COSName.SUBTYPE, COSName.IMAGE);
        cos.setItem(COSName.FILTER, COSName.JPX_DECODE);
        cos.setItem(COSName.WIDTH, COSInteger.get(width));
        cos.setItem(COSName.HEIGHT, COSInteger.get(height));
        cos.setItem(COSName.BITS_PER_COMPONENT, COSInteger.get(8));
        cos.setItem(COSName.COLORSPACE, COSName.DEVICERGB);
        return new PDImageXObject(stream, new PDResources());
    }

    private PDImageXObject flateMask(byte[] data, int width, int height) throws IOException {
        PDStream stream = new PDStream(document, new ByteArrayInputStream(data));
        COSStream cos = stream.getCOSObject();
        cos.setItem(COSName.TYPE, COSName.XOBJECT);
        cos.setItem(COSName.SUBTYPE, COSName.IMAGE);
        cos.setItem(COSName.FILTER, COSName.FLATE_DECODE);
        cos.setItem(COSName.WIDTH, COSInteger.get(width));
        cos.setItem(COSName.HEIGHT, COSInteger.get(height));
        cos.setItem(COSName.BITS_PER_COMPONENT, COSInteger.get(1));
        cos.setItem(COSName.COLORSPACE, COSName.DEVICEGRAY);
        return new PDImageXObject(stream, new PDResources());
    }

    private static byte[] packMask(Mat mask) {
        int w = mask.cols();
        int h = mask.rows();
        int rowBytes = (w + 7) / 8;
        byte[] packed = new byte[rowBytes * h];
        byte[] row = new byte[w];
        for (int y = 0; y < h; y++) {
            mask.get(y, 0, row);
            int base = y * rowBytes;
            for (int x = 0; x < w; x++) {
                if ((row[x] & 0xFF) != 0) {
                    packed[base + (x >> 3)] |= (byte) (0x80 >>> (x & 7));
                }
            }
        }
        return packed;
    }

    private static byte[] deflate(byte[] data) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(baos,
                new Deflater(Deflater.BEST_COMPRESSION))) {
            dos.write(data);
        }
        return baos.toByteArray();
    }

    private static BufferedImage toBgr(BufferedImage source) {
        if (source.getType() == BufferedImage.TYPE_3BYTE_BGR) {
            return source;
        }
        BufferedImage bgr = new BufferedImage(source.getWidth(), source.getHeight(),
                BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = bgr.createGraphics();
        try {
            g.drawImage(source, 0, 0, null);
        } finally {
            g.dispose();
        }
        return bgr;
    }

    private static BufferedImage toBufferedImage(Mat mat) {
        int w = mat.cols();
        int h = mat.rows();
        int channels = mat.channels();
        byte[] data = new byte[w * h * channels];
        mat.get(0, 0, data);
        BufferedImage image;
        if (channels == 3) {
            // Mat is BGR. Raster.setDataElements takes band order (R,G,B) for
            // TYPE_3BYTE_BGR, so swap channels before handing the bytes over.
            for (int i = 0; i + 2 < data.length; i += 3) {
                byte b = data[i];
                data[i] = data[i + 2];
                data[i + 2] = b;
            }
            image = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        } else if (channels == 1) {
            image = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY);
        } else {
            throw new IllegalArgumentException("Unsupported channel count: " + channels);
        }
        image.getRaster().setDataElements(0, 0, w, h, data);
        return image;
    }

    private static void validate(Options options) {
        if (options.thresholdBlockSize < 3 || options.thresholdBlockSize % 2 == 0) {
            throw new IllegalArgumentException("thresholdBlockSize must be an odd number >= 3");
        }
        if (options.backgroundBlurKernel < 3 || options.backgroundBlurKernel % 2 == 0) {
            throw new IllegalArgumentException("backgroundBlurKernel must be an odd number >= 3");
        }
        if (options.backgroundBitsPerPixel <= 0 || options.foregroundBitsPerPixel <= 0) {
            throw new IllegalArgumentException("JPEG 2000 rates must be > 0");
        }
    }
}
