package org.telegram.messenger.camera;

import java.nio.ByteBuffer;

/**
 * Per-recording-session software AGC: a slow RMS leveler (targets an adjustable overall loudness,
 * frozen during non-speech so it doesn't creep up during pauses) plus a fast look-ahead peak
 * limiter (protects against clipping), replacing PixelGramSettings' fixed gain multiplier
 * entirely when enabled - see PixelGramSettings.isAdaptiveGainEnabled(). Motivation: the fixed
 * multiplier is a single constant that's right for no one recording - 1x is too quiet for a
 * normal speaking voice, 3x is sometimes too loud, because the correct gain depends on how loud
 * the person actually is at the mic, which varies by person, distance, and room. RNNoise (if
 * active) cleans the signal but does not add level - denoising a quiet signal still leaves a
 * quiet signal, so gain and denoising address different problems (see FINDINGS.md's "Mic gain
 * reverted" section for the reasoning error that conflated the two once already).
 *
 * Same per-recording-session lifecycle as SpeechEnhancer/VoiceIsolationProcessor - construct
 * fresh every time a new AudioRecord session starts, no shared/static instance. Runs after
 * SpeechEnhancer and VoiceIsolationProcessor in the chain (denoise/isolate first, then gain-shape
 * the cleaned signal), before the existing fixed-multiplier + soft-limiter stage, which it
 * replaces outright rather than stacking with - see PixelGramSettings.applyGainFloat's call sites.
 *
 * Look-ahead without a separate delay buffer: audio already arrives in ~10ms chunks (the same
 * granularity SpeechEnhancer's own RNNoise blocks use). Because this class sees an entire
 * incoming buffer before writing any of it back, it can compute that buffer's own peak first and
 * shape the gain applied across the whole buffer accordingly - true look-ahead within the
 * buffer's own ~10ms window (in the same range as look-ahead windows real limiters use), with no
 * additional latency introduced. The one simplification this implies: gain is computed once per
 * buffer rather than continuously varying sample-by-sample within it - at audio's timescale a
 * ~10ms window is short enough that this hasn't needed finer resolution.
 *
 * Two independently-smoothed gain components, applied together:
 *   - slowGainDb: the leveler. Updated once per buffer (a variable-timestep one-pole toward
 *     whatever gain would bring this buffer's RMS to the target), asymmetric attack/release
 *     (reduces relatively promptly if a passage runs hot, recovers slowly so a brief pause
 *     mid-sentence doesn't get amplified before the next word arrives), frozen entirely during
 *     non-speech so gain doesn't creep up during silence and then overshoot when speech resumes.
 *   - limiterGainDb: the peak protector. Smoothed per-sample (fast attack, moderate release,
 *     standard limiter ballistics) toward whatever reduction the current buffer's own peak
 *     requires to stay under the ceiling - this one is not gated on speech/silence, since
 *     clipping protection should never turn off.
 *
 * Silence detection: prefers SpeechEnhancer's own RNNoise voice-activity probability when
 * available (confirmed via denoise.c's source to genuinely be a speech probability, not assumed -
 * see SpeechEnhancer.getLastVadProbability()'s doc), since a neural VAD can distinguish speech
 * from steady background noise far better than energy alone. Falls back to a simple energy
 * threshold only when Speech Enhancement is off and no RNNoise VAD signal exists at all.
 *
 * A third, optional component - silenceFloorGainDb - see PixelGramSettings.isAdaptiveGainSilenceFloorEnabled().
 * Off by default; when on, adds (never removes) just enough additional gain during non-speech to
 * keep the signal above AAC's own collapse-to-literal-zero threshold at this app's bitrate (see
 * FINDINGS.md's "AAC digital-silence floor" entries), attacking/releasing slowly enough to be a
 * floor rather than an audible event. Independent of slowGainDb - it targets the raw captured
 * silence level, not the leveler's own (unrelated) speech target.
 */
public class AdaptiveGainProcessor {

    // Peak ceiling - matches the existing soft limiter's own -3dBFS headroom choice, so switching
    // Adaptive Gain on/off doesn't change the app's basic clipping-safety margin.
    private static final float CEILING_DB = -3f;

    // Gain bounds: 0.3x-8x per the reported design, converted to dB (20*log10(x)).
    private static final float GAIN_MIN_DB = -10.46f; // 0.3x
    private static final float GAIN_MAX_DB = 18.06f;  // 8x

