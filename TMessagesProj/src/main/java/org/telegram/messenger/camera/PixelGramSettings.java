package org.telegram.messenger.camera;

import android.content.Context;
import android.content.SharedPreferences;
import android.hardware.camera2.CameraMetadata;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.MicrophoneDirection;

import org.telegram.messenger.ApplicationLoader;

/**
 * SharedPreferences-backed store for the PixelGram camera/recording settings screen.
 * Read fresh (not cached) wherever a value is used - these are cheap in-memory lookups,
 * not CameraCharacteristics re-parsing, so there's no reason to cache them.
 */
public class PixelGramSettings {

    private static final String PREFS_NAME = "pixelgram_settings";

    public static final int NOISE_REDUCTION_OFF = CameraMetadata.NOISE_REDUCTION_MODE_OFF;
    public static final int NOISE_REDUCTION_FAST = CameraMetadata.NOISE_REDUCTION_MODE_FAST;
    public static final int NOISE_REDUCTION_HIGH_QUALITY = CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY;

    public static final int EDGE_MODE_OFF = CameraMetadata.EDGE_MODE_OFF;
    public static final int EDGE_MODE_FAST = CameraMetadata.EDGE_MODE_FAST;
    public static final int EDGE_MODE_HIGH_QUALITY = CameraMetadata.EDGE_MODE_HIGH_QUALITY;

    // No OFF option - tonemap has no OFF mode in the Camera2 API at all. See FINDINGS.md's
    // tone-mapping investigation for why this stays a choice between the device's own two
    // built-in qualities rather than a custom CONTRAST_CURVE.
    public static final int TONEMAP_MODE_FAST = CameraMetadata.TONEMAP_MODE_FAST;
    public static final int TONEMAP_MODE_HIGH_QUALITY = CameraMetadata.TONEMAP_MODE_HIGH_QUALITY;

    public static final int VOICE_ENHANCEMENT_OFF = 0;
    public static final int VOICE_ENHANCEMENT_VOICE_COMMUNICATION = 1;
    public static final int VOICE_ENHANCEMENT_VOICE_RECOGNITION = 2;
    public static final int VOICE_ENHANCEMENT_CAMCORDER = 3;
    // AudioSource.UNPROCESSED: the only source the platform documents as genuinely
    // unprocessed - no AGC, no noise suppression, no echo cancellation applied ahead of the
    // app, guaranteed rather than merely likely (unlike e.g. VOICE_RECOGNITION, which is
    // commonly implemented with flat gain but isn't specified to be). Only actually available
    // if AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED is declared - see
    // isUnprocessedAudioSourceSupported(). Best source for feeding our own DSP/ML denoising a
    // clean signal, since nothing upstream has already altered it.
    public static final int VOICE_ENHANCEMENT_UNPROCESSED = 4;

    public static final int MIC_GAIN_1X = 0;
    public static final int MIC_GAIN_1_5X = 1;
    public static final int MIC_GAIN_2X = 2;
    public static final int MIC_GAIN_3X = 3;
    public static final int MIC_GAIN_4X = 4;
    public static final int MIC_GAIN_5X = 5;

    /** Off: no processing. Bandpass: 90Hz-7kHz filter only. Bandpass + Gate: filter, then a
     * downward-expander gate. See VoiceIsolationProcessor and FINDINGS.md's design writeup for
     * why this is scoped to ordinary room noise (HVAC, traffic, handling) and not expected to
     * do much against a same-band, comparable-level interferer like music. */
    public static final int VOICE_ISOLATION_OFF = 0;
    public static final int VOICE_ISOLATION_BANDPASS = 1;
    public static final int VOICE_ISOLATION_BANDPASS_GATE = 2;

    /** RNN-based denoiser (RNNoise, vendored - see SpeechEnhancer), applied first in the audio
     * chain, before VoiceIsolationProcessor's bandpass/gate and before applyMicGain(Float). Off by
     * default until the three-way comparison (denoiser off / stacked with the existing chain /
     * denoiser-only with gate+bandpass off and 1x gain) in FINDINGS.md settles whether it should
     * replace rather than stack with the existing DSP. Only takes effect when capturing in
     * ENCODING_PCM_FLOAT - see SpeechEnhancer's class doc. */
    public static final int SPEECH_ENHANCEMENT_OFF = 0;
    public static final int SPEECH_ENHANCEMENT_RNNOISE = 1;

    /** Wet/dry blend applied after RNNoise, before Voice Isolation - see SpeechEnhancer.process().
     * 100% is pure RNNoise output; anything less mixes back a fraction of the original signal, so
     * content RNNoise fully suppresses (background music, misclassified quiet word-endings/breath)
     * reappears attenuated by a fixed, predictable amount (20*log10(1-wet) dB) instead of
     * disappearing outright - a simple fixed-ratio blend, not time-varying (see FINDINGS.md for
     * why that's the right starting lever, and the RNNoise VAD-probability-driven alternative
     * worth trying later). Defaults to 90% based on early listening (100% has audible music
     * elimination and eaten word-endings); settled on 70% after listening across the full range
     * (see FINDINGS.md) - 60/50 added for further tuning below that. */
    public static final float[] SPEECH_ENHANCEMENT_WET_VALUES = {1.0f, 0.9f, 0.8f, 0.7f, 0.6f, 0.5f};

    /** Gate threshold choices, in dBFS against the pre-gain raw signal (this runs before mic
     * gain - see VoiceIsolationProcessor) - not the post-chain levels in FINDINGS.md's audio
     * matrix, which were measured after gain. */
    public static final float[] GATE_THRESHOLD_DB_VALUES = {-50f, -45f, -40f, -35f};

    public static final int MIC_DIRECTION_OFF = 0;
    public static final int MIC_DIRECTION_TOWARDS_USER = 1;
    public static final int MIC_DIRECTION_AWAY_FROM_USER = 2;
    public static final int MIC_DIRECTION_AUTO = 3;

    /** Discrete field-dimension values offered in the settings picker, 0.0 (wide) to 1.0
     * (narrow/directional) per setPreferredMicrophoneFieldDimension's documented range. */
    public static final float[] MIC_FIELD_DIMENSION_VALUES = {0.0f, 0.25f, 0.5f, 0.75f, 1.0f};

