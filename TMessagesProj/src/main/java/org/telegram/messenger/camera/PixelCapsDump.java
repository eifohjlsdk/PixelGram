package org.telegram.messenger.camera;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaRecorder;
import android.media.MicrophoneDirection;
import android.os.Build;
import android.os.Environment;
import android.util.Log;
import android.util.Range;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.Arrays;
import java.util.List;

/**
 * One-off diagnostic. Dumps the camera, codec and audio capability surface for this
 * device: every CameraCharacteristics key (plus a few named ones explicitly) and
 * CameraExtensionCharacteristics for camera ids "0" and "1"; MediaCodecList encoder
 * capabilities for video/avc, video/hevc and audio/mp4a-latm; and whether
 * AudioRecord.setPreferredMicrophoneDirection/setPreferredMicrophoneFieldDimension
 * report success on a throwaway AudioRecord.
 *
 * Purely a reader: it only calls getters/capability queries, opens no camera, and
 * starts no encoder or capture. It does not touch any state used by the app's real
 * camera/codec/audio paths. Triggered only when explicitly requested (see
 * LaunchActivity's "pixelcaps_dump" intent-extra hook) - never runs as a side effect
 * of normal app usage.
 *
 * Logs every line under tag "PixelCaps" and additionally writes the same content to
 * <Downloads>/PixelCaps/pixelcaps_dump.txt (same public-Downloads approach the app
 * already uses for saved media - see MediaController's download paths - so it's
 * reachable with a plain `adb pull`).
 */
public class PixelCapsDump {

    private static final String TAG = "PixelCaps";

    public static void run(Context context) {
        StringBuilder sb = new StringBuilder();
        line(sb, "=== PixelCaps dump: " + Build.MANUFACTURER + " " + Build.MODEL
                + " (" + Build.DEVICE + "), API " + Build.VERSION.SDK_INT
                + " (" + Build.VERSION.RELEASE + ") ===");

        for (String cameraId : new String[]{"0", "1"}) {
            try {
                dumpCamera(context, cameraId, sb);
            } catch (Exception e) {
                line(sb, "camera " + cameraId + ": dump failed - " + e);
            }
        }

        try {
            dumpCodecs(sb);
        } catch (Exception e) {
            line(sb, "codec dump failed - " + e);
        }

        try {
            dumpAudioMicPreferences(sb);
        } catch (Exception e) {
            line(sb, "audio mic-preference dump failed - " + e);
        }

        try {
            dumpAudioInputCapabilities(context, sb);
        } catch (Exception e) {
            line(sb, "audio input-capability dump failed - " + e);
        }

        line(sb, "=== end PixelCaps dump ===");
        writeToFile(sb.toString());
    }

    private static void line(StringBuilder sb, String s) {
        Log.d(TAG, s);
        sb.append(s).append('\n');
    }

    // ---------------------------------------------------------------- camera

