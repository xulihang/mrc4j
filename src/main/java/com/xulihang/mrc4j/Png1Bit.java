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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.CRC32;
import java.util.zip.Deflater;


/**
 * Minimal 1-bit (grayscale 1 bpp, palette black/white) PNG writer.
 *
 * <p>Pillow's {@code Image.new('1')} / {@code np.ndarray(bool)} → PNG path in
 * mrc.py writes exactly this: bit depth 1, colour type 3 (palette) with a
 * black/white palette, so jbig2enc receives proper 1-bit data.</p>
 */
public final class Png1Bit {

    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private Png1Bit() {
    }

    public static byte[] encode(Mask mask) throws IOException {
        int w = mask.getWidth();
        int h = mask.getHeight();
        byte[] data = mask.getData();
        int rowBytes = (w + 7) / 8;
        ByteArrayOutputStream raw = new ByteArrayOutputStream(h * (rowBytes + 1));
        for (int y = 0; y < h; y++) {
            raw.write(0); // filter type 0
            int cur = 0;
            int count = 0;
            for (int x = 0; x < w; x++) {
                cur = (cur << 1) | (data[y * w + x] != 0 ? 1 : 0);
                if (++count == 8 || x == w - 1) {
                    if (count < 8) {
                        cur <<= (8 - count);
                    }
                    raw.write(cur);
                    cur = 0;
                    count = 0;
                }
            }
        }
        byte[] compressed = deflate(raw.toByteArray());

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(SIGNATURE);
        ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
        writeInt(ihdr, w);
        writeInt(ihdr, h);
        ihdr.write(1);   // bit depth
        ihdr.write(3);   // colour type: palette
        ihdr.write(0);   // compression
        ihdr.write(0);   // filter
        ihdr.write(0);   // interlace
        writeChunk(out, "IHDR", ihdr.toByteArray());
        // PLTE: index 0 = black background, index 1 = white set pixel
        writeChunk(out, "PLTE", new byte[]{0, 0, 0, (byte) 255, (byte) 255, (byte) 255});
        writeChunk(out, "IDAT", compressed);
        writeChunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /** Row-padded 1-bit packs (MSB first, 1 = set), as used by 1-bit PDF images. */
    public static byte[] packBits(Mask mask) {
        int w = mask.getWidth();
        int h = mask.getHeight();
        int rowBytes = (w + 7) / 8;
        byte[] out = new byte[rowBytes * h];
        byte[] d = mask.getData();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (d[y * w + x] != 0) {
                    out[y * rowBytes + (x >> 3)] |= (byte) (0x80 >>> (x & 7));
                }
            }
        }
        return out;
    }

    private static void writeInt(ByteArrayOutputStream os, int v) {
        os.write(v >>> 24);
        os.write(v >>> 16);
        os.write(v >>> 8);
        os.write(v);
    }

    private static void writeChunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        writeInt(out, data.length);
        out.write(t, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
    }

    private static byte[] deflate(byte[] data) {
        Deflater d = new Deflater(Deflater.BEST_SPEED);
        try {
            d.setInput(data);
            d.finish();
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            while (!d.finished()) {
                int n = d.deflate(buf);
                bos.write(buf, 0, n);
            }
            return bos.toByteArray();
        } finally {
            d.end();
        }
    }
}