    /** Which 9-tap kernel the two supersample GL passes use to downscale from the capture size
     * to the render target (see InstantCameraView's SUPERSAMPLE_*_FRAGMENT_SHADER variants).
     * Lanczos-2 is sharpest but has negative lobes that ring on fine high-contrast detail (beard
     * stubble, hair) - that ringing is high-frequency content the video encoder has to spend bits
     * on, and at typical round-video bitrates it can't afford to, so it degrades into blocking
     * instead. Box and Gaussian are strictly positive (no ringing), producing a softer but more
     * compressible signal - worth A/B-ing against Lanczos at a given bitrate rather than assuming
     * sharper-before-encoding means better-after-encoding. */
    public static final int DOWNSCALE_FILTER_LANCZOS = 0;
    public static final int DOWNSCALE_FILTER_BOX = 1;
    public static final int DOWNSCALE_FILTER_GAUSSIAN = 2;

    private static final String KEY_NOISE_REDUCTION = "noise_reduction_mode";
    private static final String KEY_EDGE_MODE = "edge_mode";
    private static final String KEY_TONEMAP_MODE = "tonemap_mode";
    private static final String KEY_FACE_AE_METERING = "face_ae_metering";
    private static final String KEY_LOW_LIGHT_BOOST = "low_light_boost_enabled";
    private static final String KEY_PREVIEW_STABILIZATION = "preview_stabilization_enabled";
    private static final String KEY_EXPOSURE_COMPENSATION = "exposure_compensation_ev";
    private static final String KEY_RESOLUTION = "resolution";
    private static final String KEY_VIDEO_BITRATE = "video_bitrate";
    private static final String KEY_AUDIO_BITRATE = "audio_bitrate";
    private static final String KEY_OPUS_APPLICATION = "opus_application_mode";
    private static final String KEY_OPUS_BITRATE = "opus_bitrate";
    private static final String KEY_DEBUG_LOGGING = "debug_logging_enabled";
    private static final String KEY_VOICE_ENHANCEMENT = "voice_enhancement_mode";
    private static final String KEY_NOISE_SUPPRESSION = "noise_suppression_enabled";
    private static final String KEY_AGC = "agc_enabled";
    private static final String KEY_ECHO_CANCELLATION = "echo_cancellation_enabled";
    private static final String KEY_MIC_GAIN = "mic_gain_mode";
    // Separate preference from KEY_MIC_GAIN (round video) - see DEFAULT_MIC_GAIN_VOICE_MESSAGE's
    // doc for why these need independent defaults rather than sharing one setting.
    private static final String KEY_MIC_GAIN_VOICE_MESSAGE = "mic_gain_mode_voice_message";
    // Distinct from KEY_AGC (that's the platform's android.media.audiofx.AutomaticGainControl
    // effect, a different mechanism entirely - see DEFAULT_AGC's own doc). "Adaptive Gain" is
    // this app's own look-ahead-limiter-based software AGC, replacing the fixed mic-gain
    // multiplier outright when enabled - see AdaptiveGainProcessor's class doc.
    private static final String KEY_ADAPTIVE_GAIN_ENABLED = "adaptive_gain_enabled";
    private static final String KEY_ADAPTIVE_GAIN_TARGET_DB = "adaptive_gain_target_db";
    // Slow-leveler time constants, adjustable per the 2026-09-05 pumping-vs-convergence report
    // (see FINDINGS.md): the 1.0s/4.0s defaults below don't fully converge within a typical
    // 8-10s round-video clip, but shortening them trades that away for audible envelope-following
    // ("pumping") at conversational speech's own syllable/word-gap timescale - there's no value
    // that's simply better, so this is exposed for the user to compare rather than picked for them.
    private static final String KEY_ADAPTIVE_GAIN_SLOW_ATTACK_SEC = "adaptive_gain_slow_attack_sec";
    private static final String KEY_ADAPTIVE_GAIN_SLOW_RELEASE_SEC = "adaptive_gain_slow_release_sec";
    private static final String KEY_MIC_DIRECTION_MODE = "mic_direction_mode";
    private static final String KEY_MIC_FIELD_DIMENSION = "mic_field_dimension";
    private static final String KEY_VOICE_ISOLATION_MODE = "voice_isolation_mode";
    private static final String KEY_SPEECH_ENHANCEMENT_MODE = "speech_enhancement_mode";
    private static final String KEY_SPEECH_ENHANCEMENT_WET = "speech_enhancement_wet";
    private static final String KEY_GATE_THRESHOLD_DB = "voice_isolation_gate_threshold_db";
    private static final String KEY_DOWNSCALE_FILTER = "downscale_filter_mode";
    private static final String KEY_DITHER_AMOUNT_LSB = "dither_amount_lsb";

    // NR/edge/exposure-comp/face-AE-metering defaults below were tuned back when the ISP did the
    // entire resolution reduction with no oversampling margin at all (see FINDINGS.md's original
    // tone-mapping/AE-regions investigations) - capture-stage noise reduction and edge enhancement
    // were doing real work then. Now that the round-video path captures at 1920 and Lanczos-
    // downscales to the render target, that single downscale pass both denoises (averaging many
    // source pixels per output pixel) and sharpens (Lanczos's own negative lobes) far more
    // effectively than the ISP's own real-time NR/edge processing at capture resolution, making
    // that capture-stage processing redundant - off is now the better default.
    public static final int DEFAULT_NOISE_REDUCTION = NOISE_REDUCTION_OFF;
    public static final int DEFAULT_EDGE_MODE = EDGE_MODE_OFF;
    // Fast vs High Quality showed no visible difference across two separate tests - kept as Fast
    // since it's the cheaper of two options that look the same.
    public static final int DEFAULT_TONEMAP_MODE = TONEMAP_MODE_FAST;
    public static final boolean DEFAULT_FACE_AE_METERING = false;

    /** CONTROL_AE_MODE_ON_LOW_LIGHT_BOOST_BRIGHTNESS_PRIORITY (6) - confirmed available on both
     * cameras with CONTROL_LOW_LIGHT_BOOST_INFO_LUMINANCE_RANGE [0.1, 15.0] lux. Measured (see
     * FINDINGS.md's Low Light Boost section): CONTROL_LOW_LIGHT_BOOST_STATE reports ACTIVE
     * unconditionally in a dim scene, but realized frame rate came in at 14-17fps regardless of
     * the requested CONTROL_AE_TARGET_FPS_RANGE - the HAL extends exposure past the frame budget
     * and the fixed range does not constrain it. Roughly half the normal ~30fps is visibly choppy
     * for a talking head, so this stays off by default - offered as a setting (with that tradeoff
     * stated in the UI) for whoever prefers brightness over motion smoothness. */
    public static final boolean DEFAULT_LOW_LIGHT_BOOST = false;