    private static void dumpCamera(Context context, String cameraId, StringBuilder sb) throws CameraAccessException {
        CameraManager cm = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        CameraCharacteristics c = cm.getCameraCharacteristics(cameraId);

        line(sb, "--- camera " + cameraId + ": all keys (" + c.getKeys().size() + ") ---");
        for (CameraCharacteristics.Key<?> key : c.getKeys()) {
            String value;
            try {
                value = describe(c.get(key));
            } catch (Exception e) {
                value = "<get() failed: " + e + ">";
            }
            line(sb, "camera" + cameraId + " " + key.getName() + " = " + value);
        }

        line(sb, "--- camera " + cameraId + ": explicitly requested keys ---");
        // There is no separate CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_MAX_SIZES /
        // ..._ZOOM_RATIO_RANGES key in this SDK (compileSdk 35) - that data is carried per-mode
        // inside CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES, each entry exposing
        // getMode()/getMaxStreamingSize()/getZoomRatioRange(). Dumping that instead.
        android.hardware.camera2.params.Capability[] extendedSceneModeCaps = c.get(CameraCharacteristics.CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES);
        if (extendedSceneModeCaps != null) {
            for (android.hardware.camera2.params.Capability cap : extendedSceneModeCaps) {
                line(sb, "camera" + cameraId + " [explicit] CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES: mode=" + cap.getMode()
                        + " maxStreamingSize(~MAX_SIZES)=" + cap.getMaxStreamingSize()
                        + " zoomRatioRange(~ZOOM_RATIO_RANGES)=" + cap.getZoomRatioRange());
            }
        } else {
            line(sb, "camera" + cameraId + " [explicit] CONTROL_AVAILABLE_EXTENDED_SCENE_MODE_CAPABILITIES = null");
        }
        logExplicit(sb, cameraId, "TONEMAP_AVAILABLE_TONE_MAP_MODES", c, CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
        logExplicit(sb, cameraId, "TONEMAP_MAX_CURVE_POINTS", c, CameraCharacteristics.TONEMAP_MAX_CURVE_POINTS);
        logExplicit(sb, cameraId, "REQUEST_AVAILABLE_CAPABILITIES", c, CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        logExplicit(sb, cameraId, "CONTROL_AE_AVAILABLE_MODES", c, CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES);
        logExplicit(sb, cameraId, "CONTROL_AWB_AVAILABLE_MODES", c, CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES);
        logExplicit(sb, cameraId, "CONTROL_AVAILABLE_SCENE_MODES", c, CameraCharacteristics.CONTROL_AVAILABLE_SCENE_MODES);
        logExplicit(sb, cameraId, "SENSOR_INFO_EXPOSURE_TIME_RANGE", c, CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
        logExplicit(sb, cameraId, "SENSOR_INFO_SENSITIVITY_RANGE", c, CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);
        logExplicit(sb, cameraId, "COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES", c, CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES);
        logExplicit(sb, cameraId, "SHADING_AVAILABLE_MODES", c, CameraCharacteristics.SHADING_AVAILABLE_MODES);
        logExplicit(sb, cameraId, "INFO_SUPPORTED_HARDWARE_LEVEL", c, CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);

        // SCALER_STREAM_CONFIGURATION_MAP output sizes for the formats round video actually
        // uses: the camera->GL->encoder path targets a SurfaceTexture (opaque/implementation
        // -defined format), not a raw YUV/JPEG ImageReader - see Camera2Session.open(SurfaceTexture).
        // JPEG is included too since Camera2Session also opens a JPEG ImageReader for stills.
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map != null) {
            android.util.Size[] surfaceTextureSizes = map.getOutputSizes(SurfaceTexture.class);
            line(sb, "camera" + cameraId + " SCALER_STREAM_CONFIGURATION_MAP outputSizes(SurfaceTexture.class) [round-video capture/encoder surface] = " + Arrays.toString(surfaceTextureSizes));
            android.util.Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
            line(sb, "camera" + cameraId + " SCALER_STREAM_CONFIGURATION_MAP outputSizes(JPEG) [stills ImageReader] = " + Arrays.toString(jpegSizes));
            android.util.Size[] privateSizes = map.getOutputSizes(ImageFormat.PRIVATE);
            line(sb, "camera" + cameraId + " SCALER_STREAM_CONFIGURATION_MAP outputSizes(PRIVATE) = " + Arrays.toString(privateSizes));
        } else {
            line(sb, "camera" + cameraId + " SCALER_STREAM_CONFIGURATION_MAP = null");
        }

        // CameraExtensionCharacteristics.getSupportedExtensions() - API 31+.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                CameraExtensionCharacteristics extChars = cm.getCameraExtensionCharacteristics(cameraId);
                List<Integer> extensions = extChars.getSupportedExtensions();
                line(sb, "camera" + cameraId + " CameraExtensionCharacteristics.getSupportedExtensions() = " + extensions + " " + describeExtensions(extensions));
            } catch (Exception e) {
                line(sb, "camera" + cameraId + " CameraExtensionCharacteristics query failed - " + e);
            }
        } else {
            line(sb, "camera" + cameraId + " CameraExtensionCharacteristics requires API 31+, device is API " + Build.VERSION.SDK_INT + " - skipped (moot here, device is well above this)");
        }
    }

