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

import java.util.Arrays;

/**
 * Binary mask, one byte per pixel, {@code 1} = set (foreground / "text").
 * The Python code uses numpy boolean or uint8 arrays for the same purpose.
 */
public final class Mask {

    private final int width;
    private final int height;
    private final byte[] data;

    public Mask(int width, int height) {
        this.width = width;
        this.height = height;
        this.data = new byte[width * height];
    }

    public int getWidth() {
        return width;
    }

    public int getHeight() {
        return height;
    }

    public byte[] getData() {
        return data;
    }

    public boolean get(int x, int y) {
        return data[y * width + x] != 0;
    }

    public void set(int x, int y, boolean v) {
        data[y * width + x] = (byte) (v ? 1 : 0);
    }

    public void or(int x, int y, boolean v) {
        if (v) {
            data[y * width + x] = 1;
        }
    }

    public long count() {
        long n = 0;
        for (byte b : data) {
            if (b != 0) {
                n++;
            }
        }
        return n;
    }

    public Mask invert() {
        Mask m = new Mask(width, height);
        for (int i = 0; i < data.length; i++) {
            m.data[i] = (byte) (data[i] == 0 ? 1 : 0);
        }
        return m;
    }

    public void copyRegionFrom(Mask src, int x0, int y0) {
        for (int y = 0; y < src.height; y++) {
            System.arraycopy(src.data, y * src.width, data, (y0 + y) * width + x0, src.width);
        }
    }

    /** Raw bytes, one per pixel. */
    public byte[] toBytes() {
        return data.clone();
    }

    /** Rebuild a mask from {@link #toBytes()} output. */
    public static Mask fromBytes(int width, int height, byte[] bytes) {
        MasksLength.check(width, height, bytes);
        Mask m = new Mask(width, height);
        System.arraycopy(bytes, 0, m.data, 0, bytes.length);
        return m;
    }

    @Override
    public String toString() {
        return "Mask[" + width + "x" + height + ", set=" + count() + "]";
    }

    private static final class MasksLength {
        static void check(int width, int height, byte[] bytes) {
            if (bytes.length != width * height) {
                throw new IllegalArgumentException("mask size mismatch");
            }
        }
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof Mask)) {
            return false;
        }
        Mask m = (Mask) o;
        return m.width == width && m.height == height && Arrays.equals(m.data, data);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(data) * 31 + width;
    }
}
