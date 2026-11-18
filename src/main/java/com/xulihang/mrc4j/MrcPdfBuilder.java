package com.xulihang.mrc4j;

import com.github.jaiimageio.jpeg2000.J2KImageWriteParam;
import nu.pattern.OpenCV;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBoolean;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSInteger;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSStream;
import org.apache.pdfbox.filter.FilterFactory;
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
 * Builds MRC-style (layered) PDF pages: a color background plate, a
 * foreground plate carrying the inked pixels, and a 1-bit mask used as the
 * foreground's soft mask. Line art stays binary and crisp while the color
 * plates can be compressed aggressively.
 *
 * <p>Following the approach of <a href="https://github.com/ifad/pdfbeads">pdfbeads</a>,
 * the mask is stored as CCITT Group 4 (the same representation pdfbeads uses for
 * its stencils), and the background plate is optionally downsampled and stored
 * as JPEG. Both are much cheaper than a full-resolution JPEG 2000 plate and are
 * decoded by every PDF viewer.</p>
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

    /** How a color plate is encoded. */
    public enum PlateFormat {
        /** Baseline JPEG (DCTDecode); small and supported by every viewer. */
        JPEG,
        /** JPEG 2000 (JPXDecode); denser, but needs a JPX decoder in the viewer. */
        JPX
    }

    /** How the 1-bit ink mask is encoded. */
    public enum MaskEncoding {
        /** CCITT Group 4 (CCITTFaxDecode); typically several times smaller than Flate. */
        CCITT_G4,
        /** zlib/Flate (FlateDecode). */
        FLATE
    }

    /**
     * How inked pixels are rendered. Stencil mode paints the mask in solid
     * black and omits the foreground plate entirely, which is what pdfbeads
     * does for black text; image mode keeps the foreground plate so colored
     * ink (stamps, headings, comics) survives.
     */
    public enum InkMode {
        /** Always keep a full-color foreground plate. */
        IMAGE,
        /** Always paint the mask as a black stencil. */
        STENCIL,
        /** Use the stencil when the ink is dark and close to neutral. */
        AUTO
    }

    /** Tunables for layer extraction and compression. */
    public static final class Options {
        /** Adaptive threshold window (odd, in pixels). */
        public int thresholdBlockSize = 31;
        /** Adaptive threshold constant. */
        public double thresholdC = 10.0;
        /** Median blur kernel used to build the background plate (odd). */
        public int backgroundBlurKernel = 9;
        /** Morphological opening kernel applied to the ink mask; 0 disables it. */
        public int maskOpenKernel = 3;
        /** Mask dilation in pixels when clearing ink from the background plate. */
        public int backgroundClearDilation = 3;

        /** Encoding for the 1-bit mask (soft mask for the foreground plate). */
        public MaskEncoding maskEncoding = MaskEncoding.CCITT_G4;

        /** Encoding for the background plate. */
        public PlateFormat backgroundFormat = PlateFormat.JPEG;
        /**
         * Downsampling factor for the background plate, &ge; 1. The mask and
         * foreground stay at full resolution, so line art remains sharp.
         */
        public double backgroundScale = 2.0;
        /** JPEG quality (0..1) for the background plate when {@link #backgroundFormat} is JPEG. */
        public float backgroundJpegQuality = 0.6f;
        /** JPEG 2000 target rate for the background plate, bits per pixel. */
        public double backgroundBitsPerPixel = 0.4;

        /** Encoding for the foreground plate. */
        public PlateFormat foregroundFormat = PlateFormat.JPX;
        /** JPEG quality (0..1) for the foreground plate when {@link #foregroundFormat} is JPEG. */
        public float foregroundJpegQuality = 0.7f;
        /** JPEG 2000 target rate for the foreground plate, bits per pixel. */
        public double foregroundBitsPerPixel = 0.05;

        /** Whether black ink may be painted straight from the mask. */
        public InkMode inkMode = InkMode.AUTO;
        /** AUTO stencil threshold: maximum mean HSV saturation of ink pixels (0..255). */
        public double stencilMaxSaturation = 24.0;
        /** AUTO stencil threshold: maximum mean HSV value of ink pixels (0..255). */
        public double stencilMaxValue = 110.0;

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
        private final double inkMeanSaturation;
        private final double inkMeanValue;

        PageLayers(BufferedImage background, BufferedImage foreground, byte[] maskPacked,
                   int width, int height, double inkRatio,
                   double inkMeanSaturation, double inkMeanValue) {
            this.background = background;
            this.foreground = foreground;
            this.maskPacked = maskPacked;
            this.width = width;
            this.height = height;
            this.inkRatio = inkRatio;
            this.inkMeanSaturation = inkMeanSaturation;
            this.inkMeanValue = inkMeanValue;
        }

        /**
         * Background plate, with ink removed. May be smaller than the page
         * image when {@link Options#backgroundScale} &gt; 1.
         */
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

        /** Width of the mask and foreground layers in pixels. */
        public int getWidth() {
            return width;
        }

        /** Height of the mask and foreground layers in pixels. */
        public int getHeight() {
            return height;
        }

        public double getInkRatio() {
            return inkRatio;
        }

        /** Mean HSV saturation of inked pixels (0..255); low values mean neutral ink. */
        public double getInkMeanSaturation() {
            return inkMeanSaturation;
        }

        /** Mean HSV value of inked pixels (0..255); low values mean dark ink. */
        public double getInkMeanValue() {
            return inkMeanValue;
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
    public PageLayers addPage(Path imagePath, float widthPt, float heightPt) throws IOException {
        Mat mat = Imgcodecs.imread(imagePath.toString(), Imgcodecs.IMREAD_COLOR);
        if (mat.empty()) {
            throw new IOException("Cannot read image: " + imagePath);
        }
        try {
            return addPage(toBufferedImage(mat), widthPt, heightPt);
        } finally {
            mat.release();
        }
    }

    /** Adds one page from a BufferedImage, scaled onto a page of the given size in points. */
    public PageLayers addPage(BufferedImage image, float widthPt, float heightPt) throws IOException {
        PageLayers layers = extractLayers(image, options);
        boolean stencil = useStencilFor(layers, options);

        PDImageXObject background = encodePlate(layers.getBackground(),
                options.backgroundFormat, options.backgroundJpegQuality,
                options.backgroundBitsPerPixel);
        // The background may be downsampled; ask viewers to interpolate it
        // when it is scaled back over the whole page.
        background.getCOSObject().setBoolean(COSName.INTERPOLATE, true);
        PDImageXObject mask = encodeMask(layers.getMaskPacked(),
                layers.getWidth(), layers.getHeight(), stencil);

        PDRectangle box = new PDRectangle(widthPt, heightPt);
        PDPage page = new PDPage(box);
        page.setCropBox(box);
        page.setRotation(0);
        try (PDPageContentStream cs = new PDPageContentStream(document, page)) {
            cs.drawImage(background, 0, 0, widthPt, heightPt);
            if (stencil) {
                // Image masks paint where the decoded sample is 0; the mask's
                // Decode [1 0] makes ink bits paint the current fill color.
                cs.setNonStrokingColor(0f, 0f, 0f);
                cs.drawImage(mask, 0, 0, widthPt, heightPt);
            } else {
                PDImageXObject foreground = encodePlate(layers.getForeground(),
                        options.foregroundFormat, options.foregroundJpegQuality,
                        options.foregroundBitsPerPixel);
                foreground.getCOSObject().setItem(COSName.SMASK, mask.getCOSObject());
                cs.drawImage(foreground, 0, 0, widthPt, heightPt);
            }
        }
        document.addPage(page);
        pageCount++;
        return layers;
    }

    /**
     * Decides whether the mask can be painted as a solid black stencil instead
     * of being used as a soft mask over a color foreground plate.
     */
    public static boolean useStencilFor(PageLayers layers, Options options) {
        if (options.inkMode == InkMode.STENCIL) {
            return true;
        }
        if (options.inkMode == InkMode.IMAGE || layers.getInkRatio() <= 0) {
            return false;
        }
        return layers.getInkMeanSaturation() <= options.stencilMaxSaturation
                && layers.getInkMeanValue() <= options.stencilMaxValue;
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
     * or re-encode the layers themselves. When {@link Options#backgroundScale}
     * is greater than 1 the returned background plate is downsampled; mask and
     * foreground are always returned at the source resolution.
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

            if (options.maskOpenKernel >= 2) {
                Mat opened = new Mat();
                Mat openKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                        new Size(options.maskOpenKernel, options.maskOpenKernel));
                try {
                    Imgproc.morphologyEx(mask, opened, Imgproc.MORPH_OPEN, openKernel);
                    opened.copyTo(mask);
                } finally {
                    openKernel.release();
                    opened.release();
                }
            }

            Mat kernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT,
                    new Size(options.backgroundClearDilation, options.backgroundClearDilation));
            try {
                Imgproc.dilate(mask, maskDil, kernel);
            } finally {
                kernel.release();
            }

            Imgproc.medianBlur(bgr, blurred, options.backgroundBlurKernel);
            background = bgr.clone();
            blurred.copyTo(background, maskDil);

            Core.bitwise_not(mask, inverse);
            foreground = bgr.clone();
            foreground.setTo(new Scalar(0, 0, 0), inverse);

            double inkRatio = Core.countNonZero(mask) / (double) (w * h);
            double inkSaturation = 0;
            double inkValue = 0;
            if (inkRatio > 0) {
                Mat hsv = new Mat();
                try {
                    Imgproc.cvtColor(bgr, hsv, Imgproc.COLOR_BGR2HSV);
                    Scalar mean = Core.mean(hsv, mask);
                    inkSaturation = mean.val[1];
                    inkValue = mean.val[2];
                } finally {
                    hsv.release();
                }
            }
            Mat backgroundPlate = downsampleBackground(background, options.backgroundScale);
            try {
                return new PageLayers(toBufferedImage(backgroundPlate), toBufferedImage(foreground),
                        packMask(mask), w, h, inkRatio, inkSaturation, inkValue);
            } finally {
                backgroundPlate.release();
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

    private PDImageXObject encodePlate(BufferedImage image, PlateFormat format,
                                       float jpegQuality, double bitsPerPixel) throws IOException {
        int w = image.getWidth();
        int h = image.getHeight();
        if (format == PlateFormat.JPEG) {
            byte[] jpeg = encodeJpeg(image, jpegQuality);
            return imageXObject(jpeg, COSName.DCT_DECODE, null, w, h, COSName.DEVICERGB, 8, false);
        }
        byte[] jpx = encodeJpx(image, bitsPerPixel);
        return imageXObject(jpx, COSName.JPX_DECODE, null, w, h, COSName.DEVICERGB, 8, false);
    }

    public static byte[] encodeJpeg(BufferedImage image, float quality) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("No JPEG writer available");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(baos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(quality);
            writer.write(null, new IIOImage(toBgr(image), null, null), param);
            ios.flush();
            return baos.toByteArray();
        } finally {
            writer.dispose();
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

    /** Encodes the packed 1-bit ink mask with the configured format. */
    private PDImageXObject encodeMask(byte[] packed, int width, int height,
                                      boolean imageMask) throws IOException {
        if (options.maskEncoding == MaskEncoding.CCITT_G4) {
            byte[] g4 = encodeCcittG4(packed, width, height);
            COSDictionary decodeParms = new COSDictionary();
            decodeParms.setInt(COSName.K, -1);
            decodeParms.setInt(COSName.COLUMNS, width);
            decodeParms.setInt(COSName.ROWS, height);
            // Ink bits are 1; with BlackIs1=true they decode to 1. As a soft
            // mask that is white/opaque, as an image mask it paints.
            decodeParms.setBoolean(COSName.BLACK_IS_1, true);
            return imageXObject(g4, COSName.CCITTFAX_DECODE, decodeParms,
                    width, height, imageMask ? null : COSName.DEVICEGRAY, 1, imageMask);
        }
        byte[] flate = deflate(packed);
        return imageXObject(flate, COSName.FLATE_DECODE, null,
                width, height, imageMask ? null : COSName.DEVICEGRAY, 1, imageMask);
    }

    /**
     * Encodes packed 1 bits-per-pixel data as CCITT Group 4 using PDFBox's
     * encoder. The input has one bit per pixel, MSB first, rows padded to a
     * byte boundary; nonzero bits are ink (black runs).
     */
    public static byte[] encodeCcittG4(byte[] packed, int width, int height) throws IOException {
        COSDictionary params = new COSDictionary();
        params.setInt(COSName.COLUMNS, width);
        params.setInt(COSName.ROWS, height);
        params.setInt(COSName.K, -1);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        FilterFactory.INSTANCE.getFilter(COSName.CCITTFAX_DECODE)
                .encode(new ByteArrayInputStream(packed), baos, params, 0);
        return baos.toByteArray();
    }

    private PDImageXObject imageXObject(byte[] data, COSName filter, COSDictionary decodeParms,
                                        int width, int height, COSName colorSpace,
                                        int bitsPerComponent, boolean imageMask) throws IOException {
        PDStream stream = new PDStream(document, new ByteArrayInputStream(data));
        COSStream cos = stream.getCOSObject();
        cos.setItem(COSName.TYPE, COSName.XOBJECT);
        cos.setItem(COSName.SUBTYPE, COSName.IMAGE);
        cos.setItem(COSName.FILTER, filter);
        if (decodeParms != null) {
            cos.setItem(COSName.DECODE_PARMS, decodeParms);
        }
        cos.setItem(COSName.WIDTH, COSInteger.get(width));
        cos.setItem(COSName.HEIGHT, COSInteger.get(height));
        cos.setItem(COSName.BITS_PER_COMPONENT, COSInteger.get(bitsPerComponent));
        if (imageMask) {
            cos.setItem(COSName.IMAGE_MASK, COSBoolean.TRUE);
            // Image masks paint where the sample is 0; our ink is 1.
            COSArray decode = new COSArray();
            decode.add(COSInteger.get(1));
            decode.add(COSInteger.get(0));
            cos.setItem(COSName.DECODE, decode);
        } else if (colorSpace != null) {
            cos.setItem(COSName.COLORSPACE, colorSpace);
        }
        return new PDImageXObject(stream, new PDResources());
    }

    private static Mat downsampleBackground(Mat background, double scale) {
        if (scale <= 1.0) {
            return background.clone();
        }
        int w = Math.max(1, (int) Math.round(background.cols() / scale));
        int h = Math.max(1, (int) Math.round(background.rows() / scale));
        Mat scaled = new Mat();
        Imgproc.resize(background, scaled, new Size(w, h), 0, 0, Imgproc.INTER_AREA);
        return scaled;
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
        if (options.maskOpenKernel != 0
                && (options.maskOpenKernel < 3 || options.maskOpenKernel % 2 == 0)) {
            throw new IllegalArgumentException("maskOpenKernel must be 0 or an odd number >= 3");
        }
        if (options.backgroundClearDilation < 1) {
            throw new IllegalArgumentException("backgroundClearDilation must be >= 1");
        }
        if (options.backgroundScale < 1.0) {
            throw new IllegalArgumentException("backgroundScale must be >= 1");
        }
        if (options.backgroundBitsPerPixel <= 0 || options.foregroundBitsPerPixel <= 0) {
            throw new IllegalArgumentException("JPEG 2000 rates must be > 0");
        }
        if (options.backgroundJpegQuality <= 0 || options.backgroundJpegQuality > 1
                || options.foregroundJpegQuality <= 0 || options.foregroundJpegQuality > 1) {
            throw new IllegalArgumentException("JPEG qualities must be in (0, 1]");
        }
        if (options.stencilMaxSaturation < 0 || options.stencilMaxSaturation > 255
                || options.stencilMaxValue < 0 || options.stencilMaxValue > 255) {
            throw new IllegalArgumentException("Stencil thresholds must be in [0, 255]");
        }
    }
}
