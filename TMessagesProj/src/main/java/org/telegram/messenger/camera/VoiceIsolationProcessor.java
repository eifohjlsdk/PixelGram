package org.telegram.messenger.camera;

import java.nio.ByteBuffer;

/**
 * Per-recording-session voice isolation DSP: an optional 90Hz-7kHz bandpass (two cascaded
 * Butterworth biquads, RBJ Audio EQ Cookbook forms) followed by an optional downward-expander
 * gate, applied in place to a mono PCM buffer - process() for 16-bit PCM (the voice-message
 * path), processFloat() for ENCODING_PCM_FLOAT (the round-video path, which captures in float
 * to avoid an early quantization step - see PixelGramSettings.applyMicGain's doc). Both share
 * the same per-sample DSP via filterOneSample(); only the buffer read/write format differs.
 * Runs before PixelGramSettings.applyMicGain and before encoding, in both paths.
 *
 * Stateful - filter delay lines, the envelope follower, and the gate's hold counter/smoothed
 * gain all carry across successive read() buffers within one recording. One instance must live
 * for the lifetime of a single AudioRecord session: construct fresh every time prepareEncoder()
 * (or MediaController's equivalent) creates a new AudioRecord, never share across recordings.
 *
 * Runs entirely in float, normalized to [-1, 1], rather than raw int16 - the pre-gain signal
 * this operates on is quiet (see FINDINGS.md's audio matrix: -39dB baseline), and this stage now
 * sits before a mic-gain multiply that can be up to 3x (+9.5dB). Filtering in int16 arithmetic
 * would let quantization noise accumulate across the cascaded biquads and then get amplified
 * right along with the real signal.
 *
 * Scope: this targets ordinary room noise that's either out of the 90Hz-7kHz speech band
 * (rumble, hiss) or in-band but reliably quieter than speech with real silent gaps (HVAC hum,
 * distant traffic, handling noise between sentences). It is not expected to meaningfully help
 * against a same-band, comparable-level interferer like nearby music - see FINDINGS.md's
 * design writeup for why: a frequency filter can't distinguish "voice" from "instrument playing
 * the same frequencies," and a gate can't duck noise that doesn't get quieter during the
 * talker's own pauses.
 */
public class VoiceIsolationProcessor {

    // Butterworth Q (1/sqrt(2)) for a maximally-flat passband with no resonant peak - a gentle
    // roll-off rather than a colored/peaky one.
    private static final float Q = 0.70710678f;
    private static final float HIGH_PASS_HZ = 90f;
    private static final float LOW_PASS_HZ = 7000f;

    // Level-detector envelope: fast attack so real level rises are tracked promptly, moderate
    // release so it doesn't chase every zero-crossing.
    private static final float ENV_ATTACK_MS = 5f;
    private static final float ENV_RELEASE_MS = 50f;

    // The gate's own output-gain ramp: fast open so speech onsets aren't clipped, slow close so
    // brief dips (word/syllable gaps, breaths) don't trigger closing - together with the hold
    // time below, this is the anti-chatter design.
    private static final float GATE_ATTACK_MS = 10f;
    private static final float GATE_RELEASE_MS = 200f;
    // Minimum time the gate stays fully open after the last above-threshold sample, before the
    // slow release ramp even starts - covers a normal pause between words outright, rather than
    // relying on the release ramp alone to ride through it.
    private static final float GATE_HOLD_MS = 100f;

    // Downward-expansion ratio below threshold - finite (not a hard mute), so crossing the
    // threshold is a gentle level reduction, not a discontinuous drop to silence. Not exposed as
    // a setting (only the threshold is, per request) - a fixed ratio is unlikely to need
    // per-room retuning the way the threshold does.
    private static final float RATIO = 4f;

    // High-pass biquad coefficients (normalized so a0 = 1) and delay-line state.
    private final float hpB0, hpB1, hpB2, hpA1, hpA2;
    private float hpX1, hpX2, hpY1, hpY2;

