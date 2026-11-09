package org.levimc.launcher.core.crash;

import android.content.Context;
import android.content.SharedPreferences;

public final class GameSessionTracker {

    private static final String PREFS_NAME = "game_session_prefs";
    private static final String KEY_START = "session_start";

    private GameSessionTracker() {
    }

    public static void markStart(Context context) {
        prefs(context).edit().putLong(KEY_START, System.currentTimeMillis()).apply();
    }

    public static long peekStart(Context context) {
        return prefs(context).getLong(KEY_START, 0L);
    }

    public static void clear(Context context) {
        prefs(context).edit().remove(KEY_START).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }
}
