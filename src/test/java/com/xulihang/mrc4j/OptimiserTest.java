package com.xulihang.mrc4j;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OptimiserTest {

    @Test
    void spreadsMaskedPixelIntoWindow() {
        PixelBuffer img = new PixelBuffer(4, 1, 1);
        for (int x = 0; x < 4; x++) {
            img.put(x, 0, 0, 200);
        }
        Mask mask = new Mask(4, 1);
        mask.set(2, 0, true);
        PixelBuffer out = Optimiser.optimise(img, mask, 1);
        // x=2 is masked and keeps its value; x=3 sees the masked pixel, x=0/1 see nothing
        assertEquals(200, out.get(2, 0, 0));
        assertEquals(200, out.get(3, 0, 0));
    }
}