    /** CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION (2) - confirmed available on both
     * cameras, with SCALER_MANDATORY_PREVIEW_STABILIZATION_OUTPUT_STREAM_COMBINATIONS also
     * populated on both (genuine HAL backing, not just an advertised enum value). Basic
     * stabilization (CONTROL_VIDEO_STABILIZATION_MODE_ON, mode 1) is already unconditionally
     * applied whenever supported, independent of this setting - preview stabilization is a
     * stronger guarantee (preview and recorded video see the same stabilized/cropped stream) at
     * the cost of a real crop, on top of the crop this app's own supersample-to-circle pipeline
     * already applies. Off by default until the actual compounded crop is measured - see
     * FINDINGS.md's Preview Stabilization section. */
    public static final boolean DEFAULT_PREVIEW_STABILIZATION = false;
    public static final float DEFAULT_EXPOSURE_COMPENSATION = 0.0f;
    // Set from A/B testing across the full resolution range (see FINDINGS.md): resolution
    // mattered more than bitrate, and Lanczos looked clearly better than Box/Gaussian. 640 is
    // confirmed working (round, not reclassified as a normal video) on Android, iOS, and web, and
    // is a clean 3:1 downscale from the 1920 supersample capture. The server-side ceiling
    // bracketing concluded: 656 and everything above it (672/704/720/960) is rejected, so 640 is
    // both the practical maximum and the default - not expected to move up further.
    public static final int DEFAULT_RESOLUTION = 640;
    public static final int DEFAULT_VIDEO_BITRATE = 1_000_000;
    public static final int DEFAULT_AUDIO_BITRATE = 96_000;

    /** Telegram limits round video by file size, not resolution or duration on their own - above
     * some server-side ceiling, an upload otherwise valid as a round message is silently
     * reclassified as a normal (non-round) video instead of being rejected. There's no client-
     * side constant for this in the whole tree (grepped for it - see FINDINGS.md's "File size
     * cap" section); bisected by hand instead: a 60s/640px recording at 1Mbps video (11.12MB
     * total) was accepted as round, the same at 2Mbps (15.61MB) was reclassified. That test was
     * shot in near-darkness, which compresses well below its nominal bitrate - a bright, detailed
     * scene at the same settings lands much closer to nominal, so this budget is set well under
     * the confirmed-accepted size rather than at it, and worst-case (assumes the encoder actually
     * spends its full allotted bitrate) rather than best-case. Used by
     * capVideoBitrateForSizeBudget() below, both for the pre-recording bitrate cap and
     * InstantCameraView's live mid-recording ratchet-down fallback. */
    public static final int ROUND_VIDEO_MAX_DURATION_MS = 60_000;
    public static final long ROUND_VIDEO_SAFE_MAX_BYTES = 9_500_000L;
    private static final double ROUND_VIDEO_MUX_OVERHEAD_FRACTION = 0.03;

    /** Opus encoder application mode for voice-message recording (audio.c/initRecorder) - values
     * match libopus's own OPUS_APPLICATION_VOIP/OPUS_APPLICATION_AUDIO constants directly (2048/
     * 2049), passed straight through the JNI boundary with no translation layer. AUDIO is the
     * default: VOIP tunes for packet-loss robustness and very low bitrates aimed at real-time
     * transmission, neither of which apply to a locally-recorded-then-uploaded file with no
     * real-time constraint - AUDIO targets higher fidelity at a given bitrate instead, which is
     * what actually matters here. See FINDINGS.md's Opus encoder configuration section. */
    public static final int OPUS_APPLICATION_VOIP = 2048;
    public static final int OPUS_APPLICATION_AUDIO = 2049;
    public static final int DEFAULT_OPUS_APPLICATION = OPUS_APPLICATION_AUDIO;

