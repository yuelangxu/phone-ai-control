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
    private static final String SELF_PACKAGE = "com.example.phoneaicontrol";
    private static final String SELF_SERVICE_CHANNEL = "phone_ai_control_service";

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
        JSONObject snapshot = sanitizeSnapshot(loadSnapshot(context));
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
            StatusBarNotification[] currentActive = PhoneAiNotificationListener.getCurrentActiveNotifications();
            if (currentActive != null) {
                JSONArray active = new JSONArray();
                for (StatusBarNotification sbn : currentActive) {
                    if (sbn == null || isSelfServiceNotification(sbn)) {
                        continue;
                    }
                    active.put(notificationToJson(sbn, false));
                }
                snapshot.put("active_notifications", active);
                snapshot.put("active_count", active.length());
                snapshot.put("captured_epoch_ms", System.currentTimeMillis());
            }
        } catch (Exception ignored) {
        }
        return snapshot;
    }

    public static synchronized void updateSnapshot(Context context, StatusBarNotification[] activeNotifications, StatusBarNotification eventNotification, boolean removed) {
        JSONObject snapshot = sanitizeSnapshot(loadSnapshot(context));
        try {
            snapshot.put("source", "phone_ai_control_notification_listener");
            snapshot.put("captured_epoch_ms", System.currentTimeMillis());
            snapshot.put("notification_access_granted", hasNotificationAccess(context));

            JSONArray active = new JSONArray();
            if (activeNotifications != null) {
                for (StatusBarNotification sbn : activeNotifications) {
                    if (sbn == null || isSelfServiceNotification(sbn)) {
                        continue;
                    }
                    active.put(notificationToJson(sbn, false));
                }
            }
            snapshot.put("active_notifications", active);
            snapshot.put("active_count", active.length());

            JSONArray previousRecent = snapshot.optJSONArray("recent_notifications");
            JSONArray recent = new JSONArray();
            if (eventNotification != null && !isSelfServiceNotification(eventNotification)) {
                recent.put(notificationToJson(eventNotification, removed));
            }
            if (previousRecent != null) {
                for (int i = 0; i < previousRecent.length() && recent.length() < MAX_RECENT_NOTIFICATIONS; i++) {
                    JSONObject item = previousRecent.optJSONObject(i);
                    if (item == null || isSelfServiceNotification(item)) {
                        continue;
                    }
                    recent.put(item);
                }
            }
            snapshot.put("recent_notifications", recent);
        } catch (Exception ignored) {
        }
        saveSnapshot(context, snapshot);
    }

    public static synchronized void clearSelfServiceNotificationHistory(Context context) {
        saveSnapshot(context, sanitizeSnapshot(loadSnapshot(context)));
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

    private static JSONObject sanitizeSnapshot(JSONObject snapshot) {
        JSONObject safe = snapshot == null ? new JSONObject() : snapshot;
        try {
            JSONArray existingActive = safe.optJSONArray("active_notifications");
            if (existingActive != null) {
                JSONArray filteredActive = new JSONArray();
                for (int i = 0; i < existingActive.length(); i++) {
                    JSONObject item = existingActive.optJSONObject(i);
                    if (item == null || isSelfServiceNotification(item)) {
                        continue;
                    }
                    filteredActive.put(item);
                }
                safe.put("active_notifications", filteredActive);
                safe.put("active_count", filteredActive.length());
            }

            JSONArray existingRecent = safe.optJSONArray("recent_notifications");
            if (existingRecent != null) {
                JSONArray filteredRecent = new JSONArray();
                for (int i = 0; i < existingRecent.length() && filteredRecent.length() < MAX_RECENT_NOTIFICATIONS; i++) {
                    JSONObject item = existingRecent.optJSONObject(i);
                    if (item == null || isSelfServiceNotification(item)) {
                        continue;
                    }
                    filteredRecent.put(item);
                }
                safe.put("recent_notifications", filteredRecent);
            }
        } catch (Exception ignored) {
        }
        return safe;
    }

    private static boolean isSelfServiceNotification(StatusBarNotification sbn) {
        if (sbn == null) {
            return false;
        }
        try {
            if (!SELF_PACKAGE.equals(sbn.getPackageName())) {
                return false;
            }
            Notification notification = sbn.getNotification();
            if (notification == null) {
                return false;
            }
            return Build.VERSION.SDK_INT >= 26 && SELF_SERVICE_CHANNEL.equals(notification.getChannelId());
        } catch (Exception ignored) {
            return false;
        }
    }

    private static boolean isSelfServiceNotification(JSONObject item) {
        if (item == null) {
            return false;
        }
        try {
            return SELF_PACKAGE.equals(item.optString("package", ""))
                    && SELF_SERVICE_CHANNEL.equals(item.optString("channel_id", ""));
        } catch (Exception ignored) {
            return false;
        }
    }
}