    // Low-pass biquad coefficients and delay-line state, cascaded after the high-pass.
    private final float lpB0, lpB1, lpB2, lpA1, lpA2;
    private float lpX1, lpX2, lpY1, lpY2;

    // Envelope follower + gate state.
    private final float envAttackCoef, envReleaseCoef;
    private final float gateAttackCoef, gateReleaseCoef;
    private final int holdSamples;
    private float envelope;
    private float currentGain = 1f;
    private int holdCounter;

    public VoiceIsolationProcessor(int sampleRate) {
        float[] hp = computeHighPass(HIGH_PASS_HZ, sampleRate, Q);
        hpB0 = hp[0]; hpB1 = hp[1]; hpB2 = hp[2]; hpA1 = hp[3]; hpA2 = hp[4];

        float[] lp = computeLowPass(LOW_PASS_HZ, sampleRate, Q);
        lpB0 = lp[0]; lpB1 = lp[1]; lpB2 = lp[2]; lpA1 = lp[3]; lpA2 = lp[4];

        envAttackCoef = timeConstantToCoef(ENV_ATTACK_MS, sampleRate);
        envReleaseCoef = timeConstantToCoef(ENV_RELEASE_MS, sampleRate);
        gateAttackCoef = timeConstantToCoef(GATE_ATTACK_MS, sampleRate);
        gateReleaseCoef = timeConstantToCoef(GATE_RELEASE_MS, sampleRate);
        holdSamples = Math.round(GATE_HOLD_MS / 1000f * sampleRate);
    }

    /** One-pole smoothing coefficient for a given time constant at this sample rate:
     * coef such that, applied every sample as `v += coef * (target - v)`, v reaches ~63% of a
     * step change in `ms` milliseconds. */
    private static float timeConstantToCoef(float ms, int sampleRate) {
        return 1f - (float) Math.exp(-1.0 / (ms / 1000.0 * sampleRate));
    }

    // RBJ Audio EQ Cookbook high-pass biquad, coefficients normalized by a0.
    private static float[] computeHighPass(float f0, int sampleRate, float q) {
        double w0 = 2 * Math.PI * f0 / sampleRate;
        double cosw0 = Math.cos(w0);
        double alpha = Math.sin(w0) / (2 * q);
        double a0 = 1 + alpha;
        double b0 = (1 + cosw0) / 2;
        double b1 = -(1 + cosw0);
        double b2 = (1 + cosw0) / 2;
        double a1 = -2 * cosw0;
        double a2 = 1 - alpha;
        return new float[]{(float) (b0 / a0), (float) (b1 / a0), (float) (b2 / a0), (float) (a1 / a0), (float) (a2 / a0)};
    }

    // RBJ Audio EQ Cookbook low-pass biquad, coefficients normalized by a0.
    private static float[] computeLowPass(float f0, int sampleRate, float q) {
        double w0 = 2 * Math.PI * f0 / sampleRate;
        double cosw0 = Math.cos(w0);
        double alpha = Math.sin(w0) / (2 * q);
        double a0 = 1 + alpha;
        double b0 = (1 - cosw0) / 2;
        double b1 = 1 - cosw0;
        double b2 = (1 - cosw0) / 2;
        double a1 = -2 * cosw0;
        double a2 = 1 - alpha;
        return new float[]{(float) (b0 / a0), (float) (b1 / a0), (float) (b2 / a0), (float) (a1 / a0), (float) (a2 / a0)};
    }

