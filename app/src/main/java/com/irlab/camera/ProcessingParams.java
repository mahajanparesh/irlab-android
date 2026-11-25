package com.irlab.camera;

import java.util.Locale;

/** Image-processing settings applied by the GPU shader (preview and capture). */
final class ProcessingParams {
    static final int MODE_PASSTHROUGH = -1;
    static final int MODE_NATURAL = 0;
    static final int MODE_IR_MONO = 1;
    static final int MODE_FALSE_COLOUR = 2;
    static final int MODE_CHANNEL_SWAP = 3;
    static final int MODE_IR_TEST = 4;

    static final int PALETTE_IRONBOW = 0;
    static final int PALETTE_RAINBOW = 1;
    static final int PALETTE_NIGHT = 2;

    int mode = MODE_NATURAL;
    int palette = PALETTE_IRONBOW;
    float brightness = 0f;   // -0.5 .. 0.5
    float contrast = 1f;     // 0.5 .. 2.5
    float gamma = 1f;        // 0.4 .. 2.5
    float enhance = 0f;      // 0 .. 2 (unsharp-mask strength)
    /** Channel weights used to build the "IR" luminance. Red carries most residual NIR on Bayer sensors. */
    float[] irWeights = {0.60f, 0.30f, 0.10f};

    ProcessingParams copy() {
        ProcessingParams p = new ProcessingParams();
        p.mode = mode;
        p.palette = palette;
        p.brightness = brightness;
        p.contrast = contrast;
        p.gamma = gamma;
        p.enhance = enhance;
        p.irWeights = irWeights.clone();
        return p;
    }

    String modeTag() {
        switch (mode) {
            case MODE_IR_MONO: return "IRMONO";
            case MODE_FALSE_COLOUR: return "FALSECOL";
            case MODE_CHANNEL_SWAP: return "SWAP";
            case MODE_IR_TEST: return "IRTEST";
            default: return "NATURAL";
        }
    }

    String paletteName() {
        switch (palette) {
            case PALETTE_RAINBOW: return "rainbow";
            case PALETTE_NIGHT: return "night-green";
            default: return "ironbow";
        }
    }

    String describe() {
        return String.format(Locale.US,
                "mode=%s%s brightness=%+.2f contrast=%.2f gamma=%.2f enhance=%.2f irWeights=R%.2f/G%.2f/B%.2f",
                modeTag(), mode == MODE_FALSE_COLOUR ? " palette=" + paletteName() : "",
                brightness, contrast, gamma, enhance, irWeights[0], irWeights[1], irWeights[2]);
    }
}
