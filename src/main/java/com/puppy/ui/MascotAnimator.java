package com.puppy.ui;

import com.puppy.enums.MascotState;
import javafx.animation.AnimationTimer;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Plays mascot sprite animations on an {@link ImageView}.
 *
 * <p>Each state's frame images are loaded once and played in order by changing
 * the image view's image. Frame timing comes from {@link MascotSprites}.</p>
 *
 * <p>Must be used on the JavaFX Application Thread.</p>
 */
public final class MascotAnimator {

    private final ImageView target;
    private final Map<MascotState, List<Image>> frames;
    private final AnimationTimer timer;

    private MascotState currentState;
    private int frameIndex;
    private long currentFrameDurationNanos;
    private long lastFrameNanos;
    private double speed = 1.0;
    private boolean paused;
    private boolean started;

    public MascotAnimator(ImageView target) {
        this.target = Objects.requireNonNull(target, "target");
        this.frames = loadFrames();
        target.setViewport(null);
        target.setImage(frames.get(MascotState.IDLE).get(0));

        this.timer = new AnimationTimer() {
            @Override
            public void handle(long now) {
                advance(now);
            }
        };
    }

    /**
     * Starts (or restarts) the animation for the given state. Playing the state that
     * is already running only resumes playback, so callers can safely repeat events.
     */
    public void play(MascotState state) {
        Objects.requireNonNull(state, "state");
        MascotSprites.Animation animation = MascotSprites.animationFor(state);
        boolean stateChanged = state != currentState;

        currentState = state;
        currentFrameDurationNanos = Math.round(animation.frameDurationMillis() * 1_000_000);

        if (stateChanged) {
            frameIndex = 0;
            applyFrame();
        }
        paused = false;
        lastFrameNanos = 0;
        timer.start();
        started = true;
    }

    /** Freezes the current frame without forgetting the animation. */
    public void pause() {
        paused = true;
    }

    /** Continues a paused animation. */
    public void resume() {
        if (currentState == null) {
            return;
        }
        paused = false;
        lastFrameNanos = 0;
    }

    /** Stops playback and clears the image view. */
    public void stop() {
        timer.stop();
        started = false;
        currentState = null;
        frameIndex = 0;
        target.setImage(null);
    }

    /** Multiplier for animation speed: 2.0 plays twice as fast, 0.5 half as fast. */
    public void setSpeed(double speed) {
        if (!Double.isFinite(speed) || speed <= 0) {
            throw new IllegalArgumentException("speed must be a positive finite number");
        }
        this.speed = speed;
    }

    public double getSpeed() {
        return speed;
    }

    public MascotState getCurrentState() {
        return currentState;
    }

    public int getFrameIndex() {
        return frameIndex;
    }

    public boolean isRunning() {
        return started && !paused;
    }

    private void advance(long now) {
        if (paused || currentState == null) {
            return;
        }
        if (lastFrameNanos == 0) {
            lastFrameNanos = now;
            return;
        }

        int frameCount = frames.get(currentState).size();
        long frameDuration = Math.max(1, Math.round(currentFrameDurationNanos / speed));
        long elapsed = now - lastFrameNanos;
        if (elapsed < frameDuration) {
            return;
        }

        long framesToAdvance = elapsed / frameDuration;
        frameIndex = (int) ((frameIndex + framesToAdvance) % frameCount);
        lastFrameNanos += framesToAdvance * frameDuration;
        applyFrame();
    }

    private void applyFrame() {
        target.setImage(frames.get(currentState).get(frameIndex));
    }

    private static Map<MascotState, List<Image>> loadFrames() {
        Map<MascotState, List<Image>> images = new EnumMap<>(MascotState.class);
        for (MascotState state : MascotState.values()) {
            List<String> resources = MascotSprites.animationFor(state).frameResources();
            List<Image> stateFrames = new ArrayList<>(resources.size());
            for (String resource : resources) {
                stateFrames.add(loadFrame(resource));
            }
            images.put(state, List.copyOf(stateFrames));
        }
        return Map.copyOf(images);
    }

    private static Image loadFrame(String resource) {
        try (InputStream stream = MascotAnimator.class.getResourceAsStream(resource)) {
            if (stream == null) {
                throw new IllegalStateException("Missing mascot frame on classpath: " + resource);
            }
            Image image = new Image(stream);
            if (image.isError()) {
                throw new IllegalStateException("Could not load mascot frame: " + resource, image.getException());
            }
            return image;
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read mascot frame: " + resource, e);
        }
    }
}
