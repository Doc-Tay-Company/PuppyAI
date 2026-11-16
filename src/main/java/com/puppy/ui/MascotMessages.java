package com.puppy.ui;

import java.util.List;
import java.util.Objects;
import java.util.random.RandomGenerator;

/**
 * Short, friendly lines the desktop mascot says on its own.
 *
 * <p>Click lines are a replacement for the old hover bubble: the mascot stays
 * quiet until the user clicks it. Notification lines appear periodically while
 * Puppy is idle, and wake lines are used when a sleeping mascot is clicked.</p>
 */
public final class MascotMessages {

    private static final List<String> CLICK_MESSAGES = List.of(
            "Woof! Need a hand?",
            "I'm right here if you want to chat.",
            "Psst... your next idea is waiting.",
            "One small step at a time!",
            "Take a breath, then let's go.",
            "Ready when you are!",
            "You're doing better than you think.",
            "Want to study together for a bit?",
            "A short break counts too.",
            "Boop! Consider yourself encouraged.",
            "Tell me what you're working on."
    );

    private static final List<String> NOTIFICATION_MESSAGES = List.of(
            "Water break? Your brain will thank you.",
            "Stretch those shoulders for a moment.",
            "You've been at it for a while. Maybe a tiny pause?",
            "Don't forget to blink and look away from the screen.",
            "A 25-minute focus session could fit right about now.",
            "Sit up straight, take a sip of water, and keep going."
    );

    private static final List<String> WAKE_MESSAGES = List.of(
            "Mmm... I'm awake! What's up?",
            "Woof! I was dreaming about treats.",
            "I'm back. Did you need me?",
            "Yawn... ready to help!"
    );

    private MascotMessages() {
    }

    public static String randomClickMessage(RandomGenerator random) {
        return pick(random, CLICK_MESSAGES);
    }

    public static String randomNotification(RandomGenerator random) {
        return pick(random, NOTIFICATION_MESSAGES);
    }

    public static String randomWakeMessage(RandomGenerator random) {
        return pick(random, WAKE_MESSAGES);
    }

    private static String pick(RandomGenerator random, List<String> messages) {
        Objects.requireNonNull(random, "random");
        return messages.get(random.nextInt(messages.size()));
    }
}
