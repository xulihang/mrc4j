package com.xulihang.mrc4j;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;

import org.junit.jupiter.api.Test;

class ImageCodecTest {

    @Test
    void readsRgbOrderFromBgrImages() {
        for (int type : new int[] {BufferedImage.TYPE_3BYTE_BGR, BufferedImage.TYPE_4BYTE_ABGR}) {
            BufferedImage img = new BufferedImage(1, 1, type);
            img.setRGB(0, 0, 0xFF112233);
            PixelBuffer px = ImageCodec.fromBufferedImage(img);
            assertEquals(0x11, px.get(0, 0, 0), "red " + type);
            assertEquals(0x22, px.get(0, 0, 1), "green " + type);
            assertEquals(0x33, px.get(0, 0, 2), "blue " + type);
        }
    }

    @Test
    void recognisesRawJpeg2000Codestream() {
        byte[] socSiz = {(byte) 0xFF, (byte) 0x4F, (byte) 0xFF, (byte) 0x51,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00};
        assertTrue(ImageCodec.isJpeg2000(socSiz));
    }
}
