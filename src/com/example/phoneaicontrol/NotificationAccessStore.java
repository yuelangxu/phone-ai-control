package com.example.phoneaicontrol;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

public final class NotificationAccessStore {
    private static final String PREFS_NAME = "phone_ai_notification_state";
    private static final String KEY_SNAPSHOT_JSON = "snapshot_json";
    private static final int MAX_RECENT_NOTIFICATIONS = 40;

    private NotificationAccessStore() {
    }

    public static boolean hasNotificationAccess(Context context) {
        try {
            String enabled = Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
            if (enabled == null || enabled.trim().isEmpty()) {
                return false;
            }
            ComponentName listener = new ComponentName(context, PhoneAiNotificationListener.class);
            String full = listener.flattenToString();
            String shortName = listener.flattenToShortString();
            return enabled.contains(full) || enabled.contains(shortName) || enabled.contains(context.getPackageName());
        } catch (Exception ignored) {
            return false;
        }
    }

    public static synchronized JSONObject exportSnapshot(Context context) {
        JSONObject snapshot = loadSnapshot(context);
        try {
            snapshot.put("notification_access_granted", hasNotificationAccess(context));
            if (!snapshot.has("captured_epoch_ms")) {
                snapshot.put("captured_epoch_ms", System.currentTimeMillis());
            }
            if (!snapshot.has("source")) {
                snapshot.put("source", "phone_ai_control_notification_listener");
            }
            if (!snapshot.has("active_notifications")) {
                snapshot.put("active_notifications", new JSONArray());
            }
            if (!snapshot.has("recent_notifications")) {
                snapshot.put("recent_notifications", new JSONArray());
            }
            if (!snapshot.has("active_count")) {
                snapshot.put("active_count", snapshot.optJSONArray("active_notifications") == null ? 0 : snapshot.optJSONArray("active_notifications").length());
            }
        } catch (Exception ignored) {
        }
        return snapshot;
    }

    public static synchronized void updateSnapshot(Context context, StatusBarNotification[] activeNotifications, StatusBarNotification eventNotification, boolean removed) {
        JSONObject snapshot = loadSnapshot(context);
        try {
            snapshot.put("source", "phone_ai_control_notification_listener");
            snapshot.put("captured_epoch_ms", System.currentTimeMillis());
            snapshot.put("notification_access_granted", hasNotificationAccess(context));

            JSONArray active = new JSONArray();
            if (activeNotifications != null) {
                for (StatusBarNotification sbn : activeNotifications) {
                    if (sbn == null) {
                        continue;
                    }
                    active.put(notificationToJson(sbn, false));
                }
            }
            snapshot.put("active_notifications", active);
            snapshot.put("active_count", active.length());

            JSONArray previousRecent = snapshot.optJSONArray("recent_notifications");
            JSONArray recent = new JSONArray();
            if (eventNotification != null) {
                recent.put(notificationToJson(eventNotification, removed));
            }
            if (previousRecent != null) {
                for (int i = 0; i < previousRecent.length() && recent.length() < MAX_RECENT_NOTIFICATIONS; i++) {
                    recent.put(previousRecent.optJSONObject(i));
                }
            }
            snapshot.put("recent_notifications", recent);
        } catch (Exception ignored) {
        }
        saveSnapshot(context, snapshot);
    }

    private static JSONObject notificationToJson(StatusBarNotification sbn, boolean removed) {
        JSONObject json = new JSONObject();
        try {
            Notification notification = sbn.getNotification();
            Bundle extras = notification == null ? null : notification.extras;
            json.put("package", sbn.getPackageName());
            json.put("id", sbn.getId());
            json.put("tag", sbn.getTag() == null ? JSONObject.NULL : sbn.getTag());
            json.put("key", sbn.getKey());
            json.put("post_time_epoch_ms", sbn.getPostTime());
            json.put("removed", removed);
            json.put("clearable", sbn.isClearable());
            json.put("ongoing", notification != null && (notification.flags & Notification.FLAG_ONGOING_EVENT) != 0);
            json.put("category", notification == null || notification.category == null ? JSONObject.NULL : notification.category);
            if (Build.VERSION.SDK_INT >= 26 && notification != null) {
                json.put("channel_id", notification.getChannelId() == null ? JSONObject.NULL : notification.getChannelId());
            }
            json.put("title", extrasToString(extras, Notification.EXTRA_TITLE));
            json.put("text", extrasToString(extras, Notification.EXTRA_TEXT));
            json.put("subtext", extrasToString(extras, Notification.EXTRA_SUB_TEXT));
            json.put("big_text", extrasToString(extras, Notification.EXTRA_BIG_TEXT));
        } catch (Exception ignored) {
        }
        return json;
    }

    private static String extrasToString(Bundle extras, String key) {
        if (extras == null || key == null) {
            return null;
        }
        try {
            CharSequence value = extras.getCharSequence(key);
            if (value == null) {
                return null;
            }
            String text = value.toString().trim();
            return text.isEmpty() ? null : text;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private static JSONObject loadSnapshot(Context context) {
        try {
            String raw = prefs(context).getString(KEY_SNAPSHOT_JSON, "");
            if (raw == null || raw.trim().isEmpty()) {
                return new JSONObject();
            }
            return new JSONObject(raw);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }

    private static void saveSnapshot(Context context, JSONObject snapshot) {
        prefs(context).edit().putString(KEY_SNAPSHOT_JSON, snapshot == null ? "{}" : snapshot.toString()).apply();
    }
}