    // Slow leveler time constants - asymmetric on purpose. Reacts to "too loud" faster than to
    // "too quiet" so a hot passage gets reined in reasonably promptly, but a brief pause doesn't
    // get amplified (and then overshoot) before the next word arrives. Adjustable (see
    // PixelGramSettings.getAdaptiveGainSlowAttackSec/getAdaptiveGainSlowReleaseSec) - read live
    // from settings every buffer, same "read live, don't cache" convention as the rest of this
    // package, rather than fixed constants: the 1.0s/4.0s originals don't fully converge within a
    // typical 8-10s round-video clip (see FINDINGS.md's 2026-09-05 leveler-timing report), but
    // shortening them costs audible pumping at syllable/word-gap timescale, so this is exposed for
    // the user to compare rather than hardcoded to one point on that tradeoff.

    // Fast limiter time constants - classic look-ahead-limiter ballistics: near-instant
    // reduction, a release slow enough not to visibly (audibly) pump right after a peak.
    private static final float LIMITER_ATTACK_MS = 5f;
    private static final float LIMITER_RELEASE_MS = 100f;

    // RNNoise vad_prob threshold for "speech present" - see the class doc for why this signal is
    // preferred over energy alone when available.
    private static final float VAD_SPEECH_THRESHOLD = 0.5f;

    // Optional silence floor - see isAdaptiveGainSilenceFloorEnabled()'s doc and FINDINGS.md's
    // "AAC digital-silence floor" entries: measured the encoder's own collapse-to-literal-zero
    // threshold at roughly -60 to -75dBFS at this app's bitrate, confirmed independent of
    // bitrate (raising it didn't move the threshold - this really is the codec deciding
    // sufficiently quiet content isn't worth encoding, not a bit-budget problem). -60dB sits
    // comfortably above that whole measured range, not just past its lower edge. Attack/release
    // both slow on purpose - a fast-responding floor would itself sound like an audible "silence
    // kicks in" event, exactly the kind of artifact this exists to avoid, not introduce.
    private static final float SILENCE_FLOOR_TARGET_DB = -60f;
    private static final float SILENCE_FLOOR_ATTACK_SEC = 2f;
    private static final float SILENCE_FLOOR_RELEASE_SEC = 2f;

    // Fallback-only silence floor, used solely when no RNNoise VAD signal exists (Speech
    // Enhancement off). Deliberately simple/conservative - this is a fallback, not the primary
    // mechanism, per the explicit instruction not to guess a replacement for a confirmed-real
    // signal when one exists.
    private static final float ENERGY_SILENCE_FLOOR_DB = -50f;

    private final int sampleRate;
    private final float limiterAttackCoef;
    private final float limiterReleaseCoef;

    private float slowGainDb;
    private float limiterGainDb;
    private float silenceFloorGainDb;

    public AdaptiveGainProcessor(int sampleRate) {
        this.sampleRate = sampleRate;
        limiterAttackCoef = timeConstantToCoef(LIMITER_ATTACK_MS, sampleRate);
        limiterReleaseCoef = timeConstantToCoef(LIMITER_RELEASE_MS, sampleRate);
    }

    /** One-pole smoothing coefficient for a fixed per-sample step at this sample rate - same
     * formula VoiceIsolationProcessor uses, see its own doc for the derivation. */
    private static float timeConstantToCoef(float ms, int sampleRate) {
        return 1f - (float) Math.exp(-1.0 / (ms / 1000.0 * sampleRate));
    }

    /** Variable-timestep one-pole coefficient for a buffer of elapsedSec real-world duration -
     * used for the slow leveler, which updates once per buffer rather than once per sample, so a
     * fixed per-sample coefficient doesn't apply; this generalizes to whatever buffer size a
     * caller actually hands in. */
    private static float timeConstantToCoefForDuration(float seconds, float elapsedSec) {
        return 1f - (float) Math.exp(-elapsedSec / seconds);
    }

    private static float linearToDb(float linear) {
        return 20f * (float) Math.log10(Math.max(linear, 1e-6f));
    }

    private static float dbToLinear(float db) {
        return (float) Math.pow(10.0, db / 20.0);
    }

