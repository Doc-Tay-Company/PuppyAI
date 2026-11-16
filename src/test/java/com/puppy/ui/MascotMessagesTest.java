package com.puppy.ui;

import org.junit.jupiter.api.Test;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MascotMessagesTest {

    @Test
    void everyMessagePoolProducesReadableText() {
        Random random = new Random(7);
        for (int i = 0; i < 40; i++) {
            assertFalse(MascotMessages.randomClickMessage(random).isBlank());
            assertFalse(MascotMessages.randomWakeMessage(random).isBlank());
            assertFalse(MascotMessages.randomNotification(random).isBlank());
        }
    }
}
