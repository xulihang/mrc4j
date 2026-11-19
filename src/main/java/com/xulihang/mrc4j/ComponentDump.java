package com.xulihang.mrc4j;

import java.io.File;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * Developer helper: dumps the MRC components of an image (as produced by
 * {@link MrcDecomposer}) plus md5 hashes, so they can be compared with the
 * reference dumps from the Python implementation.
 */
public final class ComponentDump {

    private ComponentDump() {
    }

    public static void main(String[] args) throws Exception {
        File in = new File(args[0]);
        File outDir = new File(args[1]);
        int dpi = args.length > 2 ? Integer.parseInt(args[2]) : 300;
        Files.createDirectories(outDir.toPath());

        PixelBuffer img = ImageCodec.decode(Files.readAllBytes(in.toPath()));
        PixelBuffer gray = img.toGrayscale();

        // Noise estimate (as estimate_noise() in mrc.py: centre half)
        int h = gray.getHeight();
        int w = gray.getWidth();
        int hs = (int) Math.floor(h / 2.0 - h / 4.0);
        int he = (int) Math.floor(h / 2.0 + h / 4.0);
        int ws = (int) Math.floor(w / 2.0 - w / 4.0);
        int we = (int) Math.floor(w / 2.0 + w / 4.0);
        if (he == 0 || we == 0) {
            hs = 0;
            he = h;
            ws = 0;
            we = w;
        }
        double sigma = NoiseEstimator.meanEstimateSigma(gray.subImage(ws, hs, we - ws, he - hs));
        System.out.println("sigma_est " + sigma);

        MrcDecomposer.Options o = new MrcDecomposer.Options();
        o.dpi = dpi;
        o.bgDownsample = 3;
        MrcDecomposer.Components c = MrcDecomposer.decompose(img, o);
        Files.write(outDir.toPath().resolve("java-mask.png"), Png1Bit.encode(c.mask));
        Files.write(outDir.toPath().resolve("java-fg.png"), ImageCodec.encodePng(c.foreground));
        Files.write(outDir.toPath().resolve("java-bg.png"), ImageCodec.encodePng(c.background));
        System.out.println("mask " + md5(c.mask.getData()) + " " + c.mask.count());
        System.out.println("fg " + md5(c.foreground.getData()));
        System.out.println("bg " + md5(c.background.getData()));
        System.out.println("bg_ds " + md5(c.background.getData()) + " " + c.background.getWidth()
                + "x" + c.background.getHeight());
    }

    static String md5(byte[] data) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("MD5").digest(data));
    }
}
