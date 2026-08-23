package org.telegram.messenger;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Runtime storage for the Telegram api_id/api_hash pair this build connects with. Every
 * public Telegram-compatible client must register its own pair at https://my.telegram.org -
 * this fork no longer ships one baked in (see BuildVars.APP_ID/APP_HASH, which read from here).
 *
 * Deliberately a separate SharedPreferences file from PixelGramSettings - credentials are a
 * different concern from camera/recording tuning and worth keeping easy to reason about
 * in isolation.
 */
public class ApiCredentials {

    private static final String PREFS_NAME = "api_credentials";
    private static final String KEY_API_ID = "api_id";
    private static final String KEY_API_HASH = "api_hash";

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static boolean hasCredentials() {
        return getApiId() != 0 && getApiHash().length() > 0;
    }

    public static int getApiId() {
        return prefs().getInt(KEY_API_ID, 0);
    }

    public static String getApiHash() {
        return prefs().getString(KEY_API_HASH, "");
    }

    /** Persists the pair and updates BuildVars in-memory immediately (BuildVars.APP_HASH's
     * only consumers are Java-side login flows, so an in-memory update alone is enough for it;
     * BuildVars.APP_ID also feeds a native init call made once per process at startup, so a
     * caller changing api_id still needs to restart the process for the native layer to see it -
     * see ApiCredentialsSetupActivity/PixelGramSettingsActivity's restart-after-save). */
    public static void setCredentials(int apiId, String apiHash) {
        prefs().edit()
                .putInt(KEY_API_ID, apiId)
                .putString(KEY_API_HASH, apiHash)
                .apply();
        BuildVars.APP_ID = apiId;
        BuildVars.APP_HASH = apiHash;
    }
}
