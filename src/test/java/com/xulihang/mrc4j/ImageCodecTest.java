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