    private static String describeExtensions(List<Integer> extensions) {
        StringBuilder names = new StringBuilder("[");
        for (int i = 0; i < extensions.size(); i++) {
            if (i > 0) names.append(", ");
            int ext = extensions.get(i);
            String name;
            switch (ext) {
                case CameraExtensionCharacteristics.EXTENSION_AUTOMATIC: name = "AUTOMATIC"; break;
                // EXTENSION_BEAUTY and EXTENSION_FACE_RETOUCH are the same int (1) in this SDK.
                case CameraExtensionCharacteristics.EXTENSION_BEAUTY: name = "BEAUTY/FACE_RETOUCH"; break;
                case CameraExtensionCharacteristics.EXTENSION_BOKEH: name = "BOKEH"; break;
                case CameraExtensionCharacteristics.EXTENSION_HDR: name = "HDR"; break;
                case CameraExtensionCharacteristics.EXTENSION_NIGHT: name = "NIGHT"; break;
                default: name = "UNKNOWN(" + ext + ")";
            }
            names.append(name);
        }
        return names.append("]").toString();
    }

    private static <T> void logExplicit(StringBuilder sb, String cameraId, String label, CameraCharacteristics c, CameraCharacteristics.Key<T> key) {
        String value;
        try {
            value = describe(c.get(key));
        } catch (Exception e) {
            value = "<get() failed: " + e + ">";
        }
        line(sb, "camera" + cameraId + " [explicit] " + label + " = " + value);
    }

    private static String describe(Object v) {
        if (v == null) return "null";
        if (v instanceof int[]) return Arrays.toString((int[]) v);
        if (v instanceof float[]) return Arrays.toString((float[]) v);
        if (v instanceof long[]) return Arrays.toString((long[]) v);
        if (v instanceof byte[]) return Arrays.toString((byte[]) v);
        if (v instanceof boolean[]) return Arrays.toString((boolean[]) v);
        if (v.getClass().isArray()) return Arrays.deepToString((Object[]) v);
        return String.valueOf(v);
    }

    // ---------------------------------------------------------------- codecs

    private static void dumpCodecs(StringBuilder sb) {
        MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos();
        for (MediaCodecInfo info : infos) {
            if (!info.isEncoder()) continue;
            for (String type : info.getSupportedTypes()) {
                if (type.equalsIgnoreCase("video/avc") || type.equalsIgnoreCase("video/hevc")) {
                    dumpVideoEncoder(sb, info, type);
                } else if (type.equalsIgnoreCase("audio/mp4a-latm")) {
                    dumpAudioEncoder(sb, info, type);
                }
            }
        }
    }

    private static void dumpVideoEncoder(StringBuilder sb, MediaCodecInfo info, String type) {
        String label = info.getName() + " (" + type + ")";
        try {
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
            line(sb, "codec " + label + " profileLevels = " + describeProfileLevels(type, caps.profileLevels));

            MediaCodecInfo.EncoderCapabilities enc = caps.getEncoderCapabilities();
            boolean cq = enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
            boolean vbr = enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
            boolean cbr = enc.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
            line(sb, "codec " + label + " bitrateModes: CQ=" + cq + " VBR=" + vbr + " CBR=" + cbr);

            MediaCodecInfo.VideoCapabilities vcaps = caps.getVideoCapabilities();
            Range<Integer> widths = vcaps.getSupportedWidths();
            Range<Integer> heights = vcaps.getSupportedHeights();
            boolean size448 = false;
            try {
                size448 = vcaps.isSizeSupported(448, 448);
            } catch (Exception e) {
                line(sb, "codec " + label + " isSizeSupported(448,448) threw - " + e);
            }
            line(sb, "codec " + label + " supportedWidths=" + widths + " supportedHeights=" + heights + " isSizeSupported(448,448)=" + size448);

            line(sb, "codec " + label + " colorFormats = " + Arrays.toString(caps.colorFormats));
        } catch (Exception e) {
            line(sb, "codec " + label + " dump failed - " + e);
        }
    }