    /** Explicit VBR bitrate target (bps) for the Opus voice-message encoder, replacing the
     * previous OPUS_BITRATE_MAX (i.e. "use whatever ceiling the encoder's own default picks,"
     * which for Opus at 48kHz mono/VOIP application lands far above what speech actually needs -
     * see FINDINGS.md for the measured file-size comparison). */
    public static final int[] OPUS_BITRATE_VALUES = {16000, 24000, 32000, 48000, 64000};
    public static final int DEFAULT_OPUS_BITRATE = 32000;
    public static final boolean DEFAULT_DEBUG_LOGGING = false;
    // Set from the controlled matrix measurement on the Pixel 11 Pro (see FINDINGS.md's
    // "Audio matrix measurement" and "Voice isolation measurement" sections). AGC defaults off
    // since AutomaticGainControl.isAvailable() is false on this device (see the earlier "Audio
    // effect availability" finding) - enabling it here would be a no-op, not a real choice. Mic
    // direction defaults off since the matrix measured it 0.2dB from baseline with applied:true,
    // confirming it's inert on this device. Gain moved 3x -> 4x -> 5x as headroom was confirmed
    // at each step - at 5x with the soft limiter in place, measured -25.8dB mean, -7.7dB peak,
    // no samples near full scale, so the limiter is only catching occasional transients rather
    // than compressing continuously (see "Mic gain 4x/5x + soft limiter" below).
    // VOICE_ENHANCEMENT_CAMCORDER (AudioSource.CAMCORDER) - reverted back from
    // VOICE_ENHANCEMENT_OFF (2026-09-05) after the broadband SNR comparison that justified the
    // earlier switch turned out to be measuring the wrong thing: MIC/DEFAULT's ~5.6dB broadband
    // SNR advantage is a low-frequency-dominated number (most of any voice signal's energy sits
    // below 2kHz), so it said nothing about the treble end - where CAMCORDER actually measures
    // ~8.6dB *better* than MIC/DEFAULT, and where the iPhone comparison independently showed this
    // app's output measurably deficient. See FINDINGS.md's "AudioSource reverted to CAMCORDER"
    // for the full reconciliation and the general lesson about broadband ratios hiding spectral
    // shape.
    public static final int DEFAULT_VOICE_ENHANCEMENT = VOICE_ENHANCEMENT_CAMCORDER;
    public static final boolean DEFAULT_NOISE_SUPPRESSION = true;
    public static final boolean DEFAULT_AGC = false;
    public static final boolean DEFAULT_ECHO_CANCELLATION = true;
    // Round video's own gain, measured directly: the matrix confirmed gain behaves as predicted
    // (2x -> +6.3dB, 3x -> +9.7dB against theoretical +6.0/+9.5) with the soft limiter catching
    // anything close to clipping, at 5x peaking -7.7dB with no samples near full scale. Briefly
    // moved down to 1x on the theory that RNNoise made the gain stage redundant (RNNoise's SNR
    // improvement made it look like level wasn't the remaining bottleneck) - reverted back to 3x
    // once that reasoning was checked: RNNoise removes noise, it doesn't add level, so a low-gain
    // signal denoised by RNNoise is still a low-gain signal. Gain and denoising address different
    // problems and neither substitutes for the other.
    public static final int DEFAULT_MIC_GAIN = MIC_GAIN_3X;
    // Separate from round video's own gain (DEFAULT_MIC_GAIN) - voice messages share the exact
    // same gain+limiter code but were never independently measured. 3x is carried over from round
    // video's own measured behavior (see DEFAULT_MIC_GAIN's comment) on the assumption the same
    // multiplier should behave similarly given identical code, NOT from a voice-message-specific
    // measurement - this is a compensation, not a confirmed fix. See FINDINGS.md for why the two
    // paths' actual level gap is still unexplained and worth measuring properly.
    public static final int DEFAULT_MIC_GAIN_VOICE_MESSAGE = MIC_GAIN_3X;
    // Off by default - new and unmeasured against real recordings, same "off until measured"
    // convention as this session's other new features. When on, replaces the fixed mic-gain
    // multiplier entirely (the Microphone Gain picker(s) grey out) rather than stacking with it -
    // see AdaptiveGainProcessor's class doc for the full design and why this relationship was
    // chosen over running both.
    public static final boolean DEFAULT_ADAPTIVE_GAIN = false;
    // RMS target for the slow leveler. -20dBFS is a conventional speech-leveling target
    // (comfortable headroom under the -3dBFS peak ceiling); adjustable per request.
    public static final float DEFAULT_ADAPTIVE_GAIN_TARGET_DB = -20f;
    public static final float[] ADAPTIVE_GAIN_TARGET_DB_VALUES = {-30f, -27f, -24f, -21f, -20f, -18f, -15f, -12f};
    // Slow-leveler attack/release, in seconds. Defaults retuned (2026-09-05, see FINDINGS.md's
    // "Leveler timing" report) from the original 1.0s/4.0s, which were sized for continuous audio
    // and left a typical 8-15s round-video clip only 86.5%-97.6% converged toward the target by
    // the time recording stopped - the achieved level tracked clip duration rather than settling
    // on the configured target. 1.2s release reaches 99.9% of target by 8s (essentially complete
    // for the whole 8-15s range), at the cost of a tighter margin over typical speech-pause
    // timescales than a more conservative value would give (1.2x a ~1s sentence gap, 4x a ~0.3s
    // word gap - noticeably closer than the 2.5x/4x margins a slower constant would carry), so
    // this trades more convergence certainty for less headroom against audible pumping on a long
    // natural pause. 0.4s attack (governs the "already too loud" direction, not the bottleneck)
    // tightened to match. Still adjustable - these aren't claimed to be a single provably-correct
    // point, just the chosen tradeoff.
    public static final float DEFAULT_ADAPTIVE_GAIN_SLOW_ATTACK_SEC = 0.4f;
    public static final float DEFAULT_ADAPTIVE_GAIN_SLOW_RELEASE_SEC = 1.2f;
    public static final float[] ADAPTIVE_GAIN_SLOW_ATTACK_SEC_VALUES = {0.2f, 0.4f, 0.5f, 1.0f, 2.0f};
    public static final float[] ADAPTIVE_GAIN_SLOW_RELEASE_SEC_VALUES = {0.5f, 1.0f, 1.2f, 2.0f, 4.0f, 8.0f};
    public static final int DEFAULT_MIC_DIRECTION_MODE = MIC_DIRECTION_OFF;
    public static final float DEFAULT_MIC_FIELD_DIMENSION = 0.5f;
    // Off as of 1.0.2: the three-way comparison in FINDINGS.md found the bandpass/gate chain adds
    // no measurable benefit once RNNoise (DEFAULT_SPEECH_ENHANCEMENT_MODE) is active - redundant
    // rather than complementary, so it's off by default now that RNNoise is on by default.
    public static final int DEFAULT_VOICE_ISOLATION_MODE = VOICE_ISOLATION_OFF;
    public static final float DEFAULT_GATE_THRESHOLD_DB = -45f;
    // RNNoise on by default as of 1.0.2, at DEFAULT_SPEECH_ENHANCEMENT_WET - see FINDINGS.md's
    // speech enhancement section for the splice-bug fix, the three-way comparison, and the A/B
    // process that settled the wet/dry blend.
    public static final int DEFAULT_SPEECH_ENHANCEMENT_MODE = SPEECH_ENHANCEMENT_RNNOISE;
    // 90% was the initial guess before listening; 70% was the first settled value after A/B'ing
    // the full range (100%/90%/80% all had audible word-ending clipping and full background-
    // music elimination to varying degrees); further listening moved this down to 60%, then back
    // up to 80% once 70%-and-below was found to produce an audible stereo-like artefact -
    // presumably a phase mismatch between the denoised and original signals becoming perceptible
    // as more of the raw (undenoised) signal is blended back in. See FINDINGS.md's speech
    // enhancement section.
    public static final float DEFAULT_SPEECH_ENHANCEMENT_WET = 0.8f;
    public static final int DEFAULT_DOWNSCALE_FILTER = DOWNSCALE_FILTER_LANCZOS;
    // In multiples of 1/255 (one 8-bit LSB), applied as +-0.5x this value. 0 = off. Matches what
    // shipped without a setting (1x = +-0.5 LSB) as the default so existing recordings don't
    // change until this is explicitly adjusted.
    public static final float[] DITHER_AMOUNT_LSB_VALUES = {0f, 0.5f, 1f, 2f};
    public static final float DEFAULT_DITHER_AMOUNT_LSB = 1f;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static int getNoiseReductionMode() {
        return prefs().getInt(KEY_NOISE_REDUCTION, DEFAULT_NOISE_REDUCTION);
    }

    public static void setNoiseReductionMode(int mode) {
        prefs().edit().putInt(KEY_NOISE_REDUCTION, mode).apply();
    }

    public static int getEdgeMode() {
        return prefs().getInt(KEY_EDGE_MODE, DEFAULT_EDGE_MODE);
    }

    public static void setEdgeMode(int mode) {
        prefs().edit().putInt(KEY_EDGE_MODE, mode).apply();
    }