    /** Processes length bytes (length/2 little-endian 16-bit samples) of buffer in place.
     * No-op besides a mode check when voice isolation is off - mode and threshold are read
     * fresh from PixelGramSettings on every call, same "read live, don't cache" convention as
     * the rest of this package; only the sample-rate-derived coefficients above are fixed at
     * construction. */
    public void process(ByteBuffer buffer, int length) {
        int mode = PixelGramSettings.getVoiceIsolationMode();
        if (mode == PixelGramSettings.VOICE_ISOLATION_OFF) {
            return;
        }
        boolean gateEnabled = mode == PixelGramSettings.VOICE_ISOLATION_BANDPASS_GATE;
        float thresholdDb = PixelGramSettings.getVoiceIsolationGateThresholdDb();

        for (int i = 0; i + 1 < length; i += 2) {
            float filtered = filterOneSample(buffer.getShort(i) / 32768f, gateEnabled, thresholdDb);
            int outSample = Math.round(filtered * 32768f);
            if (outSample > Short.MAX_VALUE) outSample = Short.MAX_VALUE;
            else if (outSample < Short.MIN_VALUE) outSample = Short.MIN_VALUE;
            buffer.putShort(i, (short) outSample);
        }
    }

    /** Same DSP as process(ByteBuffer, int), but reads/writes native-endian 32-bit float
     * samples already normalized to [-1, 1] (AudioFormat.ENCODING_PCM_FLOAT) instead of
     * scaled 16-bit shorts - used by capture paths that record in float to avoid quantizing
     * the signal to 16-bit ahead of this stage. length is in bytes (length/4 float samples),
     * matching process()'s byte-count convention. No int16 conversion happens anywhere in this
     * path - filtered is written back as-is. */
    public void processFloat(ByteBuffer buffer, int length) {
        int mode = PixelGramSettings.getVoiceIsolationMode();
        if (mode == PixelGramSettings.VOICE_ISOLATION_OFF) {
            return;
        }
        boolean gateEnabled = mode == PixelGramSettings.VOICE_ISOLATION_BANDPASS_GATE;
        float thresholdDb = PixelGramSettings.getVoiceIsolationGateThresholdDb();

        for (int i = 0; i + 3 < length; i += 4) {
            float filtered = filterOneSample(buffer.getFloat(i), gateEnabled, thresholdDb);
            buffer.putFloat(i, filtered);
        }
    }

    /** The bandpass + gate DSP for one sample, shared by process()/processFloat() - operates
     * entirely on the [-1, 1]-normalized float domain regardless of which buffer format the
     * caller stores samples in. Advances all filter/envelope/gate state by exactly one sample;
     * callers must call this once per input sample, in order. */
    private float filterOneSample(float x, boolean gateEnabled, float thresholdDb) {
        float hpY = hpB0 * x + hpB1 * hpX1 + hpB2 * hpX2 - hpA1 * hpY1 - hpA2 * hpY2;
        hpX2 = hpX1; hpX1 = x;
        hpY2 = hpY1; hpY1 = hpY;

        float lpY = lpB0 * hpY + lpB1 * lpX1 + lpB2 * lpX2 - lpA1 * lpY1 - lpA2 * lpY2;
        lpX2 = lpX1; lpX1 = hpY;
        lpY2 = lpY1; lpY1 = lpY;

        float filtered = lpY;

        if (gateEnabled) {
            float absSample = Math.abs(filtered);
            float envCoef = (absSample > envelope) ? envAttackCoef : envReleaseCoef;
            envelope += envCoef * (absSample - envelope);

            float envDb = 20f * (float) Math.log10(Math.max(envelope, 1e-6f));
            float targetGain;
            if (envDb >= thresholdDb) {
                holdCounter = holdSamples;
                targetGain = 1f;
            } else if (holdCounter > 0) {
                holdCounter--;
                targetGain = 1f;
            } else {
                float belowDb = thresholdDb - envDb;
                float reductionDb = belowDb * (1f - 1f / RATIO);
                targetGain = (float) Math.pow(10.0, -reductionDb / 20.0);
            }

            float gainCoef = (targetGain > currentGain) ? gateAttackCoef : gateReleaseCoef;
            currentGain += gainCoef * (targetGain - currentGain);
            filtered *= currentGain;
        }

        return filtered;
    }
}
