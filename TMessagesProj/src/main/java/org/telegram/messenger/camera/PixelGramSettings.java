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
    private static final String KEY_EXPOSURE_COMPENSATION = "exposure_compensation_ev";
    private static final String KEY_RESOLUTION = "resolution";
    private static final String KEY_VIDEO_BITRATE = "video_bitrate";
    private static final String KEY_AUDIO_BITRATE = "audio_bitrate";
    private static final String KEY_DEBUG_LOGGING = "debug_logging_enabled";
    private static final String KEY_VOICE_ENHANCEMENT = "voice_enhancement_mode";
    private static final String KEY_NOISE_SUPPRESSION = "noise_suppression_enabled";
    private static final String KEY_AGC = "agc_enabled";
    private static final String KEY_ECHO_CANCELLATION = "echo_cancellation_enabled";
    private static final String KEY_MIC_GAIN = "mic_gain_mode";
    private static final String KEY_MIC_DIRECTION_MODE = "mic_direction_mode";
    private static final String KEY_MIC_FIELD_DIMENSION = "mic_field_dimension";
    private static final String KEY_VOICE_ISOLATION_MODE = "voice_isolation_mode";
    private static final String KEY_SPEECH_ENHANCEMENT_MODE = "speech_enhancement_mode";
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
    public static final float DEFAULT_EXPOSURE_COMPENSATION = 0.0f;
    // Set from A/B testing across the full resolution range (see FINDINGS.md): resolution
    // mattered more than bitrate, and Lanczos looked clearly better than Box/Gaussian. 640 is
    // confirmed working (round, not reclassified as a normal video) on Android, iOS, and web, and
    // is a clean 3:1 downscale from the 1920 supersample capture. There's a real server-side
    // ceiling somewhere above 640 (720 is confirmed rejected) - being bracketed empirically; this
    // default should move up once that's found, since testing shows resolution is worth pushing
    // further than bitrate.
    public static final int DEFAULT_RESOLUTION = 640;
    public static final int DEFAULT_VIDEO_BITRATE = 1_000_000;
    public static final int DEFAULT_AUDIO_BITRATE = 96_000;
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
    // VOICE_ENHANCEMENT_OFF (AudioSource.DEFAULT, labeled "Off (raw mic)" in the menu) - changed
    // from CAMCORDER after a proper SNR comparison (real voice, arm's-length geometry) measured
    // DEFAULT ~5.6dB cleaner than CAMCORDER, despite the original matrix above finding CAMCORDER
    // ~4.7dB louder. Both are true: CAMCORDER's extra level is a far-talk gain boost that raises
    // noise right along with signal, not a cleanup - see FINDINGS.md's "Round video's default
    // AudioSource switched to MIC/DEFAULT" for the full reconciliation. Round video's own gain
    // stage (mic gain 1x-5x, soft limiter) makes level the cheap, recoverable part and SNR the
    // scarce one, so the cleaner-but-quieter source is the better starting point.
    public static final int DEFAULT_VOICE_ENHANCEMENT = VOICE_ENHANCEMENT_OFF;
    public static final boolean DEFAULT_NOISE_SUPPRESSION = true;
    public static final boolean DEFAULT_AGC = false;
    public static final boolean DEFAULT_ECHO_CANCELLATION = true;
    public static final int DEFAULT_MIC_GAIN = MIC_GAIN_5X;
    public static final int DEFAULT_MIC_DIRECTION_MODE = MIC_DIRECTION_OFF;
    public static final float DEFAULT_MIC_FIELD_DIMENSION = 0.5f;
    public static final int DEFAULT_VOICE_ISOLATION_MODE = VOICE_ISOLATION_BANDPASS_GATE;
    public static final float DEFAULT_GATE_THRESHOLD_DB = -45f;
    public static final int DEFAULT_SPEECH_ENHANCEMENT_MODE = SPEECH_ENHANCEMENT_OFF;
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

    public static int getAudioBitrate() {
        return prefs().getInt(KEY_AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE);
    }

    public static void setAudioBitrate(int bitrate) {
        prefs().edit().putInt(KEY_AUDIO_BITRATE, bitrate).apply();
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
        switch (getMicGainMode()) {
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

    /** Multiplies every 16-bit PCM sample in [0, lengthBytes) of buffer by the mic gain
     * setting, then passes it through the soft limiter above instead of a hard clamp. A final
     * Short.MIN/MAX_VALUE bounds check stays as a defensive backstop against rounding right at
     * the asymptote (tanh's output is strictly < 1.0 for any finite input, but rounding a value
     * close enough to it to an int16 could still land on the boundary) - it's not expected to
     * actually engage in normal operation the way the old hard clamp did. Uses absolute indexed
     * get/put so it doesn't disturb the buffer's position/limit. No-op (no per-sample cost)
     * when gain is 1x. */
    public static void applyMicGain(java.nio.ByteBuffer buffer, int lengthBytes) {
        float gain = getMicGainMultiplier();
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

    /** Same gain + soft limiter as applyMicGain(ByteBuffer, int), but reads/writes native-endian
     * 32-bit float samples already normalized to [-1, 1] (AudioFormat.ENCODING_PCM_FLOAT)
     * instead of scaled 16-bit shorts - used by the round-video capture path, which records in
     * float specifically so gain (up to 5x/+14dB) is applied to a sample that was never
     * quantized to 16-bit in the first place. lengthBytes is in bytes (lengthBytes/4 float
     * samples), matching applyMicGain's byte-count convention. No int16 round-trip happens here
     * at all - the result is written back as a float, quantized to 16-bit exactly once, at the
     * encoder hand-off. */
    public static void applyMicGainFloat(java.nio.ByteBuffer buffer, int lengthBytes) {
        float gain = getMicGainMultiplier();
        if (gain == 1.0f) return;
        for (int i = 0; i + 3 < lengthBytes; i += 4) {
            float x = softLimit(buffer.getFloat(i) * gain);
            buffer.putFloat(i, x);
        }
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
                .putFloat(KEY_EXPOSURE_COMPENSATION, DEFAULT_EXPOSURE_COMPENSATION)
                .putInt(KEY_RESOLUTION, DEFAULT_RESOLUTION)
                .putInt(KEY_VIDEO_BITRATE, DEFAULT_VIDEO_BITRATE)
                .putInt(KEY_AUDIO_BITRATE, DEFAULT_AUDIO_BITRATE)
                .putBoolean(KEY_DEBUG_LOGGING, DEFAULT_DEBUG_LOGGING)
                .putInt(KEY_VOICE_ENHANCEMENT, DEFAULT_VOICE_ENHANCEMENT)
                .putBoolean(KEY_NOISE_SUPPRESSION, DEFAULT_NOISE_SUPPRESSION)
                .putBoolean(KEY_AGC, DEFAULT_AGC)
                .putBoolean(KEY_ECHO_CANCELLATION, DEFAULT_ECHO_CANCELLATION)
                .putInt(KEY_MIC_GAIN, DEFAULT_MIC_GAIN)
                .putInt(KEY_MIC_DIRECTION_MODE, DEFAULT_MIC_DIRECTION_MODE)
                .putFloat(KEY_MIC_FIELD_DIMENSION, DEFAULT_MIC_FIELD_DIMENSION)
                .putInt(KEY_VOICE_ISOLATION_MODE, DEFAULT_VOICE_ISOLATION_MODE)
                .putFloat(KEY_GATE_THRESHOLD_DB, DEFAULT_GATE_THRESHOLD_DB)
                .putInt(KEY_SPEECH_ENHANCEMENT_MODE, DEFAULT_SPEECH_ENHANCEMENT_MODE)
                .putInt(KEY_DOWNSCALE_FILTER, DEFAULT_DOWNSCALE_FILTER)
                .putFloat(KEY_DITHER_AMOUNT_LSB, DEFAULT_DITHER_AMOUNT_LSB)
                .apply();
    }
}
