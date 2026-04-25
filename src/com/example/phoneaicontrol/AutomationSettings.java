package com.example.phoneaicontrol;

import android.content.Context;
import android.content.SharedPreferences;

public final class AutomationSettings {
    private static final String PREFS_NAME = "phone_ai_control_settings";
    private static final String KEY_POLL_INTERVAL_SECONDS = "poll_interval_seconds";
    private static final String KEY_RELAY_MODE = "relay_mode";
    private static final int DEFAULT_POLL_INTERVAL_SECONDS = 5;
    private static final int MAX_POLL_INTERVAL_SECONDS = 3600;
    public static final int RELAY_MODE_TRADITIONAL = 0;
    public static final int RELAY_MODE_GITHUB = 1;
    private static final int DEFAULT_RELAY_MODE = RELAY_MODE_TRADITIONAL;

    private AutomationSettings() {
    }

    public static int getPollIntervalSeconds(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return normalizeSeconds(prefs.getInt(KEY_POLL_INTERVAL_SECONDS, DEFAULT_POLL_INTERVAL_SECONDS));
    }

    public static void setPollIntervalSeconds(Context context, int seconds) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_POLL_INTERVAL_SECONDS, normalizeSeconds(seconds)).apply();
    }

    public static long getPollIntervalMs(Context context) {
        int seconds = getPollIntervalSeconds(context);
        if (seconds <= 0) {
            return 0L;
        }
        return seconds * 1000L;
    }

    public static int getRelayMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return normalizeRelayMode(prefs.getInt(KEY_RELAY_MODE, DEFAULT_RELAY_MODE));
    }

    public static void setRelayMode(Context context, int mode) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putInt(KEY_RELAY_MODE, normalizeRelayMode(mode)).apply();
    }

    public static boolean isGitHubRelayMode(Context context) {
        return getRelayMode(context) == RELAY_MODE_GITHUB;
    }

    public static boolean isPollingEnabled(Context context) {
        return getPollIntervalSeconds(context) > 0;
    }

    public static int normalizeSeconds(int seconds) {
        if (seconds <= 0) {
            return 0;
        }
        if (seconds > MAX_POLL_INTERVAL_SECONDS) {
            return MAX_POLL_INTERVAL_SECONDS;
        }
        return seconds;
    }

    public static int getDefaultPollIntervalSeconds() {
        return DEFAULT_POLL_INTERVAL_SECONDS;
    }

    public static int normalizeRelayMode(int mode) {
        return mode == RELAY_MODE_GITHUB ? RELAY_MODE_GITHUB : RELAY_MODE_TRADITIONAL;
    }
}
