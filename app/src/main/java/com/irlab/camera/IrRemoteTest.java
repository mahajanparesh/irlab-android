package com.irlab.camera;

import android.os.SystemClock;

/**
 * Empirical NIR-sensitivity test. A TV / AC remote emits pulsed ~940 nm (sometimes 850 nm) light that
 * is invisible to the eye. If a camera's IR-cut filter lets some of it through, the LED shows up as a
 * small spot that flickers from frame to frame while the rest of the scene stays still. The detector looks
 * for exactly that: large local luminance jumps with almost no global change.
 */
final class IrRemoteTest {
    static final long DURATION_MS = 7000;
    private static final float SPOT_DELTA = 0.16f;   // ~40/255 jump in one cell
    private static final float STILL_SCENE = 0.02f;  // average change must stay tiny
    private static final int EVENTS_REQUIRED = 3;

    private long startMs;
    private boolean running;
    private int frames, events, movingFrames;
    private float peakDelta;

    void start() {
        startMs = SystemClock.elapsedRealtime();
        running = true;
        frames = events = movingFrames = 0;
        peakDelta = 0f;
    }

    void cancel() {
        running = false;
    }

    boolean isRunning() {
        return running;
    }

    long remainingMs() {
        return Math.max(0, DURATION_MS - (SystemClock.elapsedRealtime() - startMs));
    }

    /** @return true when the test window has just finished. */
    boolean feed(IrRenderer.FrameStats s) {
        if (!running) return false;
        if (s.consecutive) {
            frames++;
            if (s.meanAbsDelta >= STILL_SCENE) {
                movingFrames++;
            } else if (s.maxDelta >= SPOT_DELTA) {
                events++;
                peakDelta = Math.max(peakDelta, s.maxDelta);
            }
        }
        if (remainingMs() == 0) {
            running = false;
            return true;
        }
        return false;
    }

    boolean detected() {
        return events >= EVENTS_REQUIRED;
    }

    boolean valid() {
        return frames >= 30 && movingFrames < frames / 2;
    }

    int events() {
        return events;
    }

    String summary() {
        if (!valid()) {
            return "Inconclusive: the camera or scene moved too much (" + movingFrames + "/" + frames
                    + " frames). Rest the phone on a table and try again.";
        }
        if (detected()) {
            return String.format(java.util.Locale.US,
                    "IR DETECTED: %d flicker events (peak jump %.0f%%). This camera's filter passes some "
                            + "~850-940 nm near-infrared. Real, but heavily attenuated.", events, peakDelta * 100);
        }
        return "No IR flicker seen in " + frames + " frames. This camera's IR-cut filter blocks the remote's "
                + "~940 nm LED (expected for this class of sensor).";
    }
}
