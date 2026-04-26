package com.example.phoneaicontrol;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashSet;
import java.util.Locale;

public final class AlarmStateStore {
    private static final String PREFS_NAME = "phone_ai_alarm_state";
    private static final String KEY_MANAGED_ALARMS = "managed_alarms_json";
    private static final int MAX_MANAGED_ALARMS = 50;
    private static final Uri[] SYSTEM_ALARM_URIS = new Uri[]{
            Uri.parse("content://com.android.deskclock/alarm"),
            Uri.parse("content://deskclock.android.com/alarm")
    };

    private AlarmStateStore() {
    }

    public static synchronized void recordManagedAlarm(Context context, JSONObject payload, JSONObject executionResult) {
        try {
            JSONArray alarms = loadManagedAlarms(context);
            JSONObject item = new JSONObject();
            long now = System.currentTimeMillis();
            item.put("source", "phone_ai_control");
            item.put("recorded_epoch_ms", now);
            item.put("hour", payload.optInt("hour", -1));
            item.put("minutes", payload.optInt("minutes", -1));
            String message = payload.optString("message", "");
            item.put("message", message);
            item.put("note", message);
            item.put("vibrate", payload.optBoolean("vibrate", true));
            item.put("skip_ui", payload.optBoolean("skip_ui", true));
            String ringtone = emptyToNull(payload.optString("ringtone", ""));
            item.put("ringtone", ringtone == null ? JSONObject.NULL : ringtone);
            ArrayList<Integer> normalizedDays = normalizeAlarmDays(payload.optJSONArray("days"));
            item.put("calendar_days", calendarDaysJson(normalizedDays));
            item.put("weekdays", weekdayNamesJson(normalizedDays));
            if (executionResult != null) {
                item.put("executed_by", executionResult.optString("executed_by", "phone_ai_control"));
            }
            JSONArray updated = new JSONArray();
            updated.put(item);
            for (int i = 0; i < alarms.length() && updated.length() < MAX_MANAGED_ALARMS; i++) {
                updated.put(alarms.optJSONObject(i));
            }
            saveManagedAlarms(context, updated);
        } catch (Exception ignored) {
        }
    }

    public static synchronized JSONObject exportSnapshot(Context context) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("source", "phone_ai_control_service");
            payload.put("captured_epoch_ms", System.currentTimeMillis());

            JSONArray managed = loadManagedAlarms(context);
            payload.put("managed_alarms", managed);
            payload.put("count_managed", managed.length());

