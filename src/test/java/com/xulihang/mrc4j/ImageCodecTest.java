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
