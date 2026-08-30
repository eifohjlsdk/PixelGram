package org.telegram.messenger.camera;

import android.util.Log;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.time.FastDateFormat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.util.Locale;

/**
 * Rotating debug logger for the PixelCamera pipeline. Every call always forwards to
 * android.util.Log under tag "PixelCamera" (same behavior as before this existed); it
 * additionally appends to a small rotating file on device, but only while
 * PixelGramSettings.isDebugLoggingEnabled() is on - that's the whole point of the setting,
 * so nothing is written to disk when it's off.
 *
 * Unlike FileLog, this rotates: a single-generation swap to pixelcamera.log.1 once the
 * active file passes ~1.5MB, capping total on-disk size at a few MB.
 */
public class PixelCameraLog {

    private static final String TAG = "PixelCamera";
    private static final long MAX_FILE_BYTES = 1_500_000L;

    private static final DispatchQueue queue = new DispatchQueue("pixelCameraLog");
    private static FastDateFormat dateFormat;
    private static File logFile;
    private static File rotatedFile;
    private static OutputStreamWriter writer;
    private static boolean initAttempted;

    private static void ensureInit() {
        if (initAttempted) return;
        initAttempted = true;
        try {
            dateFormat = FastDateFormat.getInstance("dd_MM_yyyy_HH_mm_ss.SSS", Locale.US);
            File dir = AndroidUtilities.getLogsDir();
            if (dir == null) return;
            logFile = new File(dir, "pixelcamera.log");
            rotatedFile = new File(dir, "pixelcamera.log.1");
            writer = new OutputStreamWriter(new FileOutputStream(logFile, true));
        } catch (Exception ignore) {
        }
    }

    private static void writeLine(String line) {
        queue.postRunnable(() -> {
            ensureInit();
            if (writer == null) return;
            try {
                writer.write(dateFormat.format(System.currentTimeMillis()) + " " + line + "\n");
                writer.flush();
                if (logFile.length() > MAX_FILE_BYTES) {
                    writer.close();
                    logFile.renameTo(rotatedFile);
                    writer = new OutputStreamWriter(new FileOutputStream(logFile, false));
                }
            } catch (Exception ignore) {
            }
        });
    }

    public static void d(String message) {
        Log.d(TAG, message);
        if (PixelGramSettings.isDebugLoggingEnabled()) {
            writeLine("D/" + TAG + ": " + message);
        }
    }

    public static void w(String message) {
        Log.w(TAG, message);
        if (PixelGramSettings.isDebugLoggingEnabled()) {
            writeLine("W/" + TAG + ": " + message);
        }
    }

    public static void w(String message, Throwable t) {
        Log.w(TAG, message, t);
        if (PixelGramSettings.isDebugLoggingEnabled()) {
            writeLine("W/" + TAG + ": " + message + " - " + t);
        }
    }

    /**
     * One marker line per recording start, config summary included. Always goes to logcat (this
     * was the actual bug: it used to skip Log entirely and only ever write to the on-device file,
     * gated behind isDebugLoggingEnabled() - so it could never show up in `adb logcat` at all,
     * regardless of that setting). The file write remains gated, matching d()/w() above.
     */
    public static void marker(String configSummary) {
        Log.i(TAG, "=== recording start: " + configSummary + " ===");
        if (!PixelGramSettings.isDebugLoggingEnabled()) return;
        writeLine("=== recording start: " + configSummary + " ===");
    }
}