            JSONObject providerState = new JSONObject();
            JSONArray systemAlarms = querySystemAlarms(context, providerState);
            payload.put("system_alarms", systemAlarms);
            payload.put("count_system", systemAlarms.length());
            payload.put("provider_query_status", providerState.optString("provider_query_status", "unavailable"));
            payload.put("provider_uri", providerState.optString("provider_uri", ""));
            String providerError = emptyToNull(providerState.optString("provider_error", ""));
            payload.put("provider_error", providerError == null ? JSONObject.NULL : providerError);
            payload.put("alarm_provider_access_granted", "ok".equals(providerState.optString("provider_query_status", "")));
        } catch (Exception ignored) {
        }
        return payload;
    }

    public static ArrayList<Integer> normalizeAlarmDays(JSONArray days) {
        ArrayList<Integer> result = new ArrayList<Integer>();
        if (days == null) {
            return result;
        }
        LinkedHashSet<Integer> unique = new LinkedHashSet<Integer>();
        for (int i = 0; i < days.length(); i++) {
            Object value = days.opt(i);
            Integer normalized = normalizeSingleDay(value);
            if (normalized != null) {
                unique.add(normalized);
            }
        }
        result.addAll(unique);
        return result;
    }

    public static JSONArray calendarDaysJson(ArrayList<Integer> days) {
        JSONArray array = new JSONArray();
        if (days == null) {
            return array;
        }
        for (Integer value : days) {
            if (value != null) {
                array.put(value.intValue());
            }
        }
        return array;
    }

    public static JSONArray weekdayNamesJson(ArrayList<Integer> days) {
        JSONArray array = new JSONArray();
        if (days == null) {
            return array;
        }
        for (Integer value : days) {
            if (value != null) {
                array.put(calendarDayName(value.intValue()));
            }
        }
        return array;
    }

    private static JSONArray loadManagedAlarms(Context context) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String raw = preferences.getString(KEY_MANAGED_ALARMS, "[]");
        try {
            return new JSONArray(raw);
        } catch (Exception ignored) {
            return new JSONArray();
        }
    }

    private static void saveManagedAlarms(Context context, JSONArray alarms) {
        SharedPreferences preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        preferences.edit().putString(KEY_MANAGED_ALARMS, alarms.toString()).apply();
    }

    private static JSONArray querySystemAlarms(Context context, JSONObject providerState) {
        JSONArray alarms = new JSONArray();
        for (Uri uri : SYSTEM_ALARM_URIS) {
            Cursor cursor = null;
            try {
                cursor = context.getContentResolver().query(uri, null, null, null, null);
                if (cursor == null) {
                    continue;
                }
                providerState.put("provider_query_status", "ok");
                providerState.put("provider_uri", uri.toString());
                while (cursor.moveToNext()) {
                    JSONObject item = new JSONObject();
                    int id = getInt(cursor, "_id", -1);
                    if (id >= 0) {
                        item.put("alarm_id", id);
                    }
                    item.put("hour", getInt(cursor, "hour", getInt(cursor, "hours", -1)));
                    item.put("minutes", getInt(cursor, "minutes", getInt(cursor, "minute", -1)));
                    item.put("enabled", getInt(cursor, "enabled", 0) != 0);
                    item.put("vibrate", getInt(cursor, "vibrate", 0) != 0);

                    String label = firstNonEmpty(
                            getString(cursor, "label"),
                            getString(cursor, "message"),
                            getString(cursor, "alerttitle")
                    );
                    item.put("message", label == null ? JSONObject.NULL : label);
                    item.put("note", label == null ? JSONObject.NULL : label);

                    String alert = firstNonEmpty(getString(cursor, "alert"), getString(cursor, "ringtone"));
                    item.put("ringtone", alert == null ? JSONObject.NULL : alert);

                    int dayMask = getInt(cursor, "daysofweek", -1);
                    item.put("daysofweek_mask", dayMask >= 0 ? dayMask : JSONObject.NULL);
                    ArrayList<Integer> decodedDays = decodeDayMask(dayMask);
                    item.put("calendar_days", calendarDaysJson(decodedDays));
                    item.put("weekdays", weekdayNamesJson(decodedDays));

                    long nextAlert = getLong(cursor, "next_alert_time", getLong(cursor, "time", -1L));
                    item.put("next_trigger_epoch_ms", nextAlert >= 0 ? nextAlert : JSONObject.NULL);
                    alarms.put(item);
                }
                return alarms;
            } catch (SecurityException e) {
                try {
                    providerState.put("provider_query_status", "permission_denied");
                    providerState.put("provider_uri", uri.toString());
                    providerState.put("provider_error", shortenError(e));
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                try {
                    providerState.put("provider_query_status", "error");
                    providerState.put("provider_uri", uri.toString());
                    providerState.put("provider_error", shortenError(e));
                } catch (Exception ignored) {
                }
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        return alarms;
    }

    private static ArrayList<Integer> decodeDayMask(int mask) {
        ArrayList<Integer> days = new ArrayList<Integer>();
        if (mask < 0) {
            return days;
        }
        if ((mask & 0x01) != 0) {
            days.add(Calendar.MONDAY);
        }
        if ((mask & 0x02) != 0) {
            days.add(Calendar.TUESDAY);
        }
        if ((mask & 0x04) != 0) {
            days.add(Calendar.WEDNESDAY);
        }
        if ((mask & 0x08) != 0) {
            days.add(Calendar.THURSDAY);
        }
        if ((mask & 0x10) != 0) {
            days.add(Calendar.FRIDAY);
        }
        if ((mask & 0x20) != 0) {
            days.add(Calendar.SATURDAY);
        }
        if ((mask & 0x40) != 0) {
            days.add(Calendar.SUNDAY);
        }
        return days;
    }

    private static Integer normalizeSingleDay(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number) {
            int number = ((Number) value).intValue();
            return isCalendarDay(number) ? number : null;
        }
        String text = String.valueOf(value).trim().toLowerCase(Locale.US);
        if (text.isEmpty()) {
            return null;
        }
        if ("1".equals(text) || "sun".equals(text) || "sunday".equals(text)) {
            return Calendar.SUNDAY;
        }
        if ("2".equals(text) || "mon".equals(text) || "monday".equals(text)) {
            return Calendar.MONDAY;
        }
        if ("3".equals(text) || "tue".equals(text) || "tuesday".equals(text)) {
            return Calendar.TUESDAY;
        }
        if ("4".equals(text) || "wed".equals(text) || "wednesday".equals(text)) {
            return Calendar.WEDNESDAY;
        }
        if ("5".equals(text) || "thu".equals(text) || "thursday".equals(text)) {
            return Calendar.THURSDAY;
        }
        if ("6".equals(text) || "fri".equals(text) || "friday".equals(text)) {
            return Calendar.FRIDAY;
        }
        if ("7".equals(text) || "sat".equals(text) || "saturday".equals(text)) {
            return Calendar.SATURDAY;
        }
        return null;
    }

    private static boolean isCalendarDay(int day) {
        return day >= Calendar.SUNDAY && day <= Calendar.SATURDAY;
    }

    private static String calendarDayName(int day) {
        switch (day) {
            case Calendar.SUNDAY:
                return "sunday";
            case Calendar.MONDAY:
                return "monday";
            case Calendar.TUESDAY:
                return "tuesday";
            case Calendar.WEDNESDAY:
                return "wednesday";
            case Calendar.THURSDAY:
                return "thursday";
            case Calendar.FRIDAY:
                return "friday";
            case Calendar.SATURDAY:
                return "saturday";
            default:
                return "unknown";
        }
    }

    private static int getInt(Cursor cursor, String column, int defaultValue) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return defaultValue;
        }
        try {
            return cursor.getInt(index);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static long getLong(Cursor cursor, String column, long defaultValue) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return defaultValue;
        }
        try {
            return cursor.getLong(index);
        } catch (Exception ignored) {
            return defaultValue;
        }
    }

    private static String getString(Cursor cursor, String column) {
        int index = cursor.getColumnIndex(column);
        if (index < 0 || cursor.isNull(index)) {
            return null;
        }
        try {
            return emptyToNull(cursor.getString(index));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String firstNonEmpty(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.trim().isEmpty()) {
                return value.trim();
            }
        }
        return null;
    }

    private static String shortenError(Exception e) {
        String message = e.getClass().getSimpleName() + ": " + String.valueOf(e.getMessage());
        if (message.length() > 240) {
            return message.substring(0, 240);
        }
        return message;
    }

    private static String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
