package org.telegram.messenger.camera;

import java.nio.ByteBuffer;

/**
 * Optional high-shelf boost, applied in place to a mono PCM buffer - process() for 16-bit PCM,
 * processFloat() for ENCODING_PCM_FLOAT, same split as VoiceIsolationProcessor. Exists to
 * compensate the relative treble rolloff measured against the iPhone (see FINDINGS.md's
 * "Sharpness/exposure/audio re-comparison" and "Audio noise-floor and bass investigation"
 * entries) - the CAMCORDER audio source's own bass-heavy character isn't something the app
 * controls, but a gentle high-frequency lift on top of it is.
 *
 * Placement in the chain matters: this runs after VoiceIsolationProcessor and before Adaptive
 * Gain (or the fixed-multiplier path when Adaptive Gain is off) - see the call sites in
 * InstantCameraView - specifically so that whichever limiter runs last (Adaptive Gain's own
 * look-ahead limiter, or applyMicGain's soft limiter) catches any peak increase the shelf boost
 * introduces. This class does no limiting of its own.
 *
 * A few fixed strengths rather than a continuous dB control, since the point is to A/B against
 * the iPhone by ear and settle on whichever one closes the gap without sounding harsh - not to
 * hit one precisely "correct" number. Off by default.
 *
 * Stateful (biquad delay line) - same per-recording-session lifecycle as
 * SpeechEnhancer/VoiceIsolationProcessor/AdaptiveGainProcessor: construct fresh every time a new
 * AudioRecord session starts, never share an instance across recordings.
 */
public class TrebleTiltProcessor {

    // Shelf corner frequency - the lower edge of the "high" band FINDINGS.md's audio comparisons
    // already measure (3-8kHz), so the boost lands exactly where the deficit was quantified
    // rather than at an arbitrary "presence" frequency.
    private static final float SHELF_HZ = 4000f;

    // RBJ Audio EQ Cookbook shelf slope S=1 (the standard "gentle" shelf - no resonant peak at
    // the corner, same maximally-flat philosophy as VoiceIsolationProcessor's high-pass).
    private static final float SHELF_SLOPE = 1f;

    private static float gainDbForMode(int mode) {
        switch (mode) {
            case PixelGramSettings.TREBLE_TILT_LOW:
                return 2f;
            case PixelGramSettings.TREBLE_TILT_MEDIUM:
                return 4f;
            case PixelGramSettings.TREBLE_TILT_HIGH:
                return 6f;
            case PixelGramSettings.TREBLE_TILT_OFF:
            default:
                return 0f;
        }
    }

    // High-shelf biquad coefficients (normalized so a0 = 1) and delay-line state, recomputed
    // whenever the mode changes (cheap - happens at most a few times per recording) rather than
    // once at construction, since PixelGramSettings.getTrebleTiltMode() is read live like every
    // other setting in this package.
    private int cachedMode = -1;
    private float b0, b1, b2, a1, a2;
    private float x1, x2, y1, y2;

    private final int sampleRate;

    public TrebleTiltProcessor(int sampleRate) {
        this.sampleRate = sampleRate;
    }

    // RBJ Audio EQ Cookbook high-shelf biquad, coefficients normalized by a0.
    private static void computeHighShelf(float f0, float gainDb, float slope, int sampleRate, float[] out) {
        double a = Math.pow(10.0, gainDb / 40.0);
        double w0 = 2 * Math.PI * f0 / sampleRate;
        double cosw0 = Math.cos(w0);
        double sinw0 = Math.sin(w0);
        double alpha = sinw0 / 2.0 * Math.sqrt((a + 1 / a) * (1 / slope - 1) + 2);
        double twoSqrtAAlpha = 2 * Math.sqrt(a) * alpha;

        double b0 = a * ((a + 1) + (a - 1) * cosw0 + twoSqrtAAlpha);
        double b1 = -2 * a * ((a - 1) + (a + 1) * cosw0);
        double b2 = a * ((a + 1) + (a - 1) * cosw0 - twoSqrtAAlpha);
        double a0 = (a + 1) - (a - 1) * cosw0 + twoSqrtAAlpha;
        double a1 = 2 * ((a - 1) - (a + 1) * cosw0);
        double a2 = (a + 1) - (a - 1) * cosw0 - twoSqrtAAlpha;

        out[0] = (float) (b0 / a0);
        out[1] = (float) (b1 / a0);
        out[2] = (float) (b2 / a0);
        out[3] = (float) (a1 / a0);
        out[4] = (float) (a2 / a0);
    }

    private void ensureCoefficients(int mode) {
        if (mode == cachedMode) return;
        cachedMode = mode;
        float gainDb = gainDbForMode(mode);
        if (gainDb == 0f) return; // OFF: filterOneSample bypasses entirely, coefficients unused
        float[] coef = new float[5];
        computeHighShelf(SHELF_HZ, gainDb, SHELF_SLOPE, sampleRate, coef);
        b0 = coef[0]; b1 = coef[1]; b2 = coef[2]; a1 = coef[3]; a2 = coef[4];
    }

    /** Processes length bytes (length/2 little-endian 16-bit samples) of buffer in place.
     * No-op besides a mode check when off - mode is read fresh from PixelGramSettings on every
     * call, same "read live, don't cache" convention as the rest of this package. */
    public void process(ByteBuffer buffer, int length) {
        int mode = PixelGramSettings.getTrebleTiltMode();
        if (mode == PixelGramSettings.TREBLE_TILT_OFF) return;
        ensureCoefficients(mode);

        for (int i = 0; i + 1 < length; i += 2) {
            float filtered = filterOneSample(buffer.getShort(i) / 32768f);
            int outSample = Math.round(filtered * 32768f);
            if (outSample > Short.MAX_VALUE) outSample = Short.MAX_VALUE;
            else if (outSample < Short.MIN_VALUE) outSample = Short.MIN_VALUE;
            buffer.putShort(i, (short) outSample);
        }
    }

    /** Same DSP as process(ByteBuffer, int), but reads/writes native-endian 32-bit float samples
     * already normalized to [-1, 1] (AudioFormat.ENCODING_PCM_FLOAT) - see VoiceIsolationProcessor's
     * processFloat() for why the float path skips int16 quantization entirely. length is in
     * bytes (length/4 float samples). Deliberately not clamped to [-1,1] here - same convention
     * as every other stage in this chain; the limiter that runs after this one is what catches
     * any peak this boost pushes over the top. */
    public void processFloat(ByteBuffer buffer, int length) {
        int mode = PixelGramSettings.getTrebleTiltMode();
        if (mode == PixelGramSettings.TREBLE_TILT_OFF) return;
        ensureCoefficients(mode);

        for (int i = 0; i + 3 < length; i += 4) {
            float filtered = filterOneSample(buffer.getFloat(i));
            buffer.putFloat(i, filtered);
        }
    }

    private float filterOneSample(float x) {
        float y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2;
        x2 = x1; x1 = x;
        y2 = y1; y1 = y;
        return y;
    }
}
