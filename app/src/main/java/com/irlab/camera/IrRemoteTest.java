package com.irlab.camera;

import android.os.SystemClock;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Empirical NIR-sensitivity test. A TV / AC remote emits pulsed ~940 nm (sometimes 850 nm) light that
 * is invisible to the eye. If a camera's IR-cut filter lets some of it through, the LED shows up as a
 * small spot that flickers from frame to frame in one place while the rest of the scene stays still.
 *
 * The detector therefore only counts a frame as an "event" when the changed area is SMALL and COMPACT,
 * and only reports IR when several events happen at the SAME location. Video playing on a TV or monitor
 * changes large or scattered areas and is rejected (found while testing on a Galaxy S25 Ultra).
 */
final class IrRemoteTest {
    static final long DURATION_MS = 7000;
    private static final float STILL_SCENE = 0.02f;  // average change must stay tiny
    private static final int MAX_SPOT_CELLS = 80;    // of 96x128 cells (~0.65 % of the frame)
    private static final int MAX_SPOT_SPAN = 14;     // cells; ~220 px on a 1440 px wide frame
    private static final int CLUSTER_RADIUS = 6;     // cells
    private static final int EVENTS_REQUIRED = 5;   // a held remote button repeats ~10x per second

    private long startMs;
    private boolean running;
    private int frames, movingFrames, largeChangeFrames;
    private final List<int[]> events = new ArrayList<>();
    private int clusterSize;

    void start() {
        startMs = SystemClock.elapsedRealtime();
        running = true;
        frames = movingFrames = largeChangeFrames = 0;
        events.clear();
        clusterSize = 0;
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
            } else if (s.spotCells > 0) {
                int spanX = s.spotMaxX - s.spotMinX + 1, spanY = s.spotMaxY - s.spotMinY + 1;
                if (s.spotCells <= MAX_SPOT_CELLS && spanX <= MAX_SPOT_SPAN && spanY <= MAX_SPOT_SPAN) {
                    events.add(new int[]{(s.spotMinX + s.spotMaxX) / 2, (s.spotMinY + s.spotMaxY) / 2});
                } else {
                    largeChangeFrames++;
                }
            }
        }
        if (remainingMs() == 0) {
            running = false;
            clusterSize = largestCluster();
            return true;
        }
        return false;
    }

    /** Size of the biggest group of events that sit at (nearly) the same place. */
    private int largestCluster() {
        int best = 0;
        for (int[] a : events) {
            int n = 0;
            for (int[] b : events) {
                if (Math.abs(a[0] - b[0]) <= CLUSTER_RADIUS && Math.abs(a[1] - b[1]) <= CLUSTER_RADIUS) n++;
            }
            best = Math.max(best, n);
        }
        return best;
    }

    boolean detected() {
        return clusterSize >= EVENTS_REQUIRED;
    }

    boolean valid() {
        return frames >= 30 && movingFrames < frames / 2;
    }

    int events() {
        return clusterSize;
    }

    String summary() {
        if (!valid()) {
            return "Inconclusive: the camera or scene moved too much (" + movingFrames + "/" + frames
                    + " frames). Rest the phone on a table and try again.";
        }
        String screenNote = largeChangeFrames > frames / 4
                ? " Large areas kept changing (" + largeChangeFrames + " frames). Is a TV or monitor playing in view? "
                  + "Those changes were ignored, but aim away from screens for a cleaner test."
                : "";
        if (detected()) {
            return String.format(Locale.US,
                    "IR DETECTED: a small spot flickered %d times in the same place. This camera's filter passes "
                            + "some ~850-940 nm near-infrared. Real, but heavily attenuated.%s", clusterSize, screenNote);
        }
        return "No IR flicker seen in " + frames + " frames. Either no remote was pointed at the lens, or this "
                + "camera's IR-cut filter blocks the remote's ~940 nm LED (expected for this class of sensor)."
                + screenNote;
    }
}
