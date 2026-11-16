package com.puppy.ui;

import com.puppy.enums.MascotState;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Describes the ordered image frames and timing used by each mascot state.
 */
public final class MascotSprites {

    /** Classpath location of the application icon. */
    public static final String ICON_RESOURCE = "/icons/puppy.png";

    /**
     * Timing for one mascot animation.
     *
     * @param frameResources      ordered classpath locations of the frame images
     * @param frameDurationMillis how long one frame is shown at normal speed
     */
    public record Animation(List<String> frameResources, double frameDurationMillis) {

        public Animation {
            frameResources = List.copyOf(Objects.requireNonNull(frameResources, "frameResources"));
            if (frameResources.isEmpty()) {
                throw new IllegalArgumentException("frameResources must not be empty");
            }
            if (frameResources.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("frame resource paths must not be blank");
            }
            if (!Double.isFinite(frameDurationMillis) || frameDurationMillis <= 0) {
                throw new IllegalArgumentException("frameDurationMillis must be positive");
            }
        }

        public int frameCount() {
            return frameResources.size();
        }
    }

    private static final Map<MascotState, Animation> ANIMATIONS = animations();

    private MascotSprites() {
    }

    private static Map<MascotState, Animation> animations() {
        Map<MascotState, Animation> map = new EnumMap<>(MascotState.class);
        map.put(MascotState.IDLE, animation("idle", 3, 2_000));
        map.put(MascotState.HAPPY, animation("happy", 2, 2_000));
        map.put(MascotState.THINKING, animation("thinking", 2, 500));
        map.put(MascotState.TALKING, animation("talking", 2, 2_000));
        map.put(MascotState.STUDYING, animation("study", 2, 2_000));
        map.put(MascotState.SLEEPING, animation("sleep", 2, 1_000));
        return Map.copyOf(map);
    }

    private static Animation animation(String folder, int frameCount, double frameDurationMillis) {
        List<String> frames = new ArrayList<>(frameCount);
        for (int frame = 0; frame < frameCount; frame++) {
            frames.add("/sprites/" + folder + "/" + frame + ".png");
        }
        return new Animation(frames, frameDurationMillis);
    }

    public static Animation animationFor(MascotState state) {
        Objects.requireNonNull(state, "state");
        return ANIMATIONS.get(state);
    }
}
