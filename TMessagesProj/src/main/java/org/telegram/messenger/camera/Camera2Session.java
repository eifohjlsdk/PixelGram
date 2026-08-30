package org.telegram.messenger.camera;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.Face;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Session {

    private boolean isError;
    private boolean isSuccess;
    private boolean isClosed;

    private final CameraManager cameraManager;
    private final boolean isFront;
    public final String cameraId;
    private CameraCharacteristics cameraCharacteristics;

    private HandlerThread thread;
    private Handler handler;

    private CameraDevice cameraDevice;
    private SurfaceTexture surfaceTexture;
    private CameraCaptureSession captureSession;
    private Surface surface;

    private final CameraDevice.StateCallback cameraStateCallback;
    private final CameraCaptureSession.StateCallback captureStateCallback;
    private final CameraCaptureSession.CaptureCallback captureCallback;
    private CaptureRequest.Builder captureRequestBuilder;
    private Rect sensorSize;
    private float maxZoom = 1f;
    private float currentZoom = 1f;
    private Range<Integer> targetFpsRange;
    private boolean afContinuousVideoSupported;
    private boolean videoStabilizationSupported;
    private boolean opticalStabilizationSupported;
    private boolean faceDetectFullSupported;
    private int[] availableNoiseReductionModes = new int[0];
    private int[] availableEdgeModes = new int[0];
    private int[] availableTonemapModes = new int[0];
    private Range<Integer> exposureCompensationRange;
    private Rational exposureCompensationStep;
    // Explicitly pinned every request below to keep the HAL from picking its own default zoom -
    // see the CONTROL_ZOOM_RATIO block in updateCaptureRequest() for why this is necessary now.
    private Range<Float> zoomRatioRange;
    private int maxRegionsAe;
    private Rect currentFaceAeRect;
    private long lastFaceLogTimeMs;
    private long lastAeRegionsLogTimeMs;
    private long lastZoomCropLogTimeMs;

    private final Size previewSize;

    private ImageReader imageReader;

    private long lastTime;

    public static Camera2Session create(boolean front, int viewWidth, int viewHeight) {
        final Context context = ApplicationLoader.applicationContext;
        final CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);

        float bestAspectRatio = 0;
        Size bestSize = null;
        String cameraId = null;
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            for (int i = 0; i < cameraIds.length; ++i) {
                final String id = cameraIds[i];
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                if (characteristics == null) continue;
                if (characteristics.get(CameraCharacteristics.LENS_FACING) != (front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) {
                    continue;
                }
                StreamConfigurationMap confMap = (StreamConfigurationMap) characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                Size pixelSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
                float cameraAspectRatio = pixelSize == null ? 0 : (float) pixelSize.getWidth() / pixelSize.getHeight();
                if ((viewWidth / (float) viewHeight >= 1f) != (cameraAspectRatio >= 1f)) {
                    cameraAspectRatio = 1f / cameraAspectRatio;
                }
                if (bestAspectRatio <= 0 || Math.abs((float) viewWidth / viewHeight - bestAspectRatio) > Math.abs((float) viewWidth / viewHeight - cameraAspectRatio)) {
                    if (confMap != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        // Request a supersampled capture size, not the render target directly - the
                        // GL downscale in InstantCameraView does its own proper multi-tap filtering,
                        // so feeding it more source detail than the ISP's own (unknown-quality)
                        // scaler would produce is the whole point. See chooseSupersampleCaptureSize.
                        Size size = chooseSupersampleCaptureSize(confMap.getOutputSizes(SurfaceTexture.class), viewWidth, viewHeight);
                        if (size != null) {
                            bestAspectRatio = cameraAspectRatio;
                            cameraId = id;
                            bestSize = size;
                        }
                    }
                } else {

                }
            }
        } catch (Exception e) {
            FileLog.e(e);
        }

        if (cameraId == null || bestSize == null) {
            return null;
        }
        return new Camera2Session(context, front, cameraId, bestSize);
    }

    private Camera2Session(Context context, boolean isFront, String cameraId, Size size) {
        thread = new HandlerThread("tg_camera2");
        thread.start();
        handler = new Handler(thread.getLooper());

        cameraStateCallback = new CameraDevice.StateCallback() {
            @Override
            public void onOpened(@NonNull CameraDevice camera) {
                Camera2Session.this.cameraDevice = camera;
                Camera2Session.this.lastTime = System.currentTimeMillis();
                FileLog.d("Camera2Session camera #" + cameraId + " opened");
                checkOpen();
            }

            @Override
            public void onDisconnected(@NonNull CameraDevice camera) {
                Camera2Session.this.cameraDevice = camera;
                FileLog.d("Camera2Session camera #" + cameraId + " disconnected");
            }

            @Override
            public void onError(@NonNull CameraDevice camera, int error) {
                Camera2Session.this.cameraDevice = camera;
                FileLog.e("Camera2Session camera #" + cameraId + " received " + error + " error");
                AndroidUtilities.runOnUIThread(() -> {
                    isError = true;
                });
            }
        };

        captureStateCallback = new CameraCaptureSession.StateCallback() {
            @Override
            public void onConfigured(@NonNull CameraCaptureSession session) {
                captureSession = session;
                FileLog.e("Camera2Session camera #" + cameraId + " capture session configured");
                Camera2Session.this.lastTime = System.currentTimeMillis();
                try {
                    updateCaptureRequest();
                    AndroidUtilities.runOnUIThread(() -> {
                        isSuccess = true;
                        if (doneCallback != null) {
                            doneCallback.run();
                            doneCallback = null;
                        }
                    });
                } catch (Exception e) {
                    FileLog.e(e);
                }
            }

            @Override
            public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                captureSession = session;
                FileLog.e("Camera2Session camera #" + cameraId + " capture session failed to configure");
                AndroidUtilities.runOnUIThread(() -> {
                    isError = true;
                });
            }
        };

        captureCallback = new CameraCaptureSession.CaptureCallback() {
            @Override
            public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                logZoomCropReadback(result);
                onFaceDetectionResult(result);
            }
        };

        this.isFront = isFront;
        this.cameraId = cameraId;
        this.previewSize = size;
        this.lastTime = System.currentTimeMillis();
        this.imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(), ImageFormat.JPEG, 1);
        cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
            sensorSize = cameraCharacteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            final Float value = cameraCharacteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
            maxZoom = (value == null || value < 1f) ? 1f : value;
            targetFpsRange = pickTargetFpsRange(cameraCharacteristics, cameraId, isFront);
            afContinuousVideoSupported = checkModeSupport(cameraCharacteristics, cameraId, isFront, CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO, "CONTROL_AF_MODE_CONTINUOUS_VIDEO");
            videoStabilizationSupported = checkModeSupport(cameraCharacteristics, cameraId, isFront, CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON, "CONTROL_VIDEO_STABILIZATION_MODE_ON");
            opticalStabilizationSupported = checkModeSupport(cameraCharacteristics, cameraId, isFront, CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON, "LENS_OPTICAL_STABILIZATION_MODE_ON");
            faceDetectFullSupported = checkModeSupport(cameraCharacteristics, cameraId, isFront, CameraCharacteristics.STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES, CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL, "STATISTICS_FACE_DETECT_MODE_FULL");
            availableNoiseReductionModes = queryAvailableModes(cameraCharacteristics, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
            availableEdgeModes = queryAvailableModes(cameraCharacteristics, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
            availableTonemapModes = queryAvailableModes(cameraCharacteristics, CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
            exposureCompensationRange = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            exposureCompensationStep = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                zoomRatioRange = cameraCharacteristics.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
            }
            maxRegionsAe = checkMaxRegionsAe(cameraCharacteristics, cameraId, isFront);
            PixelCameraLog.d(buildConfigSummary());
            cameraManager.openCamera(cameraId, cameraStateCallback, handler);
        } catch (Exception e) {
            FileLog.e(e);
            AndroidUtilities.runOnUIThread(() -> {
                isError = true;
            });
        }
    }

    private static Range<Integer> pickTargetFpsRange(CameraCharacteristics characteristics, String cameraId, boolean front) {
        Range<Integer>[] ranges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
        if (ranges == null || ranges.length == 0) {
            return null;
        }

        Range<Integer> chosen = null;
        for (Range<Integer> range : ranges) {
            if (range.getLower() == 30 && range.getUpper() == 30) {
                chosen = range;
                break;
            }
        }
        if (chosen == null) {
            int bestWidth = Integer.MAX_VALUE;
            for (Range<Integer> range : ranges) {
                if (range.getLower() <= 30 && range.getUpper() >= 30) {
                    int width = range.getUpper() - range.getLower();
                    if (width < bestWidth) {
                        bestWidth = width;
                        chosen = range;
                    }
                }
            }
        }
        return chosen;
    }

    private static boolean supportsMode(int[] available, int mode) {
        if (available == null) return false;
        for (int m : available) {
            if (m == mode) return true;
        }
        return false;
    }

    private static boolean checkModeSupport(CameraCharacteristics characteristics, String cameraId, boolean front, CameraCharacteristics.Key<int[]> availableModesKey, int mode, String label) {
        return supportsMode(characteristics.get(availableModesKey), mode);
    }

    private static int[] queryAvailableModes(CameraCharacteristics characteristics, CameraCharacteristics.Key<int[]> availableModesKey) {
        int[] modes = characteristics.get(availableModesKey);
        return modes == null ? new int[0] : modes;
    }

    private static int checkMaxRegionsAe(CameraCharacteristics characteristics, String cameraId, boolean front) {
        Integer maxRegions = characteristics.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        return maxRegions == null ? 0 : maxRegions;
    }

    /** Resolves the user's preferred noise reduction mode against what this camera actually
     * supports, falling back preferred -> FAST -> OFF. Returns null only if none of those
     * three are supported at all (capture key is then left unset). */
    private Integer resolveNoiseReductionMode() {
        int preferred = PixelGramSettings.getNoiseReductionMode();
        if (supportsMode(availableNoiseReductionModes, preferred)) return preferred;
        if (supportsMode(availableNoiseReductionModes, CameraMetadata.NOISE_REDUCTION_MODE_FAST)) return CameraMetadata.NOISE_REDUCTION_MODE_FAST;
        if (supportsMode(availableNoiseReductionModes, CameraMetadata.NOISE_REDUCTION_MODE_OFF)) return CameraMetadata.NOISE_REDUCTION_MODE_OFF;
        return null;
    }

    /** Same fallback shape as resolveNoiseReductionMode(), for EDGE_MODE. */
    private Integer resolveEdgeMode() {
        int preferred = PixelGramSettings.getEdgeMode();
        if (supportsMode(availableEdgeModes, preferred)) return preferred;
        if (supportsMode(availableEdgeModes, CameraMetadata.EDGE_MODE_FAST)) return CameraMetadata.EDGE_MODE_FAST;
        if (supportsMode(availableEdgeModes, CameraMetadata.EDGE_MODE_OFF)) return CameraMetadata.EDGE_MODE_OFF;
        return null;
    }

    /** Same fallback shape as resolveNoiseReductionMode(), for TONEMAP_MODE. Only FAST/HIGH_
     * QUALITY are ever offered in settings (no OFF - see FINDINGS.md's tone-mapping
     * investigation for why a custom CONTRAST_CURVE isn't worth the tradeoff, so this stays a
     * choice between the device's own two built-in tonemap qualities). */
    private Integer resolveTonemapMode() {
        int preferred = PixelGramSettings.getTonemapMode();
        if (supportsMode(availableTonemapModes, preferred)) return preferred;
        if (supportsMode(availableTonemapModes, CameraMetadata.TONEMAP_MODE_FAST)) return CameraMetadata.TONEMAP_MODE_FAST;
        if (supportsMode(availableTonemapModes, CameraMetadata.TONEMAP_MODE_HIGH_QUALITY)) return CameraMetadata.TONEMAP_MODE_HIGH_QUALITY;
        return null;
    }

    /** Converts the user's exposure-compensation EV setting to steps against this camera's
     * cached CONTROL_AE_COMPENSATION_RANGE/_STEP, clamped to range. Null if unavailable. */
    private Integer resolveExposureCompensationSteps() {
        if (exposureCompensationRange == null || exposureCompensationStep == null || exposureCompensationStep.floatValue() == 0f) {
            return null;
        }
        float ev = PixelGramSettings.getExposureCompensationEv();
        int steps = Math.round(ev / exposureCompensationStep.floatValue());
        return Math.max(exposureCompensationRange.getLower(), Math.min(exposureCompensationRange.getUpper(), steps));
    }

    private static String noiseReductionModeName(Integer mode) {
        if (mode == null) return "none";
        if (mode == CameraMetadata.NOISE_REDUCTION_MODE_OFF) return "off";
        if (mode == CameraMetadata.NOISE_REDUCTION_MODE_FAST) return "fast";
        if (mode == CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY) return "hq";
        return String.valueOf(mode);
    }

    private static String edgeModeName(Integer mode) {
        if (mode == null) return "none";
        if (mode == CameraMetadata.EDGE_MODE_OFF) return "off";
        if (mode == CameraMetadata.EDGE_MODE_FAST) return "fast";
        if (mode == CameraMetadata.EDGE_MODE_HIGH_QUALITY) return "hq";
        return String.valueOf(mode);
    }

    private static String tonemapModeName(Integer mode) {
        if (mode == null) return "none";
        if (mode == CameraMetadata.TONEMAP_MODE_FAST) return "fast";
        if (mode == CameraMetadata.TONEMAP_MODE_HIGH_QUALITY) return "hq";
        return String.valueOf(mode);
    }

    /** One resolved-config line logged once per camera open, replacing the old per-mode/per-range spam. */
    private String buildConfigSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("cam").append(cameraId).append(' ').append(isFront ? "front" : "rear");
        // The round video output resolution, not previewSize.getWidth() (the camera preview
        // stream size, e.g. 1080) - matches what the per-recording marker line reports.
        sb.append(' ').append(PixelGramSettings.getResolution());
        sb.append(' ');
        if (targetFpsRange != null) {
            if (targetFpsRange.getLower().equals(targetFpsRange.getUpper())) {
                sb.append(targetFpsRange.getLower());
            } else {
                sb.append(targetFpsRange.getLower()).append('-').append(targetFpsRange.getUpper());
            }
        } else {
            sb.append('?');
        }
        sb.append("fps");

        Integer evSteps = resolveExposureCompensationSteps();
        float ev = (evSteps != null && exposureCompensationStep != null) ? evSteps * exposureCompensationStep.floatValue() : 0f;
        sb.append(" ev").append(ev >= 0 ? "+" : "").append(String.format(Locale.US, "%.1f", ev));

        sb.append(" nr:").append(noiseReductionModeName(resolveNoiseReductionMode()));
        sb.append(" edge:").append(edgeModeName(resolveEdgeMode()));
        sb.append(" tonemap:").append(tonemapModeName(resolveTonemapMode()));
        sb.append(" eis:").append(videoStabilizationSupported ? "on" : "n/a");
        sb.append(" ois:").append(opticalStabilizationSupported ? "on" : "n/a");
        sb.append(" aeregions:").append((maxRegionsAe > 0 && PixelGramSettings.isFaceAeMeteringEnabled()) ? 1 : 0);
        sb.append(" maxzoom:").append(maxZoom);
        return sb.toString();
    }

    private static CameraCharacteristics queryCharacteristicsForFacing(boolean front) {
        try {
            final Context context = ApplicationLoader.applicationContext;
            final CameraManager cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                if (characteristics == null) continue;
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing == null) continue;
                if (facing == (front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK)) {
                    return characteristics;
                }
            }
        } catch (Exception ignore) {
        }
        return null;
    }

    /** Static capability queries for the settings screen, which has no open camera session.
     * Independent of the aspect-ratio-driven create() lookup above. Return empty/null on any
     * failure (no permission yet, no such camera, ...) - the settings UI treats that as
     * "assume unsupported, disable." */
    public static int[] queryAvailableNoiseReductionModes(boolean front) {
        CameraCharacteristics characteristics = queryCharacteristicsForFacing(front);
        if (characteristics == null) return new int[0];
        return queryAvailableModes(characteristics, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
    }

    public static int[] queryAvailableEdgeModes(boolean front) {
        CameraCharacteristics characteristics = queryCharacteristicsForFacing(front);
        if (characteristics == null) return new int[0];
        return queryAvailableModes(characteristics, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
    }

    public static int[] queryAvailableTonemapModes(boolean front) {
        CameraCharacteristics characteristics = queryCharacteristicsForFacing(front);
        if (characteristics == null) return new int[0];
        return queryAvailableModes(characteristics, CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES);
    }

    public static Range<Integer> queryExposureCompensationRange(boolean front) {
        CameraCharacteristics characteristics = queryCharacteristicsForFacing(front);
        return characteristics == null ? null : characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
    }

    public static Rational queryExposureCompensationStep(boolean front) {
        CameraCharacteristics characteristics = queryCharacteristicsForFacing(front);
        return characteristics == null ? null : characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
    }

    // Called on the tg_camera2 handler thread for every completed capture while a
    // repeating request is active. Feeds face detection (STATISTICS_FACES, enabled
    // via STATISTICS_FACE_DETECT_MODE_FULL above) into CONTROL_AE_REGIONS, but only
    // rebuilds the capture request when the face has moved meaningfully - AE chasing
    // per-frame detection jitter causes visible brightness pumping.
    // Verifies our requested CONTROL_ZOOM_RATIO/SCALER_CROP_REGION are actually reaching the HAL,
    // rather than trusting what we set on the request - same "don't just trust the request"
    // philosophy as the CONTROL_AE_REGIONS readback below. Independent of face detection support
    // (unlike onFaceDetectionResult, which early-returns without it).
    private void logZoomCropReadback(CaptureResult result) {
        if (!PixelGramSettings.isDebugLoggingEnabled()) return;
        long now = System.currentTimeMillis();
        if (now - lastZoomCropLogTimeMs < 1000) return;
        lastZoomCropLogTimeMs = now;
        Float appliedZoomRatio = result.get(CaptureResult.CONTROL_ZOOM_RATIO);
        Rect appliedCrop = result.get(CaptureResult.SCALER_CROP_REGION);
        PixelCameraLog.d("camera #" + cameraId + ": requested zoomRatio=" + currentZoom + " cropRegion=" + cropRegion
                + " | HAL-applied zoomRatio=" + appliedZoomRatio + " cropRegion=" + appliedCrop
                + " | sensorSize=" + sensorSize);
    }

    private void onFaceDetectionResult(CaptureResult result) {
        // Face detection runs whenever the hardware supports it, independent of the
        // face-AE-metering setting - that setting only gates whether the tracked face rect
        // feeds CONTROL_AE_REGIONS (see updateCaptureRequest()); exposure compensation is
        // gated on face presence alone so it stays testable with metering off.
        if (!recordingVideo || !faceDetectFullSupported) return;

        // Result-side confirmation that the HAL is actually applying the region we asked for,
        // not just what we requested - covers both the face-present and face-absent windows
        // (unlike the face-bounds debug log below, which only fires when a face is present).
        // Only reaches logcat/file when debug logging is on, same as the face-bounds log.
        if (PixelGramSettings.isDebugLoggingEnabled()) {
            long now = System.currentTimeMillis();
            if (now - lastAeRegionsLogTimeMs >= 1000) {
                MeteringRectangle[] appliedRegions = result.get(CaptureResult.CONTROL_AE_REGIONS);
                PixelCameraLog.d("camera #" + cameraId + ": applied CONTROL_AE_REGIONS=" + Arrays.toString(appliedRegions) + " currentFaceAeRect=" + currentFaceAeRect);
                lastAeRegionsLogTimeMs = now;
            }
        }

        Face[] faces = result.get(CaptureResult.STATISTICS_FACES);
        if (faces == null || faces.length == 0) {
            // No face: drop the AE region and, via updateCaptureRequest()'s own
            // currentFaceAeRect check, the +EV exposure compensation with it -
            // outdoors on the rear camera with nothing to meter on, holding the
            // last-known face region/compensation blows highlights.
            if (currentFaceAeRect != null) {
                currentFaceAeRect = null;
                PixelCameraLog.d("camera #" + cameraId + ": face lost, clearing AE region and exposure compensation to 0");
                updateCaptureRequest();
            }
            return;
        }

        Face largest = faces[0];
        long largestArea = faceArea(largest);
        for (Face face : faces) {
            long area = faceArea(face);
            if (area > largestArea) {
                largest = face;
                largestArea = area;
            }
        }

        // Face bounds and CONTROL_AE_REGIONS both use active-array coordinates;
        // intersect defensively in case a face straddles the array edge.
        Rect regionRect = new Rect(largest.getBounds());
        if (sensorSize != null && !regionRect.intersect(sensorSize)) {
            return;
        }
        if (regionRect.isEmpty()) return;

        boolean wasAbsent = currentFaceAeRect == null;
        boolean shouldRebuild = wasAbsent || hasMovedMeaningfully(currentFaceAeRect, regionRect);

        if (wasAbsent) {
            PixelCameraLog.d("camera #" + cameraId + ": face acquired, exposure compensation applied (" + resolveExposureCompensationSteps() + " steps)");
        }

        // Only reaches logcat/file at all when debug logging is on - unlike PixelCameraLog.d's
        // other call sites, which always reach logcat regardless of the setting.
        if (PixelGramSettings.isDebugLoggingEnabled()) {
            long now = System.currentTimeMillis();
            if (now - lastFaceLogTimeMs >= 1000) {
                PixelCameraLog.d("camera #" + cameraId + ": largest face bounds " + regionRect + (shouldRebuild ? " (region updated)" : " (region unchanged, holding)"));
                lastFaceLogTimeMs = now;
            }
        }

        if (shouldRebuild) {
            currentFaceAeRect = regionRect;
            updateCaptureRequest();
        }
    }

    private static long faceArea(Face face) {
        Rect bounds = face.getBounds();
        return (long) bounds.width() * bounds.height();
    }

    private boolean hasMovedMeaningfully(Rect previous, Rect current) {
        if (sensorSize == null) return true;
        // ~5% of the shorter active-array side.
        int threshold = Math.max(1, Math.min(sensorSize.width(), sensorSize.height()) / 20);
        return Math.abs(previous.centerX() - current.centerX()) > threshold
                || Math.abs(previous.centerY() - current.centerY()) > threshold
                || Math.abs(previous.width() - current.width()) > threshold
                || Math.abs(previous.height() - current.height()) > threshold;
    }

    private Runnable doneCallback;
    public void whenDone(Runnable doneCallback) {
        if (isInitiated()) {
            doneCallback.run();
            this.doneCallback = null;
        } else {
            this.doneCallback = doneCallback;
        }
    }

    public void open(SurfaceTexture surfaceTexture) {
        handler.post(() -> {
            this.surfaceTexture = surfaceTexture;
            if (surfaceTexture != null) {
                surfaceTexture.setDefaultBufferSize(getPreviewWidth(), getPreviewHeight());
            }
            checkOpen();
        });
    }

    private boolean opened = false;
    private void checkOpen() {
        if (opened) return;
        if (surfaceTexture == null || cameraDevice == null) return;
        opened = true;

        surface = new Surface(surfaceTexture);

        try {
            ArrayList<Surface> surfaces = new ArrayList<>();
            surfaces.add(surface);
            surfaces.add(imageReader.getSurface());
            cameraDevice.createCaptureSession(surfaces, captureStateCallback, null);
        } catch (Exception e) {
            FileLog.e(e);
            AndroidUtilities.runOnUIThread(() -> {
                isError = true;
            });
        }
    }

    public boolean isInitiated() {
        return !isError && isSuccess && !isClosed;
    }

    public int getDisplayOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int displayOrientation;
            if (isFront) {
                displayOrientation = (sensorOrientation + degrees) % 360;
                displayOrientation = (360 - displayOrientation) % 360; // compensate the mirror
            } else { // back-facing
                displayOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return displayOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    private int getJpegOrientation() {
        try {
            Context context = ApplicationLoader.applicationContext;
            if (context == null) {
                return 0;
            }
            int rotation = ((WindowManager) context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_0:
                    degrees = 0;
                    break;
                case Surface.ROTATION_90:
                    degrees = 90;
                    break;
                case Surface.ROTATION_180:
                    degrees = 180;
                    break;
                case Surface.ROTATION_270:
                    degrees = 270;
                    break;
            }

            Integer sensorOrientation = cameraCharacteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            int jpegOrientation;
            if (isFront) {
                jpegOrientation = (sensorOrientation + degrees) % 360;
                jpegOrientation = (360 - jpegOrientation) % 360; // compensate the mirror
            } else { // back-facing
                jpegOrientation = (sensorOrientation - degrees + 360) % 360;
            }
            return jpegOrientation;
        } catch (Exception e) {
            FileLog.e(e);
        }
        return 0;
    }

    public int getWorldAngle() {
        int displayOrientation = getDisplayOrientation();
        int jpegOrientation = getJpegOrientation();
        int diffOrientation = jpegOrientation - displayOrientation;
        if (diffOrientation < 0) {
            diffOrientation += 360;
        }
        return diffOrientation;
    }

    public int getCurrentOrientation() {
        return getJpegOrientation();
    }

    private final Rect cropRegion = new Rect();
    public void setZoom(float value) {
        if (!isInitiated()) return;
        if (captureRequestBuilder == null || cameraDevice == null || sensorSize == null) return;

        currentZoom = Utilities.clamp(value, maxZoom, 1f);
        updateCaptureRequest();

        try {
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, handler);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private boolean flashing;
    public void setFlash(boolean flash) {
        if (flashing != flash) {
            flashing = flash;
            updateCaptureRequest();
        }
    }
    public boolean getFlash() {
        return flashing;
    }

    public float getZoom() {
        return currentZoom;
    }

    public float getMaxZoom() {
        return maxZoom;
    }

    public float getMinZoom() {
        // TODO: support wide zoom camera switching
        return 1f;
    }

    public int getPreviewWidth() {
        return previewSize.getWidth();
    }

    public int getPreviewHeight() {
        return previewSize.getHeight();
    }

    public void destroy(boolean async) {
        destroy(async, null);
    }

    public void destroy(boolean async, Runnable afterCallback) {
        isClosed = true;
        if (async) {
            handler.post(() -> {
                if (captureSession != null) {
                    captureSession.close();
                    captureSession = null;
                }
                if (cameraDevice != null) {
                    cameraDevice.close();
                    cameraDevice = null;
                }
                if (imageReader != null) {
                    imageReader.close();
                    imageReader = null;
                }
                thread.quitSafely();
                AndroidUtilities.runOnUIThread(() -> {
                    try {
                        thread.join();
                    } catch (Exception e) {
                        FileLog.e(e);
                    }
                    if (afterCallback != null) {
                        afterCallback.run();
                    }
                });
            });
        } else {
            if (captureSession != null) {
                captureSession.close();
                captureSession = null;
            }
            if (cameraDevice != null) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
            thread.quitSafely();
            try {
                thread.join();
            } catch (Exception e) {
                FileLog.e(e);
            }
            if (afterCallback != null) {
                AndroidUtilities.runOnUIThread(afterCallback);
            }
        }
    }

    private boolean recordingVideo;
    public void setRecordingVideo(boolean recording) {
        if (recordingVideo != recording) {
            recordingVideo = recording;
            updateCaptureRequest();
        }
    }

    private boolean scanningBarcode;
    public void setScanningBarcode(boolean scanning) {
        if (scanningBarcode != scanning) {
            scanningBarcode = scanning;
            updateCaptureRequest();
        }
    }

    private boolean nightMode;
    public void setNightMode(boolean enable) {
        if (nightMode != enable) {
            nightMode = enable;
            updateCaptureRequest();
        }
    }

    private void updateCaptureRequest() {
        if (cameraDevice == null || surface == null || captureSession == null) return;
        try {
            int template;
            if (recordingVideo) {
                template = CameraDevice.TEMPLATE_RECORD;
            } else if (scanningBarcode) {
                template = CameraDevice.TEMPLATE_STILL_CAPTURE;
            } else {
                template = CameraDevice.TEMPLATE_PREVIEW;
            }
            captureRequestBuilder = cameraDevice.createCaptureRequest(template);

            if (scanningBarcode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
            } else if (nightMode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, isFront ? CameraMetadata.CONTROL_SCENE_MODE_NIGHT_PORTRAIT : CameraMetadata.CONTROL_SCENE_MODE_NIGHT);
            }

            captureRequestBuilder.set(CaptureRequest.FLASH_MODE, flashing ? (recordingVideo ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_SINGLE) : CaptureRequest.FLASH_MODE_OFF);

            if (recordingVideo) {
                if (targetFpsRange != null) {
                    captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, targetFpsRange);
                }
                captureRequestBuilder.set(CaptureRequest.CONTROL_CAPTURE_INTENT, CaptureRequest.CONTROL_CAPTURE_INTENT_VIDEO_RECORD);

                if (afContinuousVideoSupported) {
                    try {
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO);
                    } catch (Exception e) {
                        PixelCameraLog.w("camera #" + cameraId + ": CONTROL_AF_MODE set failed", e);
                    }
                }

                if (videoStabilizationSupported) {
                    try {
                        captureRequestBuilder.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);
                    } catch (Exception e) {
                        PixelCameraLog.w("camera #" + cameraId + ": CONTROL_VIDEO_STABILIZATION_MODE set failed", e);
                    }
                }

                if (opticalStabilizationSupported) {
                    try {
                        captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
                    } catch (Exception e) {
                        PixelCameraLog.w("camera #" + cameraId + ": LENS_OPTICAL_STABILIZATION_MODE set failed", e);
                    }
                }

                if (faceDetectFullSupported) {
                    try {
                        captureRequestBuilder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_FULL);
                    } catch (Exception e) {
                        PixelCameraLog.w("camera #" + cameraId + ": STATISTICS_FACE_DETECT_MODE set failed", e);
                    }
                }

                Integer edgeMode = resolveEdgeMode();
                if (edgeMode != null) {
                    try {
                        captureRequestBuilder.set(CaptureRequest.EDGE_MODE, edgeMode);
                    } catch (Exception e) {
                        PixelCameraLog.w("camera #" + cameraId + ": EDGE_MODE set failed", e);
                    }
                }

                Integer noiseReductionMode = resolveNoiseReductionMode();
                if (noiseReductionMode != null) {
                    try {
                        captureRequestBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, noiseReductionMode);
                    } catch (Exception e) {
                        PixelCameraLog.w("camera #" + cameraId + ": NOISE_REDUCTION_MODE set failed", e);
                    }
                }

                Integer tonemapMode = resolveTonemapMode();
                if (tonemapMode != null) {
                    try {
                        captureRequestBuilder.set(CaptureRequest.TONEMAP_MODE, tonemapMode);
                    } catch (Exception e) {
                        PixelCameraLog.w("camera #" + cameraId + ": TONEMAP_MODE set failed", e);
                    }
                }

                Integer exposureCompensationSteps = resolveExposureCompensationSteps();
                if (exposureCompensationSteps != null) {
                    try {
                        // Only meter-boost while we actually have a face to expose for -
                        // otherwise (e.g. rear camera outdoors) this blows highlights. Gated on
                        // face presence alone, independent of the face-AE-metering setting below.
                        int compensation = currentFaceAeRect != null ? exposureCompensationSteps : 0;
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensation);
                    } catch (Exception e) {
                        PixelCameraLog.w("camera #" + cameraId + ": CONTROL_AE_EXPOSURE_COMPENSATION set failed", e);
                    }
                }

                // Face-AE metering setting only gates whether the tracked face rect drives
                // CONTROL_AE_REGIONS - it doesn't gate face detection or exposure compensation
                // above, so all four (metering x face-present) combinations stay testable.
                //
                // Always set this key explicitly, every call, rather than only setting it when
                // a face is present - omitting it on face-loss relied on a fresh
                // createCaptureRequest() builder resetting AE_REGIONS to a full-frame default on
                // its own, which isn't guaranteed: some HALs treat 3A metering regions as
                // persistent algorithm state that only changes when a request explicitly sets a
                // new value, so a request that never mentions the key can leave the HAL still
                // metering the last face position even after currentFaceAeRect goes back to
                // null.
                //
                // The "clear" case is a single full-frame region at weight 0, not an empty
                // array: an empty MeteringRectangle[] is documented for the *request* to mean
                // "clear," but at least some devices reject it outright when actually applied via
                // setRepeatingRequest() (IllegalArgumentException at
                // CameraCaptureSessionImpl.setRepeatingRequest immediately after session
                // creation). A weight-0 region spanning the whole active array is accepted
                // everywhere and is AE-neutral - weight 0 means "don't bias toward this region,"
                // and it covers the same area a default/no-region request would meter anyway.
                if (maxRegionsAe > 0 && sensorSize != null) {
                    try {
                        MeteringRectangle[] regions;
                        if (currentFaceAeRect != null && PixelGramSettings.isFaceAeMeteringEnabled()) {
                            regions = new MeteringRectangle[]{new MeteringRectangle(currentFaceAeRect, MeteringRectangle.METERING_WEIGHT_MAX)};
                        } else {
                            regions = new MeteringRectangle[]{new MeteringRectangle(sensorSize, 0)};
                        }
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, regions);
                    } catch (Exception e) {
                        PixelCameraLog.w("camera #" + cameraId + ": CONTROL_AE_REGIONS set failed", e);
                    }
                }
            }

            // Set explicitly on every request, not just when we're actually zoomed - on this
            // device the HAL was picking its own default CONTROL_ZOOM_RATIO (observed via logcat
            // as "AHal::GsCapture: SetZoom: Update zoom from 0 to 0.5") once the capture stream
            // got large enough (see the supersample capture-size change), landing well below our
            // own always-1.0-or-above zoom model and visibly shifting/cropping the frame. Pinning
            // this ourselves - the same "always set, never just omit" fix already applied to
            // CONTROL_AE_REGIONS above - removes the dependency on whatever default the HAL
            // chooses for a given stream configuration.
            if (zoomRatioRange != null) {
                try {
                    float requestedZoomRatio = Utilities.clamp(currentZoom, zoomRatioRange.getUpper(), zoomRatioRange.getLower());
                    captureRequestBuilder.set(CaptureRequest.CONTROL_ZOOM_RATIO, requestedZoomRatio);
                } catch (Exception e) {
                    PixelCameraLog.w("camera #" + cameraId + ": CONTROL_ZOOM_RATIO set failed", e);
                }
            }

            // Always define an explicit, sensor-centered crop matching previewSize's aspect ratio
            // - not just when actually zoomed. This sensor's active array is 3440x2448 (not
            // square), so our square 1920x1920 supersample capture requires the HAL to crop it
            // down to 1:1 somehow; with no explicit crop, its default for that specific
            // resolution wasn't vertically centered (far more headroom above the subject than
            // below - the reported "video shifted down" bug). The smaller pre-supersampling
            // request apparently mapped to a differently (better) centered HAL default, which is
            // exactly the kind of per-resolution behavior this removes any dependence on.
            //
            // Deliberately NOT scaled by currentZoom - per CaptureRequest.SCALER_CROP_REGION's
            // own docs, once CONTROL_ZOOM_RATIO is in use (set unconditionally above), this crop
            // "should be left as the default activeArray size" and used only for
            // letterboxing/pillarboxing to the output aspect; a crop additionally shrunk by zoom
            // is "windowboxing", which the framework will just override back to the full active
            // array whenever zoomRatio != 1 anyway. Zoom amount comes entirely from
            // CONTROL_ZOOM_RATIO now - this crop only ever corrects aspect.
            if (sensorSize != null) {
                final float outputAspect = previewSize.getWidth() / (float) previewSize.getHeight();
                final float sensorAspect = sensorSize.width() / (float) sensorSize.height();
                final int cropWidth, cropHeight;
                if (outputAspect > sensorAspect) {
                    cropWidth = sensorSize.width();
                    cropHeight = Math.round(cropWidth / outputAspect);
                } else {
                    cropHeight = sensorSize.height();
                    cropWidth = Math.round(cropHeight * outputAspect);
                }
                final int centerX = sensorSize.width() / 2;
                final int centerY = sensorSize.height() / 2;
                final int deltaX = cropWidth / 2;
                final int deltaY = cropHeight / 2;
                cropRegion.set(
                        centerX - deltaX,
                        centerY - deltaY,
                        centerX + deltaX,
                        centerY + deltaY
                );
                captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
            }

            captureRequestBuilder.addTarget(surface);
            captureSession.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, handler);
        } catch (Exception e) {
            FileLog.e("Camera2Sessions setRepeatingRequest error in updateCaptureRequest", e);
        }
    }

    public boolean takePicture(final File file, Utilities.Callback<Integer> whenDone) {
        if (cameraDevice == null || captureSession == null) return false;
        try {
            CaptureRequest.Builder captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            final int orientation = getJpegOrientation();
            captureRequestBuilder.set(CaptureRequest.JPEG_ORIENTATION, orientation);
            imageReader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    Image image = reader.acquireLatestImage();
                    ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buffer.remaining()];
                    buffer.get(bytes);

                    FileOutputStream output = null;
                    try {
                        output = new FileOutputStream(file);
                        output.write(bytes);
                    } catch (IOException e) {
                        e.printStackTrace();
                    } finally {
                        image.close();
                        if (null != output) {
                            try {
                                output.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        }
                    }

                    AndroidUtilities.runOnUIThread(() -> {
                        if (whenDone != null) {
                            whenDone.run(orientation);
                        }
                    });
                }
            }, null);
            if (scanningBarcode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_SCENE_MODE, CameraMetadata.CONTROL_SCENE_MODE_BARCODE);
            }
            captureRequestBuilder.addTarget(imageReader.getSurface());
            captureSession.capture(captureRequestBuilder.build(), new CameraCaptureSession.CaptureCallback() {}, null);
            return true;
        } catch (Exception e) {
            FileLog.e("Camera2Sessions takePicture error", e);
            return false;
        }
    }


    // Long edge every FULL/LEVEL_3 device is expected to sustain 30fps at for a non-stalling
    // (SurfaceTexture/record-class) stream - this is a conservative heuristic cap, not a queried
    // per-size android.hardware.camera2.params.StreamConfigurationMap.getOutputMinFrameDuration()
    // answer (that would give an exact number for this specific device/size, but wasn't gathered
    // here). Keeps the picker out of still-capture-only sizes that may not hold 30fps; verify
    // empirically (watch for dropped/stuttering frames in a real recording) before raising it.
    // See FINDINGS.md for the measured candidate sizes on the Pixel 11 Pro's front/rear cameras.
    private static final int SUPERSAMPLE_MAX_DIMENSION = 1920;

    // Largest near-square SurfaceTexture output the sensor offers within SUPERSAMPLE_MAX_DIMENSION,
    // for oversampling the round-video capture ahead of a GL downscale to the render target
    // (see InstantCameraView's supersample pipeline). Falls back to the old direct-to-target
    // chooseOptimalSize behaviour if nothing on the sensor fits under the cap (e.g. a legacy/small
    // sensor with no size anywhere near square that low).
    private static Size chooseSupersampleCaptureSize(Size[] choices, int fallbackWidth, int fallbackHeight) {
        Size best = null;
        float bestAspectDelta = Float.MAX_VALUE;
        for (Size option : choices) {
            int w = option.getWidth(), h = option.getHeight();
            if (Math.max(w, h) > SUPERSAMPLE_MAX_DIMENSION) continue;
            float aspectDelta = Math.abs((float) w / h - 1f);
            if (best == null
                    || aspectDelta < bestAspectDelta - 0.001f
                    || (Math.abs(aspectDelta - bestAspectDelta) <= 0.001f && (long) w * h > (long) best.getWidth() * best.getHeight())) {
                best = option;
                bestAspectDelta = aspectDelta;
            }
        }
        if (best != null) {
            return best;
        }
        return chooseOptimalSize(choices, fallbackWidth, fallbackHeight, false);
    }

    public static Size chooseOptimalSize(Size[] choices, int width, int height, boolean notBigger) {
        List<Size> bigEnoughWithAspectRatio = new ArrayList<>(choices.length);
        List<Size> bigEnough = new ArrayList<>(choices.length);
        int w = width;
        int h = height;
        for (int a = 0; a < choices.length; a++) {
            Size option = choices[a];
            if (notBigger && (option.getHeight() > height || option.getWidth() > width)) {
                continue;
            }
            if (option.getHeight() == option.getWidth() * h / w && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnoughWithAspectRatio.add(option);
            } else if (option.getHeight() * option.getWidth() <= width * height * 4 && option.getWidth() >= width && option.getHeight() >= height) {
                bigEnough.add(option);
            }
        }
        if (bigEnoughWithAspectRatio.size() > 0) {
            return Collections.min(bigEnoughWithAspectRatio, new CompareSizesByArea());
        } else if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return Collections.max(Arrays.asList(choices), new CompareSizesByArea());
        }
    }
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }

}