    private static void dumpAudioEncoder(StringBuilder sb, MediaCodecInfo info, String type) {
        String label = info.getName() + " (" + type + ")";
        try {
            MediaCodecInfo.CodecCapabilities caps = info.getCapabilitiesForType(type);
            line(sb, "codec " + label + " AAC profileLevels = " + describeProfileLevels(type, caps.profileLevels));

            MediaCodecInfo.AudioCapabilities acaps = caps.getAudioCapabilities();
            line(sb, "codec " + label + " bitrateRange = " + acaps.getBitrateRange());
            try {
                line(sb, "codec " + label + " supportedSampleRates = " + Arrays.toString(acaps.getSupportedSampleRates()));
            } catch (Exception e) {
                line(sb, "codec " + label + " getSupportedSampleRates() unavailable (codec reports ranges instead) - " + Arrays.toString(acaps.getSupportedSampleRateRanges()));
            }
            line(sb, "codec " + label + " inputChannelCount min=" + acaps.getMinInputChannelCount() + " max=" + acaps.getMaxInputChannelCount());
        } catch (Exception e) {
            line(sb, "codec " + label + " dump failed - " + e);
        }
    }

    private static String describeProfileLevels(String type, MediaCodecInfo.CodecProfileLevel[] levels) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < levels.length; i++) {
            if (i > 0) out.append(", ");
            out.append("{profile=").append(levels[i].profile).append(", level=").append(levels[i].level).append("}");
        }
        return out.append("]").toString();
    }

    // ---------------------------------------------------------------- audio mic preferences

    private static void dumpAudioMicPreferences(StringBuilder sb) {
        int sampleRate = 48000;
        int minBuf = AudioRecord.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = 3584;

        AudioRecord probe = null;
        try {
            probe = new AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);

            boolean dirOk = probe.setPreferredMicrophoneDirection(MicrophoneDirection.MIC_DIRECTION_TOWARDS_USER);
            line(sb, "AudioRecord.setPreferredMicrophoneDirection(MIC_DIRECTION_TOWARDS_USER) returned " + dirOk);

            // No named MIC_FIELD_DIMENSION_* constant exists on AudioRecord in this SDK; per
            // the platform docs the valid range is [-1..1] and 0.0f is "normal" (no zoom).
            boolean dimOk = probe.setPreferredMicrophoneFieldDimension(0.0f);
            line(sb, "AudioRecord.setPreferredMicrophoneFieldDimension(0.0f /* normal */) returned " + dimOk);

            try {
                List<android.media.MicrophoneInfo> mics = probe.getActiveMicrophones();
                line(sb, "AudioRecord.getActiveMicrophones() (idle probe, not recording), " + mics.size() + " mic(s):");
                for (android.media.MicrophoneInfo mic : mics) {
                    line(sb, describeMicrophone(mic));
                }
            } catch (Exception e) {
                line(sb, "AudioRecord.getActiveMicrophones() (idle probe) failed - " + e);
            }
        } catch (Exception e) {
            line(sb, "AudioRecord mic-preference probe failed - " + e);
        } finally {
            if (probe != null) probe.release();
        }

        line(sb, "note: getActiveMicrophones() during an ACTUAL round-video recording is logged separately under tag PixelCaps by a one-line hook in InstantCameraView (see prepareEncoder()) - the idle-probe result above only shows the default routing with no capture in progress.");
    }

    // ---------------------------------------------------------------- audio input capabilities

    /**
     * Investigates capture-format headroom ahead of possibly switching AudioRecord off
     * ENCODING_PCM_16BIT: whether ENCODING_PCM_FLOAT/24-bit/32-bit are accepted, what sample
     * rates AudioDeviceInfo reports as natively supported by the built-in mic (vs. what we
     * actually request, 48kHz), whether stereo/multi-channel input is accepted, and whether
     * AudioSource.UNPROCESSED is available. Every probe here only constructs (and immediately
     * releases) an AudioRecord to read back getState() - consistent with this class's
     * construct-only, no-capture contract (see class doc); it does NOT call startRecording()
     * or read any audio, so it cannot confirm what a live capture actually delivers (e.g.
     * whether a "supported" stereo config carries distinct channel content or duplicated
     * mono) - only whether the platform accepts the request at construction time.
     */
    private static void dumpAudioInputCapabilities(Context context, StringBuilder sb) {
        line(sb, "--- audio input capabilities ---");

        // AudioDeviceInfo.getSampleRates()/getChannelCounts()/getEncodings() on the built-in
        // mic port is the documented way to ask "what does this device natively support" as
        // opposed to "what will AudioRecord silently resample/convert to get me" - an empty
        // array is documented as "arbitrary rate/channel count accepted", not "nothing".
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) {
                line(sb, "AudioManager unavailable, skipping AudioDeviceInfo enumeration");
            } else {
                AudioDeviceInfo[] inputs = am.getDevices(AudioManager.GET_DEVICES_INPUTS);
                line(sb, "AudioManager.getDevices(GET_DEVICES_INPUTS): " + inputs.length + " device(s)");
                for (AudioDeviceInfo dev : inputs) {
                    line(sb, "  input device id=" + dev.getId() + " type=" + describeAudioDeviceType(dev.getType())
                            + " productName=\"" + dev.getProductName() + "\"");
                    line(sb, "    sampleRates=" + Arrays.toString(dev.getSampleRates())
                            + " (empty = platform claims arbitrary rate accepted, not \"none supported\")");
                    line(sb, "    channelCounts=" + Arrays.toString(dev.getChannelCounts()));
                    line(sb, "    channelMasks=" + Arrays.toString(dev.getChannelMasks()));
                    line(sb, "    channelIndexMasks=" + Arrays.toString(dev.getChannelIndexMasks()));
                    line(sb, "    encodings=" + Arrays.toString(dev.getEncodings()));
                }
            }
        } catch (Exception e) {
            line(sb, "AudioDeviceInfo enumeration failed - " + e);
        }

        // For context only: this is the fast-path OUTPUT sample rate, documented as specific
        // to the low-latency playback path, not the mic input path. Logged as a corroborating
        // signal for "what rate is this SoC's audio HAL actually clocked at", not as proof of
        // the input path's native rate - AudioDeviceInfo above is the real answer for input.
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            String outputRate = am != null ? am.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE) : null;
            line(sb, "AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE (fast-path OUTPUT, context only, NOT the input answer) = " + outputRate);
        } catch (Exception e) {
            line(sb, "PROPERTY_OUTPUT_SAMPLE_RATE query failed - " + e);
        }

        // Construction-only probes: getMinBufferSize() followed by actually building an
        // AudioRecord and checking getState() == STATE_INITIALIZED, since getMinBufferSize()
        // returning a valid size doesn't by itself guarantee the constructor succeeds on every
        // device. Every probe is released immediately after the check.
        int[] rates = {8000, 16000, 44100, 48000, 96000, 192000};
        for (int rate : rates) {
            probeConfig(sb, rate, AudioFormat.CHANNEL_IN_MONO, "MONO", AudioFormat.ENCODING_PCM_16BIT, "PCM_16BIT");
        }

        line(sb, "--- encoding probes at 48kHz mono (the rate/channel config we actually capture at) ---");
        probeConfig(sb, 48000, AudioFormat.CHANNEL_IN_MONO, "MONO", AudioFormat.ENCODING_PCM_FLOAT, "PCM_FLOAT");
        if (Build.VERSION.SDK_INT >= 31) {
            probeConfig(sb, 48000, AudioFormat.CHANNEL_IN_MONO, "MONO", AudioFormat.ENCODING_PCM_24BIT_PACKED, "PCM_24BIT_PACKED");
            probeConfig(sb, 48000, AudioFormat.CHANNEL_IN_MONO, "MONO", AudioFormat.ENCODING_PCM_32BIT, "PCM_32BIT");
        } else {
            line(sb, "ENCODING_PCM_24BIT_PACKED/PCM_32BIT require API 31, this device is API " + Build.VERSION.SDK_INT + " - skipped");
        }

        line(sb, "--- channel-count probes at 48kHz ---");
        probeConfig(sb, 48000, AudioFormat.CHANNEL_IN_STEREO, "STEREO", AudioFormat.ENCODING_PCM_16BIT, "PCM_16BIT");
        probeConfig(sb, 48000, AudioFormat.CHANNEL_IN_STEREO, "STEREO", AudioFormat.ENCODING_PCM_FLOAT, "PCM_FLOAT");

        // AudioSource.UNPROCESSED: PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED is documented to
        // return "true" (as a String) if declared, and be absent/null otherwise - logging the
        // raw value rather than a boolean since a non-"true" non-null value would itself be
        // informative. Backed up with an actual construction probe using that source, since a
        // property claim and a real AudioRecord accepting the source are two different checks.
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            String unprocessedProp = am != null ? am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) : null;
            line(sb, "AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED = " + unprocessedProp);
        } catch (Exception e) {
            line(sb, "PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED query failed - " + e);
        }
        probeSource(sb, MediaRecorder.AudioSource.UNPROCESSED, "UNPROCESSED", 48000);
        probeSource(sb, MediaRecorder.AudioSource.VOICE_COMMUNICATION, "VOICE_COMMUNICATION (current default, for comparison)", 48000);
    }

    private static void probeConfig(StringBuilder sb, int rate, int channelConfig, String channelName, int encoding, String encodingName) {
        int minBuf = AudioRecord.getMinBufferSize(rate, channelConfig, encoding);
        if (minBuf <= 0) {
            line(sb, rate + "Hz " + channelName + " " + encodingName + ": getMinBufferSize() = " + describeMinBufferError(minBuf) + " (rejected before construction)");
            return;
        }
        AudioRecord probe = null;
        try {
            probe = new AudioRecord(MediaRecorder.AudioSource.MIC, rate, channelConfig, encoding, minBuf * 2);
            boolean initialized = probe.getState() == AudioRecord.STATE_INITIALIZED;
            line(sb, rate + "Hz " + channelName + " " + encodingName + ": getMinBufferSize()=" + minBuf
                    + " constructed, getState()=" + (initialized ? "STATE_INITIALIZED (accepted)" : "STATE_UNINITIALIZED (rejected)")
                    + (initialized ? " actualSampleRate=" + probe.getSampleRate() : ""));
        } catch (Exception e) {
            line(sb, rate + "Hz " + channelName + " " + encodingName + ": getMinBufferSize()=" + minBuf + " but construction threw - " + e);
        } finally {
            if (probe != null) probe.release();
        }
    }

    private static String describeMinBufferError(int minBuf) {
        if (minBuf == AudioRecord.ERROR_BAD_VALUE) return "ERROR_BAD_VALUE";
        if (minBuf == AudioRecord.ERROR) return "ERROR";
        return String.valueOf(minBuf);
    }

    private static void probeSource(StringBuilder sb, int source, String sourceName, int rate) {
        int minBuf = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBuf <= 0) minBuf = 3584;
        AudioRecord probe = null;
        try {
            probe = new AudioRecord(source, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 2);
            boolean initialized = probe.getState() == AudioRecord.STATE_INITIALIZED;
            line(sb, "AudioSource." + sourceName + " @ " + rate + "Hz mono PCM_16BIT: constructed, getState()=" + (initialized ? "STATE_INITIALIZED (accepted)" : "STATE_UNINITIALIZED (rejected)"));
        } catch (Exception e) {
            line(sb, "AudioSource." + sourceName + " @ " + rate + "Hz mono PCM_16BIT: construction threw - " + e);
        } finally {
            if (probe != null) probe.release();
        }
    }

    // ---------------------------------------------------------------- MicrophoneInfo decoding

    /**
     * MicrophoneInfo has no getDirection() - the closest real API is getDirectionality()
     * (a polar-pattern enum: omni/cardioid/etc, not a spatial direction). Spatial info is
     * getPosition() (Coordinate3F, meters from an implementation-defined reference point)
     * and getOrientation() (Coordinate3F unit vector the mic points along). Decodes every
     * field requested - getLocation(), getOrientation(), getAddress(), getDescription() -
     * plus getDirectionality() as the nearest match to "getDirection()", getPosition(),
     * getType() and getId()/getGroup() for context.
     */
    public static String describeMicrophone(android.media.MicrophoneInfo mic) {
        StringBuilder out = new StringBuilder();
        out.append("  mic id=").append(mic.getId())
                .append(" type=").append(describeAudioDeviceType(mic.getType()))
                .append(" address=\"").append(mic.getAddress()).append("\"")
                .append(" description=\"").append(mic.getDescription()).append("\"")
                .append(" location=").append(describeLocation(mic.getLocation()))
                .append(" group=").append(mic.getGroup()).append("/").append(mic.getIndexInTheGroup())
                .append(" directionality(~getDirection)=").append(describeDirectionality(mic.getDirectionality()))
                .append(" position=").append(describeCoordinate(mic.getPosition()))
                .append(" orientation=").append(describeCoordinate(mic.getOrientation()));
        return out.toString();
    }

    private static String describeCoordinate(android.media.MicrophoneInfo.Coordinate3F c) {
        if (c == null || c.equals(android.media.MicrophoneInfo.POSITION_UNKNOWN) || c.equals(android.media.MicrophoneInfo.ORIENTATION_UNKNOWN)) {
            return "UNKNOWN";
        }
        return "(" + c.x + ", " + c.y + ", " + c.z + ")";
    }

    private static String describeLocation(int location) {
        switch (location) {
            case android.media.MicrophoneInfo.LOCATION_MAINBODY: return "MAINBODY(" + location + ")";
            case android.media.MicrophoneInfo.LOCATION_MAINBODY_MOVABLE: return "MAINBODY_MOVABLE(" + location + ")";
            case android.media.MicrophoneInfo.LOCATION_PERIPHERAL: return "PERIPHERAL(" + location + ")";
            default: return "UNKNOWN(" + location + ")";
        }
    }

    private static String describeDirectionality(int d) {
        switch (d) {
            case android.media.MicrophoneInfo.DIRECTIONALITY_OMNI: return "OMNI(" + d + ")";
            case android.media.MicrophoneInfo.DIRECTIONALITY_BI_DIRECTIONAL: return "BI_DIRECTIONAL(" + d + ")";
            case android.media.MicrophoneInfo.DIRECTIONALITY_CARDIOID: return "CARDIOID(" + d + ")";
            case android.media.MicrophoneInfo.DIRECTIONALITY_HYPER_CARDIOID: return "HYPER_CARDIOID(" + d + ")";
            case android.media.MicrophoneInfo.DIRECTIONALITY_SUPER_CARDIOID: return "SUPER_CARDIOID(" + d + ")";
            default: return "UNKNOWN(" + d + ")";
        }
    }

    private static String describeAudioDeviceType(int type) {
        // android.media.MicrophoneInfo.getType() returns an AudioDeviceInfo.TYPE_* constant.
        switch (type) {
            case android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC: return "BUILTIN_MIC(" + type + ")";
            case android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET: return "WIRED_HEADSET(" + type + ")";
            case android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO: return "BLUETOOTH_SCO(" + type + ")";
            case android.media.AudioDeviceInfo.TYPE_USB_HEADSET: return "USB_HEADSET(" + type + ")";
            case android.media.AudioDeviceInfo.TYPE_USB_DEVICE: return "USB_DEVICE(" + type + ")";
            default: return "TYPE(" + type + ")";
        }
    }

    // ---------------------------------------------------------------- output

    private static void writeToFile(String content) {
        try {
            File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "PixelCaps");
            if (!dir.exists() && !dir.mkdirs()) {
                Log.w(TAG, "could not create output dir " + dir);
            }
            File out = new File(dir, "pixelcaps_dump.txt");
            try (PrintWriter writer = new PrintWriter(new FileWriter(out, false))) {
                writer.print(content);
            }
            Log.d(TAG, "wrote dump to " + out.getAbsolutePath());
        } catch (Exception e) {
            Log.w(TAG, "failed to write dump file", e);
        }
    }
}
