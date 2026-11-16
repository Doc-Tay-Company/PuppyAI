package com.puppy.ui;

import com.puppy.enums.MascotState;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Guards the mascot frame resources and per-state animation configuration. */
class MascotSpritesTest {

    private static final Map<MascotState, Integer> FRAME_COUNTS = Map.of(
            MascotState.IDLE, 3,
            MascotState.HAPPY, 2,
            MascotState.THINKING, 2,
            MascotState.TALKING, 2,
            MascotState.STUDYING, 2,
            MascotState.SLEEPING, 2);

    private static final Map<MascotState, Double> FRAME_DURATIONS = Map.of(
            MascotState.IDLE, 2_000.0,
            MascotState.HAPPY, 2_000.0,
            MascotState.THINKING, 500.0,
            MascotState.TALKING, 2_000.0,
            MascotState.STUDYING, 2_000.0,
            MascotState.SLEEPING, 1_000.0);

    @Test
    void eachStateHasExpectedFrameCountAndTiming() {
        assertEquals(FRAME_COUNTS.keySet(), Set.of(MascotState.values()));
        for (MascotState state : MascotState.values()) {
            MascotSprites.Animation animation = MascotSprites.animationFor(state);
            assertEquals(FRAME_COUNTS.get(state), animation.frameCount(), "Frame count for " + state);
            assertEquals(FRAME_DURATIONS.get(state), animation.frameDurationMillis(), "Frame duration for " + state);
        }
    }

    @Test
    void everyFrameResourceIsAReadableTransparentPng() throws IOException {
        for (MascotState state : MascotState.values()) {
            MascotSprites.Animation animation = MascotSprites.animationFor(state);
            for (String resource : animation.frameResources()) {
                try (InputStream stream = MascotSpritesTest.class.getResourceAsStream(resource)) {
                    assertNotNull(stream, "Missing mascot frame: " + resource);
                    BufferedImage frame = ImageIO.read(stream);
                    assertNotNull(frame, "Mascot frame is not a readable image: " + resource);
                    assertEquals(384, frame.getWidth(), "Unexpected frame width: " + resource);
                    assertEquals(384, frame.getHeight(), "Unexpected frame height: " + resource);
                    assertTrue(frame.getColorModel().hasAlpha(), "Mascot frame has no alpha channel: " + resource);
                    assertEquals(0, frame.getRGB(0, 0) >>> 24, "Mascot frame background is not transparent: " + resource);
                }
            }
        }
    }

    @Test
    void iconIsPackaged() throws IOException {
        try (InputStream stream = MascotSpritesTest.class.getResourceAsStream(MascotSprites.ICON_RESOURCE)) {
            assertNotNull(stream, "Missing application icon: " + MascotSprites.ICON_RESOURCE);
            assertNotNull(ImageIO.read(stream), "Application icon is not a readable image");
        }
    }
}