    public static int getTonemapMode() {
        return prefs().getInt(KEY_TONEMAP_MODE, DEFAULT_TONEMAP_MODE);
    }

    public static void setTonemapMode(int mode) {
        prefs().edit().putInt(KEY_TONEMAP_MODE, mode).apply();
    }

    public static boolean isFaceAeMeteringEnabled() {
        return prefs().getBoolean(KEY_FACE_AE_METERING, DEFAULT_FACE_AE_METERING);
    }

    public static void setFaceAeMeteringEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_FACE_AE_METERING, enabled).apply();
    }

    public static boolean isLowLightBoostEnabled() {
        return prefs().getBoolean(KEY_LOW_LIGHT_BOOST, DEFAULT_LOW_LIGHT_BOOST);
    }

    public static void setLowLightBoostEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_LOW_LIGHT_BOOST, enabled).apply();
    }

    public static boolean isPreviewStabilizationEnabled() {
        return prefs().getBoolean(KEY_PREVIEW_STABILIZATION, DEFAULT_PREVIEW_STABILIZATION);
    }

    public static void setPreviewStabilizationEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_PREVIEW_STABILIZATION, enabled).apply();
    }

    public static float getExposureCompensationEv() {
        return prefs().getFloat(KEY_EXPOSURE_COMPENSATION, DEFAULT_EXPOSURE_COMPENSATION);
    }

    public static void setExposureCompensationEv(float ev) {
        prefs().edit().putFloat(KEY_EXPOSURE_COMPENSATION, ev).apply();
    }

    public static int getResolution() {
        return prefs().getInt(KEY_RESOLUTION, DEFAULT_RESOLUTION);
    }

    public static void setResolution(int resolution) {
        prefs().edit().putInt(KEY_RESOLUTION, resolution).apply();
    }

    public static int getVideoBitrate() {
        return prefs().getInt(KEY_VIDEO_BITRATE, DEFAULT_VIDEO_BITRATE);
    }

    public static void setVideoBitrate(int bitrate) {
        prefs().edit().putInt(KEY_VIDEO_BITRATE, bitrate).apply();
    }

    /** Clamps videoBitrateBps down (never up) so that a full ROUND_VIDEO_MAX_DURATION_MS
     * recording at this video bitrate, plus audioBitrateBps - both tracks budgeted together out
     * of the same ceiling, not audio "on top" of it - projects to no more than
     * ROUND_VIDEO_SAFE_MAX_BYTES net of estimated mux container overhead. Returns
     * videoBitrateBps unchanged if it already fits. See ROUND_VIDEO_SAFE_MAX_BYTES's own comment
     * for where the ceiling number comes from. */
    public static int capVideoBitrateForSizeBudget(int videoBitrateBps, int audioBitrateBps, int durationMs) {
        double payloadBudgetBytes = ROUND_VIDEO_SAFE_MAX_BYTES * (1.0 - ROUND_VIDEO_MUX_OVERHEAD_FRACTION);
        double durationSec = durationMs / 1000.0;
        double totalBudgetBps = (payloadBudgetBytes * 8.0) / durationSec;
        double maxVideoBps = totalBudgetBps - audioBitrateBps;
        if (maxVideoBps < 100_000) {
            // Audio alone already eats nearly the whole budget at this duration - clamp to a
            // floor rather than return something unusably low or negative.
            maxVideoBps = 100_000;
        }
        return videoBitrateBps > maxVideoBps ? (int) maxVideoBps : videoBitrateBps;
    }

    public static int getAudioBitrate() {
        return prefs().getInt(KEY_AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE);
    }

    public static void setAudioBitrate(int bitrate) {
        prefs().edit().putInt(KEY_AUDIO_BITRATE, bitrate).apply();
    }

    public static int getOpusApplicationMode() {
        return prefs().getInt(KEY_OPUS_APPLICATION, DEFAULT_OPUS_APPLICATION);
    }

    public static void setOpusApplicationMode(int mode) {
        prefs().edit().putInt(KEY_OPUS_APPLICATION, mode).apply();
    }

    public static int getOpusBitrate() {
        return prefs().getInt(KEY_OPUS_BITRATE, DEFAULT_OPUS_BITRATE);
    }

    public static void setOpusBitrate(int bitrate) {
        prefs().edit().putInt(KEY_OPUS_BITRATE, bitrate).apply();
    }

    public static boolean isDebugLoggingEnabled() {
        return prefs().getBoolean(KEY_DEBUG_LOGGING, DEFAULT_DEBUG_LOGGING);
    }

    public static void setDebugLoggingEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_DEBUG_LOGGING, enabled).apply();
    }

    public static int getVoiceEnhancementMode() {
        return prefs().getInt(KEY_VOICE_ENHANCEMENT, DEFAULT_VOICE_ENHANCEMENT);
    }

    public static void setVoiceEnhancementMode(int mode) {
        prefs().edit().putInt(KEY_VOICE_ENHANCEMENT, mode).apply();
    }

    /** Maps the voice enhancement mode to the actual MediaRecorder.AudioSource int to record with. */
    public static int getVoiceEnhancementAudioSource() {
        switch (getVoiceEnhancementMode()) {
            case VOICE_ENHANCEMENT_VOICE_COMMUNICATION:
                return MediaRecorder.AudioSource.VOICE_COMMUNICATION;
            case VOICE_ENHANCEMENT_VOICE_RECOGNITION:
                return MediaRecorder.AudioSource.VOICE_RECOGNITION;
            case VOICE_ENHANCEMENT_CAMCORDER:
                return MediaRecorder.AudioSource.CAMCORDER;
            case VOICE_ENHANCEMENT_UNPROCESSED:
                return MediaRecorder.AudioSource.UNPROCESSED;
            case VOICE_ENHANCEMENT_OFF:
            default:
                return MediaRecorder.AudioSource.DEFAULT;
        }
    }

    /** Whether AudioSource.UNPROCESSED is genuinely available on this device - per platform
     * docs, PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED is the defined way to check ahead of
     * construction, distinct from just trying to construct an AudioRecord with it and seeing
     * if it throws/returns uninitialized (both confirmed to agree on this device - see
     * FINDINGS.md's audio input-capability investigation). */
    public static boolean isUnprocessedAudioSourceSupported() {
        try {
            android.media.AudioManager am = (android.media.AudioManager) ApplicationLoader.applicationContext.getSystemService(Context.AUDIO_SERVICE);
            return am != null && "true".equals(am.getProperty(android.media.AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED));
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean isNoiseSuppressionEnabled() {
        return prefs().getBoolean(KEY_NOISE_SUPPRESSION, DEFAULT_NOISE_SUPPRESSION);
    }

    public static void setNoiseSuppressionEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_NOISE_SUPPRESSION, enabled).apply();
    }

    public static boolean isAgcEnabled() {
        return prefs().getBoolean(KEY_AGC, DEFAULT_AGC);
    }

    public static void setAgcEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_AGC, enabled).apply();
    }

    public static boolean isEchoCancellationEnabled() {
        return prefs().getBoolean(KEY_ECHO_CANCELLATION, DEFAULT_ECHO_CANCELLATION);
    }

    public static void setEchoCancellationEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ECHO_CANCELLATION, enabled).apply();
    }

    public static int getMicGainMode() {
        return prefs().getInt(KEY_MIC_GAIN, DEFAULT_MIC_GAIN);
    }

    public static void setMicGainMode(int mode) {
        prefs().edit().putInt(KEY_MIC_GAIN, mode).apply();
    }

    public static float getMicGainMultiplier() {
        return micGainMultiplierForMode(getMicGainMode());
    }

    /** Separate gain setting for voice messages (see KEY_MIC_GAIN_VOICE_MESSAGE's doc) - shares
     * the round-video setting's MIC_GAIN_* mode constants and the same underlying gain+limiter
     * code, just with its own default and its own stored preference. */
    public static int getMicGainModeVoiceMessage() {
        return prefs().getInt(KEY_MIC_GAIN_VOICE_MESSAGE, DEFAULT_MIC_GAIN_VOICE_MESSAGE);
    }

    public static void setMicGainModeVoiceMessage(int mode) {
        prefs().edit().putInt(KEY_MIC_GAIN_VOICE_MESSAGE, mode).apply();
    }

    public static float getMicGainMultiplierVoiceMessage() {
        return micGainMultiplierForMode(getMicGainModeVoiceMessage());
    }

    public static boolean isAdaptiveGainEnabled() {
        return prefs().getBoolean(KEY_ADAPTIVE_GAIN_ENABLED, DEFAULT_ADAPTIVE_GAIN);
    }

    public static void setAdaptiveGainEnabled(boolean enabled) {
        prefs().edit().putBoolean(KEY_ADAPTIVE_GAIN_ENABLED, enabled).apply();
    }

    public static float getAdaptiveGainTargetDb() {
        return prefs().getFloat(KEY_ADAPTIVE_GAIN_TARGET_DB, DEFAULT_ADAPTIVE_GAIN_TARGET_DB);
    }

    public static void setAdaptiveGainTargetDb(float db) {
        prefs().edit().putFloat(KEY_ADAPTIVE_GAIN_TARGET_DB, db).apply();
    }

    public static float getAdaptiveGainSlowAttackSec() {
        return prefs().getFloat(KEY_ADAPTIVE_GAIN_SLOW_ATTACK_SEC, DEFAULT_ADAPTIVE_GAIN_SLOW_ATTACK_SEC);
    }

    public static void setAdaptiveGainSlowAttackSec(float seconds) {
        prefs().edit().putFloat(KEY_ADAPTIVE_GAIN_SLOW_ATTACK_SEC, seconds).apply();
    }

    public static float getAdaptiveGainSlowReleaseSec() {
        return prefs().getFloat(KEY_ADAPTIVE_GAIN_SLOW_RELEASE_SEC, DEFAULT_ADAPTIVE_GAIN_SLOW_RELEASE_SEC);
    }

    public static void setAdaptiveGainSlowReleaseSec(float seconds) {
        prefs().edit().putFloat(KEY_ADAPTIVE_GAIN_SLOW_RELEASE_SEC, seconds).apply();
    }

    private static float micGainMultiplierForMode(int mode) {
        switch (mode) {
            case MIC_GAIN_1_5X:
                return 1.5f;
            case MIC_GAIN_2X:
                return 2.0f;
            case MIC_GAIN_3X:
                return 3.0f;
            case MIC_GAIN_4X:
                return 4.0f;
            case MIC_GAIN_5X:
                return 5.0f;
            case MIC_GAIN_1X:
            default:
                return 1.0f;
        }
    }

    // Soft limiter applied after mic gain, replacing the previous hard Short.MIN/MAX_VALUE
    // clamp (a true brick-wall clip: any sample over range was truncated exactly at the
    // boundary, a sharp discontinuity in the transfer function that adds harmonic distortion on
    // loud transients). Below this threshold, signal passes through unchanged; above it, excess
    // is compressed through tanh() so the output asymptotically approaches but never reaches
    // +-1.0 (0dBFS) - peaks round off smoothly instead of clipping abruptly. Headroom for this
    // (and for the higher gain options above) comes from the matrix measurement: the loudest
    // configuration tested peaked at -6.3dBFS, and bandpass+gate peaks around -15.6dBFS - see
    // FINDINGS.md.
    private static final float LIMITER_THRESHOLD_DB = -3f;
    private static final float LIMITER_THRESHOLD_LINEAR = (float) Math.pow(10.0, LIMITER_THRESHOLD_DB / 20.0);
    private static final float LIMITER_HEADROOM = 1f - LIMITER_THRESHOLD_LINEAR;

    /** Soft-knee limiter on a normalized [-1,1] sample - see the field comments above for the
     * curve shape and why it replaced the old hard clamp. */
    private static float softLimit(float x) {
        float mag = Math.abs(x);
        if (mag <= LIMITER_THRESHOLD_LINEAR) {
            return x;
        }
        float sign = Math.signum(x);
        float excess = mag - LIMITER_THRESHOLD_LINEAR;
        float compressed = LIMITER_THRESHOLD_LINEAR + LIMITER_HEADROOM * (float) Math.tanh(excess / LIMITER_HEADROOM);
        return sign * compressed;
    }

    /** Multiplies every 16-bit PCM sample in [0, lengthBytes) of buffer by gain, then passes it
     * through the soft limiter above instead of a hard clamp. A final Short.MIN/MAX_VALUE bounds
     * check stays as a defensive backstop against rounding right at the asymptote (tanh's output
     * is strictly < 1.0 for any finite input, but rounding a value close enough to it to an int16
     * could still land on the boundary) - it's not expected to actually engage in normal
     * operation the way the old hard clamp did. Uses absolute indexed get/put so it doesn't
     * disturb the buffer's position/limit. No-op (no per-sample cost) when gain is 1x. Shared by
     * the round-video and voice-message gain settings below - see their own doc comments for why
     * those are separate settings rather than one shared value. */
    private static void applyGain(java.nio.ByteBuffer buffer, int lengthBytes, float gain) {
        if (gain == 1.0f) return;
        for (int i = 0; i + 1 < lengthBytes; i += 2) {
            float x = softLimit((buffer.getShort(i) / 32768f) * gain);
            int outSample = Math.round(x * 32768f);
            if (outSample > Short.MAX_VALUE) {
                outSample = Short.MAX_VALUE;
            } else if (outSample < Short.MIN_VALUE) {
                outSample = Short.MIN_VALUE;
            }
            buffer.putShort(i, (short) outSample);
        }
    }

    /** Same gain + soft limiter as applyGain(ByteBuffer, int, float), but reads/writes
     * native-endian 32-bit float samples already normalized to [-1, 1]
     * (AudioFormat.ENCODING_PCM_FLOAT) instead of scaled 16-bit shorts - used by both recording
     * paths, which capture in float specifically so gain is applied to a sample that was never
     * quantized to 16-bit in the first place. lengthBytes is in bytes (lengthBytes/4 float
     * samples), matching applyGain's byte-count convention. No int16 round-trip happens here at
     * all - the result is written back as a float, quantized to 16-bit exactly once, at the
     * encoder hand-off. */
    private static void applyGainFloat(java.nio.ByteBuffer buffer, int lengthBytes, float gain) {
        if (gain == 1.0f) return;
        for (int i = 0; i + 3 < lengthBytes; i += 4) {
            float x = softLimit(buffer.getFloat(i) * gain);
            buffer.putFloat(i, x);
        }
    }

    public static void applyMicGain(java.nio.ByteBuffer buffer, int lengthBytes) {
        applyGain(buffer, lengthBytes, getMicGainMultiplier());
    }

    public static void applyMicGainFloat(java.nio.ByteBuffer buffer, int lengthBytes) {
        applyGainFloat(buffer, lengthBytes, getMicGainMultiplier());
    }

    /** Voice-message equivalents of applyMicGain/applyMicGainFloat, using the separate
     * getMicGainMultiplierVoiceMessage() setting - see KEY_MIC_GAIN_VOICE_MESSAGE's doc. */
    public static void applyMicGainVoiceMessage(java.nio.ByteBuffer buffer, int lengthBytes) {
        applyGain(buffer, lengthBytes, getMicGainMultiplierVoiceMessage());
    }

    public static void applyMicGainFloatVoiceMessage(java.nio.ByteBuffer buffer, int lengthBytes) {
        applyGainFloat(buffer, lengthBytes, getMicGainMultiplierVoiceMessage());
    }

    public static int getMicDirectionMode() {
        return prefs().getInt(KEY_MIC_DIRECTION_MODE, DEFAULT_MIC_DIRECTION_MODE);
    }

    public static void setMicDirectionMode(int mode) {
        prefs().edit().putInt(KEY_MIC_DIRECTION_MODE, mode).apply();
    }

    /** Resolves the configured mode to the android.media.MicrophoneDirection constant to
     * actually request, or null if the setter should not be called at all (Off). Auto follows
     * the active camera - towards the user for front, away for rear - since that's the
     * direction the subject is actually in. Explicit Towards user/Away from user selections are
     * honored as-is regardless of which camera is active; frontCameraActive only matters for
     * Auto. Voice messages have no camera, so callers there should pass true - Auto then
     * resolves to towards-user, matching the fact that a voice message is always spoken into
     * the mic from the front. */
    public static Integer resolveMicDirection(boolean frontCameraActive) {
        switch (getMicDirectionMode()) {
            case MIC_DIRECTION_TOWARDS_USER:
                return MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER;
            case MIC_DIRECTION_AWAY_FROM_USER:
                return MicrophoneDirection.MIC_DIRECTION_AWAY_FROM_USER;
            case MIC_DIRECTION_AUTO:
                return frontCameraActive ? MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER : MicrophoneDirection.MIC_DIRECTION_AWAY_FROM_USER;
            case MIC_DIRECTION_OFF:
            default:
                return null;
        }
    }

    public static float getMicFieldDimension() {
        return prefs().getFloat(KEY_MIC_FIELD_DIMENSION, DEFAULT_MIC_FIELD_DIMENSION);
    }

    public static void setMicFieldDimension(float value) {
        prefs().edit().putFloat(KEY_MIC_FIELD_DIMENSION, value).apply();
    }

    public static int getVoiceIsolationMode() {
        return prefs().getInt(KEY_VOICE_ISOLATION_MODE, DEFAULT_VOICE_ISOLATION_MODE);
    }

    public static void setVoiceIsolationMode(int mode) {
        prefs().edit().putInt(KEY_VOICE_ISOLATION_MODE, mode).apply();
    }

    public static int getSpeechEnhancementMode() {
        return prefs().getInt(KEY_SPEECH_ENHANCEMENT_MODE, DEFAULT_SPEECH_ENHANCEMENT_MODE);
    }

    public static void setSpeechEnhancementMode(int mode) {
        prefs().edit().putInt(KEY_SPEECH_ENHANCEMENT_MODE, mode).apply();
    }

    public static float getSpeechEnhancementWetFraction() {
        return prefs().getFloat(KEY_SPEECH_ENHANCEMENT_WET, DEFAULT_SPEECH_ENHANCEMENT_WET);
    }

    public static void setSpeechEnhancementWetFraction(float wet) {
        prefs().edit().putFloat(KEY_SPEECH_ENHANCEMENT_WET, wet).apply();
    }

    public static float getVoiceIsolationGateThresholdDb() {
        return prefs().getFloat(KEY_GATE_THRESHOLD_DB, DEFAULT_GATE_THRESHOLD_DB);
    }

    public static void setVoiceIsolationGateThresholdDb(float db) {
        prefs().edit().putFloat(KEY_GATE_THRESHOLD_DB, db).apply();
    }

    public static int getDownscaleFilter() {
        return prefs().getInt(KEY_DOWNSCALE_FILTER, DEFAULT_DOWNSCALE_FILTER);
    }

    public static void setDownscaleFilter(int filter) {
        prefs().edit().putInt(KEY_DOWNSCALE_FILTER, filter).apply();
    }

    public static float getDitherAmountLsb() {
        return prefs().getFloat(KEY_DITHER_AMOUNT_LSB, DEFAULT_DITHER_AMOUNT_LSB);
    }

    public static void setDitherAmountLsb(float lsb) {
        prefs().edit().putFloat(KEY_DITHER_AMOUNT_LSB, lsb).apply();
    }

    private static Boolean micDirectionSupportedCache;
    private static Boolean micFieldDimensionSupportedCache;

    /** setPreferredMicrophoneDirection/FieldDimension have no static isAvailable()-style
     * capability check the way NoiseSuppressor/AutomaticGainControl/AcousticEchoCanceler do -
     * success is only observable by actually calling the setter on a real AudioRecord. Probes
     * both once with a throwaway AudioRecord and caches the result for the process lifetime
     * (repeating this on every settings-row bind would mean constructing an AudioRecord on
     * every RecyclerView scroll). Defaults to unsupported (false) rather than throwing if
     * RECORD_AUDIO isn't granted yet or construction otherwise fails - callers grey the row out
     * rather than crash. */
    private static synchronized void ensureMicPreferenceProbe() {
        if (micDirectionSupportedCache != null && micFieldDimensionSupportedCache != null) {
            return;
        }
        AudioRecord probe = null;
        try {
            int sampleRate = 48000;
            int minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) {
                minBuf = 3584;
            }
            probe = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
            micDirectionSupportedCache = probe.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER);
            micFieldDimensionSupportedCache = probe.setPreferredMicrophoneFieldDimension(DEFAULT_MIC_FIELD_DIMENSION);
        } catch (Exception e) {
            micDirectionSupportedCache = false;
            micFieldDimensionSupportedCache = false;
        } finally {
            if (probe != null) {
                probe.release();
            }
        }
    }

    public static boolean isMicDirectionSupported() {
        ensureMicPreferenceProbe();
        return micDirectionSupportedCache;
    }

    public static boolean isMicFieldDimensionSupported() {
        ensureMicPreferenceProbe();
        return micFieldDimensionSupportedCache;
    }

    private static final String KEY_LAST_UPDATE_CHECK_MS = "last_update_check_ms";
    private static final String KEY_LAST_SEEN_VERSION = "last_seen_version";

    // Update-check history, not a user preference - deliberately left out of
    // resetToDefaults() so a settings reset doesn't also reset the 30-day check window.
    public static long getLastUpdateCheckMs() {
        return prefs().getLong(KEY_LAST_UPDATE_CHECK_MS, 0L);
    }

    public static void setLastUpdateCheckMs(long ms) {
        prefs().edit().putLong(KEY_LAST_UPDATE_CHECK_MS, ms).apply();
    }

    public static String getLastSeenVersion() {
        return prefs().getString(KEY_LAST_SEEN_VERSION, "");
    }

    public static void setLastSeenVersion(String version) {
        prefs().edit().putString(KEY_LAST_SEEN_VERSION, version).apply();
    }

    public static void resetToDefaults() {
        prefs().edit()
                .putInt(KEY_NOISE_REDUCTION, DEFAULT_NOISE_REDUCTION)
                .putInt(KEY_EDGE_MODE, DEFAULT_EDGE_MODE)
                .putInt(KEY_TONEMAP_MODE, DEFAULT_TONEMAP_MODE)
                .putBoolean(KEY_FACE_AE_METERING, DEFAULT_FACE_AE_METERING)
                .putBoolean(KEY_LOW_LIGHT_BOOST, DEFAULT_LOW_LIGHT_BOOST)
                .putBoolean(KEY_PREVIEW_STABILIZATION, DEFAULT_PREVIEW_STABILIZATION)
                .putFloat(KEY_EXPOSURE_COMPENSATION, DEFAULT_EXPOSURE_COMPENSATION)
                .putInt(KEY_RESOLUTION, DEFAULT_RESOLUTION)
                .putInt(KEY_VIDEO_BITRATE, DEFAULT_VIDEO_BITRATE)
                .putInt(KEY_AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE)
                .putInt(KEY_OPUS_APPLICATION, DEFAULT_OPUS_APPLICATION)
                .putInt(KEY_OPUS_BITRATE, DEFAULT_OPUS_BITRATE)
                .putBoolean(KEY_DEBUG_LOGGING, DEFAULT_DEBUG_LOGGING)
                .putInt(KEY_VOICE_ENHANCEMENT, DEFAULT_VOICE_ENHANCEMENT)
                .putBoolean(KEY_NOISE_SUPPRESSION, DEFAULT_NOISE_SUPPRESSION)
                .putBoolean(KEY_AGC, DEFAULT_AGC)
                .putBoolean(KEY_ECHO_CANCELLATION, DEFAULT_ECHO_CANCELLATION)
                .putInt(KEY_MIC_GAIN, DEFAULT_MIC_GAIN)
                .putInt(KEY_MIC_GAIN_VOICE_MESSAGE, DEFAULT_MIC_GAIN_VOICE_MESSAGE)
                .putBoolean(KEY_ADAPTIVE_GAIN_ENABLED, DEFAULT_ADAPTIVE_GAIN)
                .putFloat(KEY_ADAPTIVE_GAIN_TARGET_DB, DEFAULT_ADAPTIVE_GAIN_TARGET_DB)
                .putFloat(KEY_ADAPTIVE_GAIN_SLOW_ATTACK_SEC, DEFAULT_ADAPTIVE_GAIN_SLOW_ATTACK_SEC)
                .putFloat(KEY_ADAPTIVE_GAIN_SLOW_RELEASE_SEC, DEFAULT_ADAPTIVE_GAIN_SLOW_RELEASE_SEC)
                .putInt(KEY_MIC_DIRECTION_MODE, DEFAULT_MIC_DIRECTION_MODE)
                .putFloat(KEY_MIC_FIELD_DIMENSION, DEFAULT_MIC_FIELD_DIMENSION)
                .putInt(KEY_VOICE_ISOLATION_MODE, DEFAULT_VOICE_ISOLATION_MODE)
                .putFloat(KEY_GATE_THRESHOLD_DB, DEFAULT_GATE_THRESHOLD_DB)
                .putInt(KEY_SPEECH_ENHANCEMENT_MODE, DEFAULT_SPEECH_ENHANCEMENT_MODE)
                .putFloat(KEY_SPEECH_ENHANCEMENT_WET, DEFAULT_SPEECH_ENHANCEMENT_WET)
                .putInt(KEY_DOWNSCALE_FILTER, DEFAULT_DOWNSCALE_FILTER)
                .putFloat(KEY_DITHER_AMOUNT_LSB, DEFAULT_DITHER_AMOUNT_LSB)
                .apply();
    }
}
