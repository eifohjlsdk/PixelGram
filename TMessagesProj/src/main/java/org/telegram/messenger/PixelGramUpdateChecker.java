package org.telegram.messenger;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.Build;

import org.json.JSONObject;
import org.telegram.messenger.browser.Browser;
import org.telegram.messenger.camera.PixelGramSettings;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.LaunchActivity;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Background check against the GitHub releases API for the PixelGram fork itself - not
 * Telegram's own update mechanism (SharedConfig.pendingAppUpdate), which is Telegram-server-based
 * and standalone-build-only. Throttled to once per 30 days and unmetered connections only for
 * the automatic (non-manual) check; the settings screen's "Check now" bypasses both.
 */
public class PixelGramUpdateChecker {

    // Single constant, easy to point at a different fork/repo later.
    public static final String REPO_URL = "https://github.com/eifohjlsdk/PixelGram";
    private static final String API_URL = "https://api.github.com/repos/eifohjlsdk/PixelGram/releases/latest";
    private static final long CHECK_INTERVAL_MS = 30L * 24 * 60 * 60 * 1000;

    public interface OnCheckDone {
        void run();
    }

    public static void checkForUpdates(boolean manual) {
        checkForUpdates(manual, null);
    }

    public static void checkForUpdates(boolean manual, OnCheckDone onDone) {
        if (!manual) {
            if (System.currentTimeMillis() - PixelGramSettings.getLastUpdateCheckMs() < CHECK_INTERVAL_MS) {
                return;
            }
            if (!isUnmeteredConnection()) {
                return;
            }
        }
        Utilities.globalQueue.postRunnable(() -> {
            Result result = fetchLatestRelease();
            AndroidUtilities.runOnUIThread(() -> {
                if (result != null) {
                    // Only reset the 30-day window on an actual completed check, not a
                    // network failure - a transient failure shouldn't cost a month's wait.
                    PixelGramSettings.setLastUpdateCheckMs(System.currentTimeMillis());
                    boolean isNewer = isNewerVersion(result.version, BuildVars.PIXELGRAM_VERSION);
                    if (isNewer) {
                        PixelGramSettings.setLastSeenVersion(result.version);
                        showUpdateBulletin(result);
                    } else if (manual) {
                        showSimpleBulletin("PixelGram is up to date.");
                    }
                } else if (manual) {
                    showSimpleBulletin("Couldn't check for updates.");
                }
                if (onDone != null) {
                    onDone.run();
                }
            });
        });
    }

    private static class Result {
        String version;
        String notes;
        String url;
    }

    private static Result fetchLatestRelease() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(API_URL).openConnection();
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(10000);
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK) {
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
            }
            JSONObject json = new JSONObject(sb.toString());
            String tag = json.optString("tag_name", "");
            if (tag.isEmpty()) {
                return null;
            }
            if (tag.charAt(0) == 'v' || tag.charAt(0) == 'V') {
                tag = tag.substring(1);
            }
            Result result = new Result();
            result.version = tag;
            result.notes = json.optString("body", "");
            result.url = json.optString("html_url", REPO_URL + "/releases");
            return result;
        } catch (Exception e) {
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    /** True if candidate (a GitHub release tag) is a newer dot-separated version than current
     * (BuildVars.PIXELGRAM_VERSION). Strips a leading v/V, pads missing trailing segments with
     * 0 so "1.2" == "1.2.0", and - since this is comparing an external tag, not a value we
     * control - falls back to "not newer" (never crashes, never notifies spuriously) if either
     * side doesn't parse as dot-separated integers, logging the bad value. */
    private static boolean isNewerVersion(String candidate, String current) {
        int[] a = parseVersion(candidate);
        int[] b = parseVersion(current);
        if (a == null || b == null) {
            return false;
        }
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int va = i < a.length ? a[i] : 0;
            int vb = i < b.length ? b[i] : 0;
            if (va != vb) {
                return va > vb;
            }
        }
        return false;
    }

    private static int[] parseVersion(String version) {
        if (version == null) {
            return null;
        }
        String s = version.trim();
        if (!s.isEmpty() && (s.charAt(0) == 'v' || s.charAt(0) == 'V')) {
            s = s.substring(1);
        }
        if (s.isEmpty()) {
            FileLog.e("PixelGramUpdateChecker: empty version string");
            return null;
        }
        String[] parts = s.split("\\.");
        int[] nums = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                nums[i] = Integer.parseInt(parts[i].trim());
            } catch (NumberFormatException e) {
                FileLog.e("PixelGramUpdateChecker: not a dot-separated integer version: " + version);
                return null;
            }
        }
        return nums;
    }

    private static boolean isUnmeteredConnection() {
        try {
            ConnectivityManager cm = (ConnectivityManager) ApplicationLoader.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return false;
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                return !cm.isActiveNetworkMetered();
            }
            NetworkInfo info = cm.getActiveNetworkInfo();
            return info != null && info.isConnected() && info.getType() == ConnectivityManager.TYPE_WIFI;
        } catch (Exception e) {
            return false;
        }
    }

    private static void showUpdateBulletin(Result result) {
        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        String notes = result.notes == null ? "" : result.notes.trim();
        if (notes.length() > 200) {
            notes = notes.substring(0, 200) + "…";
        }
        String message = "PixelGram " + result.version + " is available. **View release**";
        if (!notes.isEmpty()) {
            message = message + "\n" + notes;
        }
        Bulletin.SimpleLayout layout = new Bulletin.SimpleLayout(fragment.getParentActivity(), fragment.getResourceProvider());
        layout.textView.setText(AndroidUtilities.replaceSingleTag(message, Theme.key_undo_cancelColor, 0, () -> {
            Browser.openUrl(fragment.getParentActivity(), result.url);
        }));
        layout.textView.setSingleLine(false);
        Bulletin.make(fragment, layout, 6000).show();
    }

    private static void showSimpleBulletin(String message) {
        BaseFragment fragment = LaunchActivity.getLastFragment();
        if (fragment == null || fragment.getParentActivity() == null) {
            return;
        }
        Bulletin.SimpleLayout layout = new Bulletin.SimpleLayout(fragment.getParentActivity(), fragment.getResourceProvider());
        layout.textView.setText(message);
        Bulletin.make(fragment, layout, 3000).show();
    }
}