    /** Processes length bytes (length/4 float samples) of buffer in place, in [-1, 1]-normalized
     * ENCODING_PCM_FLOAT - the only format this runs on (Adaptive Gain requires float capture,
     * same requirement Speech Enhancement already has). No-op if the setting is off (callers
     * should then fall back to PixelGramSettings.applyGainFloat's fixed multiplier instead - see
     * its call sites). rnnoiseVadProbability/rnnoiseVadAvailable should reflect
     * SpeechEnhancer.getLastVadProbability() when Speech Enhancement is active for this
     * recording, or (false, ignored) when it isn't. */
    public void processFloat(ByteBuffer buffer, int length, float rnnoiseVadProbability, boolean rnnoiseVadAvailable) {
        if (!PixelGramSettings.isAdaptiveGainEnabled()) return;
        int sampleCount = length / 4;
        if (sampleCount <= 0) return;

        float peak = 0f;
        double sumSquares = 0;
        for (int i = 0; i < sampleCount; i++) {
            float s = buffer.getFloat(i * 4);
            float a = Math.abs(s);
            if (a > peak) peak = a;
            sumSquares += (double) s * s;
        }
        float rms = (float) Math.sqrt(sumSquares / sampleCount);
        float blockRmsDb = linearToDb(rms);
        float blockPeakDb = linearToDb(peak);

        boolean isSpeech = rnnoiseVadAvailable
                ? rnnoiseVadProbability >= VAD_SPEECH_THRESHOLD
                : blockRmsDb >= ENERGY_SILENCE_FLOOR_DB;

        float elapsedSec = sampleCount / (float) sampleRate;
        if (isSpeech) {
            float targetDb = PixelGramSettings.getAdaptiveGainTargetDb();
            float neededGainDb = targetDb - blockRmsDb;
            float timeConstant = neededGainDb < slowGainDb
                    ? PixelGramSettings.getAdaptiveGainSlowAttackSec()
                    : PixelGramSettings.getAdaptiveGainSlowReleaseSec();
            float coef = timeConstantToCoefForDuration(timeConstant, elapsedSec);
            slowGainDb += coef * (neededGainDb - slowGainDb);
            // Silence floor decays back toward 0 while actually speaking, same as it never
            // engages at all if the setting is off - it should only ever be adding gain during
            // real silence, not lingering audibly into the next sentence.
            float releaseCoef = timeConstantToCoefForDuration(SILENCE_FLOOR_RELEASE_SEC, elapsedSec);
            silenceFloorGainDb += releaseCoef * (0f - silenceFloorGainDb);
        } else if (PixelGramSettings.isAdaptiveGainSilenceFloorEnabled()) {
            // How much on top of slowGainDb (already frozen at whatever speech last required)
            // this buffer's own raw level needs to reach the floor target - never negative, this
            // only ever adds gain, never removes it. Attacks slowly (see the constants' doc)
            // rather than snapping straight to the needed value.
            float neededFloorDb = Math.max(0f, SILENCE_FLOOR_TARGET_DB - blockRmsDb - slowGainDb);
            float attackCoef = timeConstantToCoefForDuration(SILENCE_FLOOR_ATTACK_SEC, elapsedSec);
            silenceFloorGainDb += attackCoef * (neededFloorDb - silenceFloorGainDb);
        }
        if (slowGainDb > GAIN_MAX_DB) slowGainDb = GAIN_MAX_DB;
        else if (slowGainDb < GAIN_MIN_DB) slowGainDb = GAIN_MIN_DB;
        // Same ceiling as slowGainDb, for the same reason - without this, sufficiently deep raw
        // silence (below GAIN_MAX_DB's own 8x headroom under the target) could ask for more gain
        // than this class allows anywhere else, turning mic self-noise into audible hiss rather
        // than a subtle floor.
        if (silenceFloorGainDb < 0f) silenceFloorGainDb = 0f;
        else if (silenceFloorGainDb > GAIN_MAX_DB) silenceFloorGainDb = GAIN_MAX_DB;

        // How much extra reduction (on top of slowGainDb + silenceFloorGainDb) this buffer's own
        // peak requires to stay under the ceiling - the look-ahead part: computed from the whole
        // buffer before any of it is written back. silenceFloorGainDb included here too, even
        // though a buffer quiet enough to need the floor is never going to be anywhere near the
        // ceiling in practice - this is the same "every gain stage stays under the one limiter"
        // safety property the rest of this class already relies on, not a change of behavior for
        // the normal (floor-inactive) case.
        float projectedPeakDb = blockPeakDb + slowGainDb + silenceFloorGainDb;
        float neededLimiterDb = Math.min(0f, CEILING_DB - projectedPeakDb);

        for (int i = 0; i < sampleCount; i++) {
            float limCoef = neededLimiterDb < limiterGainDb ? limiterAttackCoef : limiterReleaseCoef;
            limiterGainDb += limCoef * (neededLimiterDb - limiterGainDb);
            if (limiterGainDb > 0f) limiterGainDb = 0f; // never boost via the limiter

            float totalGain = dbToLinear(slowGainDb + silenceFloorGainDb + limiterGainDb);
            buffer.putFloat(i * 4, buffer.getFloat(i * 4) * totalGain);
        }
        // Deliberately not clamped to [-1,1] here - same convention as every other stage in this
        // chain (SpeechEnhancer, VoiceIsolationProcessor): downstream soft limiting, if any,
        // catches transients this stage's own ceiling calculation didn't fully anticipate.
    }
}
