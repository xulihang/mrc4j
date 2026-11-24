/*
 * mrc4j - MRC image compression for PDFs.
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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OptimiserTest {

    private static PixelBuffer white(int width, int height) {
        PixelBuffer image = new PixelBuffer(width, height, 1);
        java.util.Arrays.fill(image.getData(), (byte) 255);
        return image;
    }

    private static PixelBuffer blackBlock(PixelBuffer image, int x, int y,
                                          int width, int height) {
        for (int yy = y; yy < y + height; yy++) {
            for (int xx = x; xx < x + width; xx++) {
                image.put1(xx, yy, 0);
            }
        }
        return image;
    }

    private static Mask blockMask(int width, int height, int x, int y,
                                  int blockWidth, int blockHeight) {
        Mask mask = new Mask(width, height);
        for (int yy = y; yy < y + blockHeight; yy++) {
            for (int xx = x; xx < x + blockWidth; xx++) {
                mask.set(xx, yy, true);
            }
        }
        return mask;
    }

    @Test
    void backgroundRemovesInk() {
        PixelBuffer image = blackBlock(white(20, 20), 8, 8, 4, 4);
        Mask mask = blockMask(20, 20, 8, 8, 4, 4);

        PixelBuffer background = Optimiser.background(image, mask);

        // The black block is replaced by the surrounding white paper.
        assertTrue(background.get1(9, 9) > 200,
                "inpainted pixel was " + background.get1(9, 9));
    }

    @Test
    void foregroundDiffusesInkColour() {
        PixelBuffer image = blackBlock(white(20, 20), 8, 8, 4, 4);
        Mask mask = blockMask(20, 20, 8, 8, 4, 4);

        PixelBuffer background = Optimiser.background(image, mask);
        PixelBuffer foreground = Optimiser.foreground(image, mask, background);

        // Ink pixels keep their exact colour.
        assertTrue(foreground.get1(9, 9) < 20);
        // A pixel next to the ink receives a dark local average.
        assertTrue(foreground.get1(6, 9) < 200,
                "near ink pixel was " + foreground.get1(6, 9));
        // Far away a flat ink colour is used (that area is hidden by the mask).
        assertTrue(foreground.get1(0, 0) < 20,
                "far ink pixel was " + foreground.get1(0, 0));
    }

    @Test
    void dilateGrowsTheMaskByTheGivenRadius() {
        Mask mask = new Mask(11, 11);
        mask.set(5, 5, true);

        Mask one = Optimiser.dilate(mask, 1);
        assertTrue(one.get(4, 4));
        assertTrue(one.get(6, 6));
        assertFalse(one.get(3, 5));

        Mask two = Optimiser.dilate(mask, 2);
        assertTrue(two.get(3, 3));
        assertFalse(two.get(2, 5));

        // Radius 0 is a no-op and returns the same mask.
        assertSame(mask, Optimiser.dilate(mask, 0));
    }

    @Test
    void denoiseDropsSinglePixelsAndKeepsDots() {
        Mask mask = new Mask(12, 12);
        mask.set(5, 5, true);
        for (int y = 1; y <= 3; y++) {
            for (int x = 1; x <= 3; x++) {
                mask.set(x, y, true);
            }
        }

        Optimiser.denoise(mask);

        assertFalse(mask.get(5, 5));
        for (int y = 1; y <= 3; y++) {
            for (int x = 1; x <= 3; x++) {
                assertTrue(mask.get(x, y), "dot pixel " + x + "," + y + " was dropped");
            }
        }
    }
}
