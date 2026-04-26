package com.example.phoneaicontrol;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.CallLog;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
import android.provider.MediaStore;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class AutomationLoopService extends Service {
    private static final String ACTION_START = "com.example.phoneaicontrol.action.START_AUTOMATION_LOOP";
    private static final String ACTION_STOP = "com.example.phoneaicontrol.action.STOP_AUTOMATION_LOOP";
    private static final String RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND";
    private static final String RUN_COMMAND_PATH_EXTRA = "com.termux.RUN_COMMAND_PATH";
    private static final String RUN_COMMAND_ARGUMENTS_EXTRA = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String RUN_COMMAND_WORKDIR_EXTRA = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String RUN_COMMAND_BACKGROUND_EXTRA = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String RUN_COMMAND_PENDING_INTENT_EXTRA = "com.termux.RUN_COMMAND_PENDING_INTENT";
    private static final String DEFAULT_LOCAL_API = "http://127.0.0.1:8787";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String PHONE_AI_RUNTIME_FILE = "/storage/emulated/0/Android/media/com.example.phoneaicontrol/runtime.json";
    private static final String NOTIFICATION_CHANNEL_ID = "phone_ai_control_alerts";
    private static final String SERVICE_CHANNEL_ID = "phone_ai_control_service";
    private static final int SERVICE_NOTIFICATION_ID = 4101;
    private static final long AUTO_INSTALL_MIN_INTERVAL_MS = 2000L;
    private static final long AUTO_DEVICE_ACTION_MIN_INTERVAL_MS = 1500L;
    private static final long BATTERY_PUSH_MIN_INTERVAL_MS = 15000L;
    private static final long USAGE_PUSH_MIN_INTERVAL_MS = 15000L;
    private static final long NOTIFICATION_PUSH_MIN_INTERVAL_MS = 10000L;
    private static final long CONTACTS_PUSH_MIN_INTERVAL_MS = 60000L;
    private static final long MISSED_CALLS_PUSH_MIN_INTERVAL_MS = 30000L;
    private static final long PUBLIC_HEALTH_CHECK_MIN_INTERVAL_MS = 15000L;
    private static final long PUBLIC_RECONNECT_MIN_INTERVAL_MS = 45000L;
    private static final int PUBLIC_PROBE_TIMEOUT_MS = 5000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable pollRunnable = new Runnable() {
        @Override
        public void run() {
            long intervalMs = AutomationSettings.getPollIntervalMs(AutomationLoopService.this);
            if (intervalMs <= 0L) {
                stopForeground(true);
                stopSelf();
                return;
            }
            pollOnce();
            handler.postDelayed(this, intervalMs);
        }
    };

    private final BroadcastReceiver commandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!TokenResultService.BROADCAST_COMMAND_RESULT.equals(intent.getAction())) {
                return;
            }
            String actionName = intent.getStringExtra(TokenResultService.EXTRA_ACTION_NAME);
            if (!TokenResultService.ACTION_DISCOVER_LOCAL_API.equals(actionName)) {
                return;
            }
            discoveryInFlight = false;
            String stdout = intent.getStringExtra(TokenResultService.EXTRA_STDOUT);
            if (stdout == null) {
                return;
            }
            String discovered = stdout.replaceAll("[^0-9]", "");
            if (discovered.length() == 4) {
                currentLocalApiBase = "http://127.0.0.1:" + discovered;
                updateServiceNotification();
            }
        }
    };

    private String currentLocalApiBase = DEFAULT_LOCAL_API;
    private boolean discoveryInFlight = false;
    private long lastAutoInstallKickMs = 0L;
    private boolean autoInstallRequestInFlight = false;
    private long lastAutoDeviceActionKickMs = 0L;
    private boolean autoDeviceActionRequestInFlight = false;
    private long lastBatteryPushMs = 0L;
    private boolean batteryPushInFlight = false;
    private long lastUsagePushMs = 0L;
    private boolean usagePushInFlight = false;
    private long lastNotificationPushMs = 0L;
    private boolean notificationPushInFlight = false;
    private long lastContactsPushMs = 0L;
    private boolean contactsPushInFlight = false;
    private long lastMissedCallsPushMs = 0L;
    private boolean missedCallsPushInFlight = false;
    private long lastPublicHealthCheckMs = 0L;
    private long lastPublicReconnectKickMs = 0L;
    private boolean publicHealthCheckInFlight = false;

    private static final class PublicTunnelProbeResult {
        final boolean reachable;
        final int statusCode;
        final String detail;

        PublicTunnelProbeResult(boolean reachable, int statusCode, String detail) {
            this.reachable = reachable;
            this.statusCode = statusCode;
            this.detail = detail == null ? "" : detail;
        }

        boolean shouldReconnect() {
            return !reachable && (statusCode < 0 || statusCode >= 400);
        }
    }

    public static void start(Context context) {
        if (!AutomationSettings.isPollingEnabled(context)) {
            stop(context);
            return;
        }
        Intent intent = new Intent(context, AutomationLoopService.class);
        intent.setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= 26) {
            context.startForegroundService(intent);
        } else {
            context.startService(intent);
        }
    }

    public static void stop(Context context) {
        try {
            Intent intent = new Intent(context, AutomationLoopService.class);
            intent.setAction(ACTION_STOP);
            context.startService(intent);
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        ensureNotificationChannels();
        registerReceiver(commandReceiver, new IntentFilter(TokenResultService.BROADCAST_COMMAND_RESULT));
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) {
            handler.removeCallbacks(pollRunnable);
            stopForeground(true);
            stopSelfResult(startId);
            return START_NOT_STICKY;
        }
        if (!AutomationSettings.isPollingEnabled(this)) {
            handler.removeCallbacks(pollRunnable);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(SERVICE_NOTIFICATION_ID, buildServiceNotification());
        handler.removeCallbacks(pollRunnable);
        handler.post(pollRunnable);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(pollRunnable);
        try {
            unregisterReceiver(commandReceiver);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void pollOnce() {
        maybeAutoHealPublicTunnel();
        pushBatteryStatusIfNeeded();
        pushUsageSnapshotIfNeeded();
        pushNotificationsSnapshotIfNeeded();
        pushContactsSnapshotIfNeeded();
        pushMissedCallsSnapshotIfNeeded();
        maybeAutoHandleInstallRequests();
        maybeAutoHandleDeviceActions();
    }

    private void ensureNotificationChannels() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        if (manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID) == null) {
            NotificationChannel alertChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Phone AI Control",
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            alertChannel.setDescription("Notifications created by the Phone AI Control API bridge.");
            manager.createNotificationChannel(alertChannel);
        }
        if (manager.getNotificationChannel(SERVICE_CHANNEL_ID) == null) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    SERVICE_CHANNEL_ID,
                    "Phone AI Control Background",
                    NotificationManager.IMPORTANCE_LOW
            );
            serviceChannel.setDescription("Keeps Phone AI Control polling the local API for device actions.");
            serviceChannel.setShowBadge(false);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification buildServiceNotification() {
        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                8101,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        int intervalSeconds = AutomationSettings.getPollIntervalSeconds(this);
        String content = "Watching local API";
        if (currentLocalApiBase != null && !currentLocalApiBase.trim().isEmpty()) {
            content = "Watching " + currentLocalApiBase;
        }
        if (intervalSeconds > 0) {
            content += " every " + intervalSeconds + "s";
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, SERVICE_CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle("Phone AI Control active")
                .setContentText(content)
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setContentIntent(openPendingIntent)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(false);
        if (Build.VERSION.SDK_INT < 26) {
            builder.setPriority(Notification.PRIORITY_MIN);
        }
        return builder.build();
    }

    private void updateServiceNotification() {
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        manager.notify(SERVICE_NOTIFICATION_ID, buildServiceNotification());
    }

    private synchronized String resolveLocalApiBase(int timeoutMs) throws Exception {
        java.util.LinkedHashSet<String> candidates = new java.util.LinkedHashSet<String>();
        if (currentLocalApiBase != null && !currentLocalApiBase.trim().isEmpty()) {
            candidates.add(currentLocalApiBase.trim());
        }
        JSONObject runtime = readRuntimeState();
        if (runtime != null) {
            String runtimeUrl = runtime.optString("local_api_url", "").trim();
            int runtimePort = runtime.optInt("local_port", 0);
            if (!runtimeUrl.isEmpty()) {
                candidates.add(runtimeUrl);
            }
            if (runtimePort >= 1000 && runtimePort <= 9999) {
                candidates.add("http://127.0.0.1:" + runtimePort);
            }
        }
        candidates.add(DEFAULT_LOCAL_API);
        Exception lastError = null;
        for (String candidate : candidates) {
            try {
                JSONObject health = getJson(candidate + "/healthz", timeoutMs);
                if (health.optBoolean("ok", false)) {
                    currentLocalApiBase = candidate;
                    updateServiceNotification();
                    return candidate;
                }
            } catch (Exception e) {
                lastError = e;
            }
        }
        triggerPortDiscovery();
        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("Local API unavailable");
    }

    private void maybeAutoHealPublicTunnel() {
        long now = System.currentTimeMillis();
        if (publicHealthCheckInFlight || now - lastPublicHealthCheckMs < PUBLIC_HEALTH_CHECK_MIN_INTERVAL_MS) {
            return;
        }
        publicHealthCheckInFlight = true;
        lastPublicHealthCheckMs = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                JSONObject health = null;
                boolean publicReachable = false;
                String publicProbeDetail = "";
                String localStatus = "Offline";
                String localApiBase = currentLocalApiBase;
                try {
                    localApiBase = resolveLocalApiBase(1500);
                    health = getJson(localApiBase + "/healthz", 2500);
                    localStatus = health.optBoolean("ok") ? "Online" : "Unexpected response";
                    boolean enabled = health.optBoolean("public_enabled", false);
                    String publicUrl = normalizePublicUrl(health.optString("public_url", ""));
                    if (!enabled || !publicUrl.startsWith("https://")) {
                        publicReachable = false;
                        publicProbeDetail = enabled ? "Public URL missing while tunnel reports enabled." : "Public exposure disabled.";
                    } else {
                        PublicTunnelProbeResult probe = probePublicTunnel(publicUrl, PUBLIC_PROBE_TIMEOUT_MS);
                        publicReachable = probe.reachable;
                        publicProbeDetail = probe.detail;
                        if (probe.shouldReconnect()) {
                            requestPublicTunnelReconnectFromService(probe.detail);
                        }
                    }
                } catch (Exception e) {
                    publicReachable = false;
                    publicProbeDetail = e.getClass().getSimpleName() + ": " + e.getMessage();
                    localStatus = "Offline";
                } finally {
                    if (GitHubRelaySync.hasLocalRelayConfig(AutomationLoopService.this)) {
                        try {
                            JSONObject relayState = GitHubRelaySync.buildDeviceState(
                                    AutomationLoopService.this,
                                    "automation_loop_service",
                                    localApiBase,
                                    "Online".equals(localStatus),
                                    localStatus,
                                    health,
                                    publicReachable,
                                    publicProbeDetail,
                                    collectPermissionState()
                            );
                            GitHubRelaySync.maybeSyncCurrentDevice(AutomationLoopService.this, relayState, false);
                        } catch (Exception ignored) {
                        }
                    }
                    publicHealthCheckInFlight = false;
                }
            }
        }).start();
    }

    private JSONObject readRuntimeState() {
        try {
            File runtimeFile = new File(PHONE_AI_RUNTIME_FILE);
            if (!runtimeFile.exists() || !runtimeFile.isFile()) {
                return null;
            }
            byte[] raw = readFileUpTo(runtimeFile, 16 * 1024);
            return new JSONObject(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception ignored) {
            return null;
        }
    }

    private void triggerPortDiscovery() {
        if (discoveryInFlight) {
            return;
        }
        discoveryInFlight = true;
        runManagedTermuxCommand(
                "cat " + TERMUX_HOME + "/ai-phone-api/port.txt 2>/dev/null || true",
                TokenResultService.ACTION_DISCOVER_LOCAL_API
        );
    }

    private void runManagedTermuxCommand(String command, String actionName) {
        try {
            Intent resultIntent = new Intent(this, TokenResultService.class);
            int executionId = TokenResultService.getNextExecutionId();
            resultIntent.putExtra(TokenResultService.EXTRA_EXECUTION_ID, executionId);
            resultIntent.putExtra(TokenResultService.EXTRA_ACTION_NAME, actionName);
            PendingIntent pendingIntent = PendingIntent.getService(
                    this,
                    executionId,
                    resultIntent,
                    PendingIntent.FLAG_ONE_SHOT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0)
            );

            Intent intent = new Intent();
            intent.setClassName("com.termux", "com.termux.app.RunCommandService");
            intent.setAction(RUN_COMMAND_ACTION);
            intent.putExtra(RUN_COMMAND_PATH_EXTRA, TERMUX_BASH);
            intent.putExtra(RUN_COMMAND_ARGUMENTS_EXTRA, new String[]{"-lc", command});
            intent.putExtra(RUN_COMMAND_WORKDIR_EXTRA, TERMUX_HOME);
            intent.putExtra(RUN_COMMAND_BACKGROUND_EXTRA, true);
            intent.putExtra(RUN_COMMAND_PENDING_INTENT_EXTRA, pendingIntent);
            startService(intent);
        } catch (Exception ignored) {
            discoveryInFlight = false;
        }
    }

    private void requestPublicTunnelReconnectFromService(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastPublicReconnectKickMs < PUBLIC_RECONNECT_MIN_INTERVAL_MS) {
            return;
        }
        lastPublicReconnectKickMs = now;
        runManagedTermuxCommand(
                TERMUX_HOME + "/ai-phone-api/start-phone-ai-public.sh",
                TokenResultService.ACTION_START_PUBLIC_API
        );
    }

    private JSONObject getJson(String rawUrl, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(rawUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "PhoneAIControl/1.0");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("bypass-tunnel-reminder", "1");
        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8
        ));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        if (code < 200 || code >= 400) {
            throw new IllegalStateException("HTTP " + code + ": " + sb.toString());
        }
        return new JSONObject(sb.toString());
    }

    private PublicTunnelProbeResult probePublicTunnel(String publicUrl, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(publicUrl + "/healthz").openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestProperty("User-Agent", "PhoneAIControl/1.0");
            conn.setRequestProperty("bypass-tunnel-reminder", "1");
            int code = conn.getResponseCode();
            InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
            String body = "";
            if (stream != null) {
                body = new String(readStreamFully(stream), StandardCharsets.UTF_8).trim();
            }
            if (code == 200) {
                try {
                    JSONObject payload = new JSONObject(body);
                    if (payload.optBoolean("ok", true)) {
                        return new PublicTunnelProbeResult(true, code, "HTTP 200");
                    }
                } catch (Exception ignored) {
                    return new PublicTunnelProbeResult(true, code, "HTTP 200");
                }
            }
            return new PublicTunnelProbeResult(false, code, summarizeProbeFailure(code, body));
        } catch (Exception e) {
            return new PublicTunnelProbeResult(false, -1, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private String summarizeProbeFailure(int statusCode, String body) {
        String normalized = body == null ? "" : body.replace('\n', ' ').replace('\r', ' ').trim();
        if (normalized.length() > 160) {
            normalized = normalized.substring(0, 160) + "...";
        }
        if (normalized.isEmpty()) {
            return "HTTP " + statusCode;
        }
        return "HTTP " + statusCode + ": " + normalized;
    }

    private JSONObject requestJson(String method, String rawUrl, String body, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(rawUrl).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "PhoneAIControl/1.0");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("bypass-tunnel-reminder", "1");
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] raw = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(raw.length));
            OutputStream stream = conn.getOutputStream();
            try {
                stream.write(raw);
                stream.flush();
            } finally {
                stream.close();
            }
        }
        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8
        ));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        if (code < 200 || code >= 400) {
            throw new IllegalStateException("HTTP " + code + ": " + sb.toString());
        }
        return new JSONObject(sb.toString());
    }

    private String normalizePublicUrl(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.startsWith("http://")) {
            trimmed = "https://" + trimmed.substring("http://".length());
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private byte[] downloadBinary(String rawUrl, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(rawUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "PhoneAIControl/1.0");
        conn.setRequestProperty("Accept", "*/*");
        int code = conn.getResponseCode();
        InputStream stream = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        byte[] raw = readStreamFully(stream);
        if (code < 200 || code >= 400) {
            throw new IllegalStateException("HTTP " + code + ": " + new String(raw, StandardCharsets.UTF_8));
        }
        return raw;
    }

    private byte[] readStreamFully(InputStream stream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = stream.read(chunk)) >= 0) {
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            try {
                stream.close();
            } catch (Exception ignored) {
            }
            buffer.close();
        }
    }

    private byte[] readFileUpTo(File target, int maxBytes) throws Exception {
        InputStream stream = new java.io.FileInputStream(target);
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        try {
            byte[] chunk = new byte[8192];
            int remaining = maxBytes;
            while (remaining > 0) {
                int read = stream.read(chunk, 0, Math.min(chunk.length, remaining));
                if (read < 0) {
                    break;
                }
                buffer.write(chunk, 0, read);
                remaining -= read;
            }
            return buffer.toByteArray();
        } finally {
            stream.close();
            buffer.close();
        }
    }

    private Uri writeApkToDownloads(String apkName, byte[] raw) throws Exception {
        String displayName = apkName == null || apkName.trim().isEmpty() ? "PhoneAIInstall.apk" : apkName.trim();
        if (!displayName.toLowerCase().endsWith(".apk")) {
            displayName += ".apk";
        }
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, displayName);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive");
            values.put(MediaStore.Downloads.IS_PENDING, 1);
            Uri collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
            Uri uri = getContentResolver().insert(collection, values);
            if (uri == null) {
                throw new IllegalStateException("Could not create Downloads entry");
            }
            OutputStream stream = getContentResolver().openOutputStream(uri, "w");
            if (stream == null) {
                throw new IllegalStateException("Could not open Downloads output stream");
            }
            try {
                stream.write(raw);
                stream.flush();
            } finally {
                stream.close();
            }
            ContentValues finalizeValues = new ContentValues();
            finalizeValues.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, finalizeValues, null, null);
            return uri;
        }
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (downloads != null && !downloads.exists() && !downloads.mkdirs() && !downloads.exists()) {
            throw new IllegalStateException("Could not create Downloads directory");
        }
        File out = new File(downloads, displayName);
        OutputStream stream = new java.io.FileOutputStream(out, false);
        try {
            stream.write(raw);
            stream.flush();
        } finally {
            stream.close();
        }
        return Uri.fromFile(out);
    }

    private String sha256Hex(byte[] raw) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return hexEncode(digest.digest(raw));
    }

    private String hexEncode(byte[] hash) {
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            int value = b & 0xff;
            if (value < 16) {
                sb.append('0');
            }
            sb.append(Integer.toHexString(value));
        }
        return sb.toString();
    }

    private void pushBatteryStatusIfNeeded() {
        long now = System.currentTimeMillis();
        if (batteryPushInFlight || now - lastBatteryPushMs < BATTERY_PUSH_MIN_INTERVAL_MS) {
            return;
        }
        batteryPushInFlight = true;
        lastBatteryPushMs = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject payload = collectBatteryStatus();
                    requestJson("POST", resolveLocalApiBase(1500) + "/v1/local/device/battery", payload.toString(), 5000);
                } catch (Exception ignored) {
                } finally {
                    batteryPushInFlight = false;
                }
            }
        }).start();
    }

    private void pushUsageSnapshotIfNeeded() {
        long now = System.currentTimeMillis();
        if (usagePushInFlight || now - lastUsagePushMs < USAGE_PUSH_MIN_INTERVAL_MS) {
            return;
        }
        usagePushInFlight = true;
        lastUsagePushMs = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject payload = collectUsageSnapshot();
                    requestJson("POST", resolveLocalApiBase(1500) + "/v1/local/device/usage", payload.toString(), 5000);
                } catch (Exception ignored) {
                } finally {
                    usagePushInFlight = false;
                }
            }
        }).start();
    }

    private void pushNotificationsSnapshotIfNeeded() {
        long now = System.currentTimeMillis();
        if (notificationPushInFlight || now - lastNotificationPushMs < NOTIFICATION_PUSH_MIN_INTERVAL_MS) {
            return;
        }
        notificationPushInFlight = true;
        lastNotificationPushMs = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject payload = collectNotificationsSnapshot();
                    requestJson("POST", resolveLocalApiBase(1500) + "/v1/local/device/notifications", payload.toString(), 5000);
                } catch (Exception ignored) {
                } finally {
                    notificationPushInFlight = false;
                }
            }
        }).start();
    }

    private void pushContactsSnapshotIfNeeded() {
        long now = System.currentTimeMillis();
        if (contactsPushInFlight || now - lastContactsPushMs < CONTACTS_PUSH_MIN_INTERVAL_MS) {
            return;
        }
        contactsPushInFlight = true;
        lastContactsPushMs = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject payload = collectContactsSnapshot();
                    requestJson("POST", resolveLocalApiBase(1500) + "/v1/local/device/contacts", payload.toString(), 5000);
                } catch (Exception ignored) {
                } finally {
                    contactsPushInFlight = false;
                }
            }
        }).start();
    }

    private void pushMissedCallsSnapshotIfNeeded() {
        long now = System.currentTimeMillis();
        if (missedCallsPushInFlight || now - lastMissedCallsPushMs < MISSED_CALLS_PUSH_MIN_INTERVAL_MS) {
            return;
        }
        missedCallsPushInFlight = true;
        lastMissedCallsPushMs = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject payload = collectMissedCallsSnapshot();
                    requestJson("POST", resolveLocalApiBase(1500) + "/v1/local/device/missed-calls", payload.toString(), 5000);
                } catch (Exception ignored) {
                } finally {
                    missedCallsPushInFlight = false;
                }
            }
        }).start();
    }

    private JSONObject collectBatteryStatus() throws Exception {
        Intent batteryIntent = registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (batteryIntent == null) {
            throw new IllegalStateException("Battery broadcast unavailable");
        }
        int level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
        int scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
        int status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN);
        int plugged = batteryIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
        int temperature = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Integer.MIN_VALUE);
        int voltage = batteryIntent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Integer.MIN_VALUE);

        JSONObject payload = new JSONObject();
        payload.put("level", level);
        payload.put("scale", scale);
        payload.put("percent", level >= 0 && scale > 0 ? (level * 100.0d) / scale : JSONObject.NULL);
        payload.put("status", batteryStatusLabel(status));
        payload.put("plugged", batteryPluggedLabel(plugged));
        payload.put("present", batteryIntent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, true));
        payload.put("technology", emptyToNull(batteryIntent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)));
        payload.put("temperature_c", temperature == Integer.MIN_VALUE ? JSONObject.NULL : (temperature / 10.0d));
        payload.put("voltage_mv", voltage == Integer.MIN_VALUE ? JSONObject.NULL : voltage);
        payload.put("source", "phone_ai_control_service");
        payload.put("captured_epoch_ms", System.currentTimeMillis());
        return payload;
    }

    private boolean hasUsageAccess() {
        if (Build.VERSION.SDK_INT < 21) {
            return false;
        }
        try {
            AppOpsManager appOps = (AppOpsManager) getSystemService(Context.APP_OPS_SERVICE);
            if (appOps == null) {
                return false;
            }
            int mode = appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, android.os.Process.myUid(), getPackageName());
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Exception ignored) {
            return false;
        }
    }

    private JSONObject collectUsageSnapshot() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("source", "phone_ai_control_service");
        payload.put("captured_epoch_ms", System.currentTimeMillis());
        payload.put("usage_access_granted", hasUsageAccess());
        payload.put("permission_state", collectPermissionState());

        ActivityManager activityManager = (ActivityManager) getSystemService(Context.ACTIVITY_SERVICE);
        if (activityManager != null) {
            ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
            activityManager.getMemoryInfo(memoryInfo);
            JSONObject memory = new JSONObject();
            memory.put("avail_mem_bytes", memoryInfo.availMem);
            memory.put("total_mem_bytes", memoryInfo.totalMem);
            memory.put("threshold_bytes", memoryInfo.threshold);
            memory.put("low_memory", memoryInfo.lowMemory);
            payload.put("memory", memory);
        }

        if (!hasUsageAccess() || Build.VERSION.SDK_INT < 21) {
            return payload;
        }

        UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usageStatsManager == null) {
            return payload;
        }

        long end = System.currentTimeMillis();
        long start = end - (15L * 60L * 1000L);
        UsageEvents events = usageStatsManager.queryEvents(start, end);
        UsageEvents.Event event = new UsageEvents.Event();
        String foregroundPackage = null;
        String foregroundClass = null;
        String foregroundEvent = null;
        long foregroundAt = 0L;
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            boolean isForeground = type == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || (Build.VERSION.SDK_INT >= 29 && type == UsageEvents.Event.ACTIVITY_RESUMED);
            if (!isForeground) {
                continue;
            }
            foregroundPackage = event.getPackageName();
            foregroundClass = event.getClassName();
            foregroundEvent = usageEventLabel(type);
            foregroundAt = event.getTimeStamp();
        }
        if (foregroundPackage != null && !foregroundPackage.isEmpty()) {
            JSONObject foreground = new JSONObject();
            foreground.put("package", foregroundPackage);
            foreground.put("class", foregroundClass == null ? JSONObject.NULL : foregroundClass);
            foreground.put("event", foregroundEvent == null ? JSONObject.NULL : foregroundEvent);
            foreground.put("event_epoch_ms", foregroundAt);
            payload.put("foreground", foreground);
        }

        List<UsageStats> stats = usageStatsManager.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, end);
        if (stats != null && !stats.isEmpty()) {
            Collections.sort(stats, new Comparator<UsageStats>() {
                @Override
                public int compare(UsageStats left, UsageStats right) {
                    return Long.compare(right.getLastTimeUsed(), left.getLastTimeUsed());
                }
            });
            JSONArray recent = new JSONArray();
            int added = 0;
            for (UsageStats stat : stats) {
                if (stat == null || stat.getPackageName() == null || stat.getPackageName().isEmpty()) {
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("package", stat.getPackageName());
                item.put("last_time_used_epoch_ms", stat.getLastTimeUsed());
                item.put("total_time_in_foreground_ms", stat.getTotalTimeInForeground());
                recent.put(item);
                added++;
                if (added >= 10) {
                    break;
                }
            }
            payload.put("recent_packages", recent);
        }

        return payload;
    }

    private JSONObject collectNotificationsSnapshot() {
        JSONObject payload = NotificationAccessStore.exportSnapshot(this);
        try {
            payload.put("source", "phone_ai_control_service");
            payload.put("captured_epoch_ms", System.currentTimeMillis());
            payload.put("permission_state", collectPermissionState());
        } catch (Exception ignored) {
        }
        return payload;
    }

    private JSONObject collectContactsSnapshot() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("source", "phone_ai_control_service");
            payload.put("captured_epoch_ms", System.currentTimeMillis());
            payload.put("contacts_access_granted", hasContactsAccess());
            payload.put("permission_state", collectPermissionState());
            JSONArray contacts = new JSONArray();
            int total = 0;
            if (hasContactsAccess()) {
                Cursor cursor = getContentResolver().query(
                        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                        new String[]{
                                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                                ContactsContract.CommonDataKinds.Phone.NUMBER,
                                ContactsContract.CommonDataKinds.Phone.STARRED
                        },
                        null,
                        null,
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE NOCASE ASC"
                );
                if (cursor != null) {
                    try {
                        int displayNameIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME);
                        int numberIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER);
                        int starredIdx = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.STARRED);
                        while (cursor.moveToNext()) {
                            total++;
                            if (contacts.length() >= 200) {
                                continue;
                            }
                            JSONObject item = new JSONObject();
                            item.put("display_name", displayNameIdx >= 0 ? emptyToNull(cursor.getString(displayNameIdx)) : JSONObject.NULL);
                            item.put("number", numberIdx >= 0 ? emptyToNull(cursor.getString(numberIdx)) : JSONObject.NULL);
                            item.put("starred", starredIdx >= 0 && cursor.getInt(starredIdx) != 0);
                            contacts.put(item);
                        }
                    } finally {
                        cursor.close();
                    }
                }
            }
            payload.put("count_total", total);
            payload.put("contacts", contacts);
        } catch (Exception ignored) {
        }
        return payload;
    }

    private JSONObject collectMissedCallsSnapshot() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("source", "phone_ai_control_service");
            payload.put("captured_epoch_ms", System.currentTimeMillis());
            payload.put("call_log_access_granted", hasCallLogAccess());
            payload.put("permission_state", collectPermissionState());
            JSONArray calls = new JSONArray();
            int total = 0;
            if (hasCallLogAccess()) {
                Cursor cursor = getContentResolver().query(
                        CallLog.Calls.CONTENT_URI,
                        new String[]{
                                CallLog.Calls.NUMBER,
                                CallLog.Calls.CACHED_NAME,
                                CallLog.Calls.DATE,
                                CallLog.Calls.DURATION,
                                CallLog.Calls.IS_READ,
                                CallLog.Calls.TYPE
                        },
                        CallLog.Calls.TYPE + "=?",
                        new String[]{String.valueOf(CallLog.Calls.MISSED_TYPE)},
                        CallLog.Calls.DATE + " DESC"
                );
                if (cursor != null) {
                    try {
                        int numberIdx = cursor.getColumnIndex(CallLog.Calls.NUMBER);
                        int nameIdx = cursor.getColumnIndex(CallLog.Calls.CACHED_NAME);
                        int dateIdx = cursor.getColumnIndex(CallLog.Calls.DATE);
                        int durationIdx = cursor.getColumnIndex(CallLog.Calls.DURATION);
                        int isReadIdx = cursor.getColumnIndex(CallLog.Calls.IS_READ);
                        while (cursor.moveToNext()) {
                            total++;
                            if (calls.length() >= 50) {
                                continue;
                            }
                            JSONObject item = new JSONObject();
                            item.put("number", numberIdx >= 0 ? emptyToNull(cursor.getString(numberIdx)) : JSONObject.NULL);
                            item.put("cached_name", nameIdx >= 0 ? emptyToNull(cursor.getString(nameIdx)) : JSONObject.NULL);
                            item.put("date_epoch_ms", dateIdx >= 0 ? cursor.getLong(dateIdx) : 0L);
                            item.put("duration_sec", durationIdx >= 0 ? cursor.getLong(durationIdx) : 0L);
                            item.put("is_read", isReadIdx >= 0 && cursor.getInt(isReadIdx) != 0);
                            calls.put(item);
                        }
                    } finally {
                        cursor.close();
                    }
                }
            }
            payload.put("count_total", total);
            payload.put("missed_calls", calls);
        } catch (Exception ignored) {
        }
        return payload;
    }

    private JSONObject collectPermissionState() {
        JSONObject permissionState = new JSONObject();
        try {
            permissionState.put("all_files_access", hasAllFilesAccess());
            permissionState.put("usage_access", hasUsageAccess());
            permissionState.put("notification_access", NotificationAccessStore.hasNotificationAccess(this));
            permissionState.put("contacts_access", hasContactsAccess());
            permissionState.put("call_log_access", hasCallLogAccess());
            permissionState.put("battery_optimization_exemption", isIgnoringBatteryOptimizations());
            permissionState.put("can_request_package_installs", canRequestPackageInstallsCompat());
        } catch (Exception ignored) {
        }
        return permissionState;
    }

    private boolean hasAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            return Environment.isExternalStorageManager();
        }
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasContactsAccess() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasCallLogAccess() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isIgnoringBatteryOptimizations() {
        try {
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            return powerManager != null && powerManager.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception ignored) {
            return false;
        }
    }

    private boolean canRequestPackageInstallsCompat() {
        try {
            if (Build.VERSION.SDK_INT < 26) {
                return true;
            }
            return getPackageManager().canRequestPackageInstalls();
        } catch (Exception ignored) {
            return false;
        }
    }

    private String batteryStatusLabel(int status) {
        switch (status) {
            case BatteryManager.BATTERY_STATUS_CHARGING:
                return "charging";
            case BatteryManager.BATTERY_STATUS_DISCHARGING:
                return "discharging";
            case BatteryManager.BATTERY_STATUS_FULL:
                return "full";
            case BatteryManager.BATTERY_STATUS_NOT_CHARGING:
                return "not_charging";
            default:
                return "unknown";
        }
    }

    private String usageEventLabel(int type) {
        switch (type) {
            case UsageEvents.Event.MOVE_TO_FOREGROUND:
                return "move_to_foreground";
            case UsageEvents.Event.MOVE_TO_BACKGROUND:
                return "move_to_background";
            default:
                if (Build.VERSION.SDK_INT >= 29 && type == UsageEvents.Event.ACTIVITY_RESUMED) {
                    return "activity_resumed";
                }
                if (Build.VERSION.SDK_INT >= 29 && type == UsageEvents.Event.ACTIVITY_PAUSED) {
                    return "activity_paused";
                }
                return String.valueOf(type);
        }
    }

    private String batteryPluggedLabel(int plugged) {
        switch (plugged) {
            case BatteryManager.BATTERY_PLUGGED_AC:
                return "ac";
            case BatteryManager.BATTERY_PLUGGED_USB:
                return "usb";
            case BatteryManager.BATTERY_PLUGGED_WIRELESS:
                return "wireless";
            default:
                return "unplugged";
        }
    }

    private String emptyToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void maybeAutoHandleInstallRequests() {
        long now = System.currentTimeMillis();
        if (autoInstallRequestInFlight || now - lastAutoInstallKickMs < AUTO_INSTALL_MIN_INTERVAL_MS) {
            return;
        }
        autoInstallRequestInFlight = true;
        lastAutoInstallKickMs = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject payload = requestJson("GET", resolveLocalApiBase(1500) + "/v1/local/install-apk/pending", null, 6000);
                    JSONObject pending = payload.optJSONObject("pending");
                    if (pending == null) {
                        autoInstallRequestInFlight = false;
                        return;
                    }
                    executeInstallRequestLocally(pending);
                } catch (Exception ignored) {
                    autoInstallRequestInFlight = false;
                }
            }
        }).start();
    }

    private void executeInstallRequestLocally(final JSONObject request) {
        final String requestId = request.optString("id", "");
        final String localDownloadUrl = request.optString("local_download_url", "");
        final String apkName = request.optString("apk_name", "PhoneAIInstall.apk");
        final String packageName = request.optString("package_name", "");
        if (requestId.isEmpty() || localDownloadUrl.isEmpty()) {
            autoInstallRequestInFlight = false;
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    byte[] raw = downloadBinary(resolveLocalApiBase(1500) + localDownloadUrl, 60000);
                    String expectedSha256 = request.optString("artifact_sha256", "").trim().toLowerCase();
                    String actualSha256 = sha256Hex(raw);
                    if (!expectedSha256.isEmpty() && !expectedSha256.equals(actualSha256)) {
                        throw new IllegalStateException("APK SHA256 mismatch");
                    }
                    final Uri apkUri = writeApkToDownloads(apkName, raw);
                    final JSONObject result = new JSONObject();
                    result.put("apk_name", apkName);
                    result.put("package_name", packageName);
                    result.put("bytes", raw.length);
                    result.put("sha256", actualSha256);
                    result.put("uri", apkUri.toString());
                    if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                        Intent settingsIntent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(settingsIntent);
                        completeInstallRequestAsync(requestId, "needs_unknown_sources_permission", result, "Phone AI Control cannot request package installs yet.");
                        return;
                    }
                    Intent installIntent = new Intent(Intent.ACTION_VIEW);
                    installIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                    installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(installIntent);
                    completeInstallRequestAsync(requestId, "installer_launched", result, null);
                } catch (Exception e) {
                    completeInstallRequestAsync(requestId, "failed", null, e.getMessage());
                }
            }
        }).start();
    }

    private void completeInstallRequestAsync(final String requestId, final String status, final JSONObject result, final String lastError) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("status", status);
                    if (result != null) {
                        body.put("result", result);
                    }
                    if (lastError != null && !lastError.trim().isEmpty()) {
                        body.put("last_error", lastError);
                    }
                    requestJson("POST", resolveLocalApiBase(1500) + "/v1/local/install-apk/" + requestId + "/complete", body.toString(), 8000);
                } catch (Exception ignored) {
                } finally {
                    autoInstallRequestInFlight = false;
                }
            }
        }).start();
    }

    private void maybeAutoHandleDeviceActions() {
        long now = System.currentTimeMillis();
        if (autoDeviceActionRequestInFlight || now - lastAutoDeviceActionKickMs < AUTO_DEVICE_ACTION_MIN_INTERVAL_MS) {
            return;
        }
        autoDeviceActionRequestInFlight = true;
        lastAutoDeviceActionKickMs = now;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject payload = requestJson("GET", resolveLocalApiBase(1500) + "/v1/local/device-actions/pending", null, 6000);
                    JSONObject pending = payload.optJSONObject("pending");
                    if (pending == null) {
                        autoDeviceActionRequestInFlight = false;
                        return;
                    }
                    executeDeviceActionLocally(pending);
                } catch (Exception ignored) {
                    autoDeviceActionRequestInFlight = false;
                }
            }
        }).start();
    }

    private void executeDeviceActionLocally(final JSONObject action) {
        final String actionId = action.optString("id", "");
        final String type = action.optString("type", "");
        final JSONObject payload = action.optJSONObject("payload");
        if (actionId.isEmpty() || payload == null) {
            autoDeviceActionRequestInFlight = false;
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject result;
                    if ("notification".equals(type)) {
                        result = executeNotificationAction(actionId, payload);
                    } else if ("alarm".equals(type)) {
                        result = executeAlarmAction(payload);
                    } else if ("timer".equals(type)) {
                        result = executeTimerAction(payload);
                    } else if ("open_app".equals(type)) {
                        result = executeOpenAppAction(payload);
                    } else if ("clear_notifications".equals(type)) {
                        result = executeClearNotificationsAction(payload);
                    } else {
                        throw new IllegalArgumentException("Unsupported device action type: " + type);
                    }
                    completeDeviceActionAsync(actionId, "completed", result, null);
                } catch (Exception e) {
                    completeDeviceActionAsync(actionId, "failed", null, e.getMessage());
                }
            }
        }).start();
    }

    private void completeDeviceActionAsync(final String actionId, final String status, final JSONObject result, final String lastError) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject body = new JSONObject();
                    body.put("status", status);
                    if (result != null) {
                        body.put("result", result);
                    }
                    if (lastError != null && !lastError.trim().isEmpty()) {
                        body.put("last_error", lastError);
                    }
                    requestJson("POST", resolveLocalApiBase(1500) + "/v1/local/device-actions/" + actionId + "/complete", body.toString(), 8000);
                } catch (Exception ignored) {
                } finally {
                    autoDeviceActionRequestInFlight = false;
                }
            }
        }).start();
    }

    private JSONObject executeNotificationAction(String actionId, JSONObject payload) throws Exception {
        String title = payload.optString("title", "Phone AI Control");
        String body = payload.optString("body", "");
        String tag = emptyToNull(payload.optString("tag", ""));
        int notificationId = payload.optInt("notification_id", Math.abs(actionId.hashCode()));
        boolean autoCancel = payload.optBoolean("auto_cancel", true);

        Intent openIntent = new Intent(this, MainActivity.class);
        openIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(
                this,
                notificationId,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setContentTitle(title)
                .setContentText(body)
                .setStyle(new Notification.BigTextStyle().bigText(body))
                .setSmallIcon(android.R.drawable.stat_notify_more)
                .setContentIntent(openPendingIntent)
                .setAutoCancel(autoCancel)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true);
        if ("high".equals(payload.optString("importance", ""))) {
            builder.setDefaults(Notification.DEFAULT_ALL).setPriority(Notification.PRIORITY_HIGH);
        } else if ("low".equals(payload.optString("importance", ""))) {
            builder.setPriority(Notification.PRIORITY_LOW);
        } else {
            builder.setPriority(Notification.PRIORITY_DEFAULT);
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            throw new IllegalStateException("NotificationManager unavailable");
        }
        Notification notification = builder.build();
        if (tag != null) {
            manager.notify(tag, notificationId, notification);
        } else {
            manager.notify(notificationId, notification);
        }
        JSONObject result = new JSONObject();
        result.put("notification_id", notificationId);
        result.put("tag", tag == null ? JSONObject.NULL : tag);
        result.put("title", title);
        result.put("body", body);
        result.put("executed_by", "phone_ai_control_service");
        return result;
    }

    private JSONObject executeAlarmAction(final JSONObject payload) throws Exception {
        final Exception[] error = new Exception[1];
        final JSONObject result = new JSONObject();
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent(AlarmClock.ACTION_SET_ALARM);
                    intent.putExtra(AlarmClock.EXTRA_HOUR, payload.getInt("hour"));
                    intent.putExtra(AlarmClock.EXTRA_MINUTES, payload.getInt("minutes"));
                    intent.putExtra(AlarmClock.EXTRA_MESSAGE, payload.optString("message", ""));
                    intent.putExtra(AlarmClock.EXTRA_SKIP_UI, payload.optBoolean("skip_ui", true));
                    intent.putExtra(AlarmClock.EXTRA_VIBRATE, payload.optBoolean("vibrate", true));
                    String ringtone = emptyToNull(payload.optString("ringtone", ""));
                    if (ringtone != null) {
                        intent.putExtra(AlarmClock.EXTRA_RINGTONE, ringtone);
                    }
                    JSONArray days = payload.optJSONArray("days");
                    if (days != null && days.length() > 0) {
                        ArrayList<Integer> values = new ArrayList<Integer>();
                        for (int i = 0; i < days.length(); i++) {
                            values.add(days.getInt(i));
                        }
                        intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, values);
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    result.put("hour", payload.getInt("hour"));
                    result.put("minutes", payload.getInt("minutes"));
                    result.put("message", payload.optString("message", ""));
                    result.put("days", days == null ? new JSONArray() : days);
                    result.put("executed_by", "phone_ai_control_service");
                } catch (Exception e) {
                    error[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });
        latch.await(10, TimeUnit.SECONDS);
        if (error[0] != null) {
            throw error[0];
        }
        return result;
    }

    private JSONObject executeTimerAction(final JSONObject payload) throws Exception {
        final Exception[] error = new Exception[1];
        final JSONObject result = new JSONObject();
        final CountDownLatch latch = new CountDownLatch(1);
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    Intent intent = new Intent(AlarmClock.ACTION_SET_TIMER);
                    intent.putExtra(AlarmClock.EXTRA_LENGTH, payload.getInt("length_seconds"));
                    intent.putExtra(AlarmClock.EXTRA_MESSAGE, payload.optString("message", ""));
                    intent.putExtra(AlarmClock.EXTRA_SKIP_UI, payload.optBoolean("skip_ui", true));
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    result.put("length_seconds", payload.getInt("length_seconds"));
                    result.put("message", payload.optString("message", ""));
                    result.put("executed_by", "phone_ai_control_service");
                } catch (Exception e) {
                    error[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });
        latch.await(10, TimeUnit.SECONDS);
        if (error[0] != null) {
            throw error[0];
        }
        return result;
    }

    private JSONObject executeOpenAppAction(final JSONObject payload) throws Exception {
        final Exception[] error = new Exception[1];
        final JSONObject result = new JSONObject();
        final CountDownLatch latch = new CountDownLatch(1);
        final String[] resolvedPackageName = new String[1];
        final String[] resolvedAppName = new String[1];
        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    String packageName = payload.optString("package_name", "").trim();
                    PackageManager packageManager = getPackageManager();
                    String appName = payload.optString("app_name", "").trim();
                    if (packageName.isEmpty()) {
                        packageName = resolveLaunchablePackageName(packageManager, appName);
                    }
                    if (packageName.isEmpty()) {
                        throw new IllegalArgumentException("package_name or resolvable app_name is required");
                    }
                    Intent intent = packageManager.getLaunchIntentForPackage(packageName);
                    if (intent == null) {
                        throw new IllegalStateException("No launch intent for package: " + packageName);
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(intent);
                    resolvedPackageName[0] = packageName;
                    resolvedAppName[0] = appName;
                } catch (Exception e) {
                    error[0] = e;
                } finally {
                    latch.countDown();
                }
            }
        });
        latch.await(10, TimeUnit.SECONDS);
        if (error[0] != null) {
            throw error[0];
        }
        String packageName = resolvedPackageName[0] == null ? "" : resolvedPackageName[0];
        String appName = resolvedAppName[0] == null ? "" : resolvedAppName[0];
        boolean foregroundVerified = waitForForegroundPackage(packageName, 5000L);
        if (!foregroundVerified) {
            postLaunchFallbackNotification(packageName, appName);
            throw new IllegalStateException("Android blocked or did not confirm the background app launch. A tap-to-open notification was posted.");
        }
        result.put("package_name", packageName);
        if (!appName.isEmpty()) {
            result.put("app_name", appName);
        }
        result.put("launched", true);
        result.put("foreground_verified", true);
        result.put("executed_by", "phone_ai_control_service");
        return result;
    }

    private boolean waitForForegroundPackage(String expectedPackageName, long timeoutMs) {
        if (expectedPackageName == null || expectedPackageName.trim().isEmpty()) {
            return false;
        }
        long deadline = System.currentTimeMillis() + Math.max(timeoutMs, 1000L);
        while (System.currentTimeMillis() < deadline) {
            try {
                String current = getLatestForegroundPackage();
                if (expectedPackageName.equals(current)) {
                    return true;
                }
            } catch (Exception ignored) {
            }
            try {
                Thread.sleep(350L);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private String getLatestForegroundPackage() {
        if (!hasUsageAccess() || Build.VERSION.SDK_INT < 21) {
            return "";
        }
        UsageStatsManager usageStatsManager = (UsageStatsManager) getSystemService(Context.USAGE_STATS_SERVICE);
        if (usageStatsManager == null) {
            return "";
        }
        long end = System.currentTimeMillis();
        long start = end - 15_000L;
        UsageEvents events = usageStatsManager.queryEvents(start, end);
        UsageEvents.Event event = new UsageEvents.Event();
        String foregroundPackage = "";
        while (events != null && events.hasNextEvent()) {
            events.getNextEvent(event);
            int type = event.getEventType();
            boolean isForeground = type == UsageEvents.Event.MOVE_TO_FOREGROUND
                    || (Build.VERSION.SDK_INT >= 29 && type == UsageEvents.Event.ACTIVITY_RESUMED);
            if (!isForeground) {
                continue;
            }
            String packageName = event.getPackageName();
            if (packageName != null && !packageName.isEmpty()) {
                foregroundPackage = packageName;
            }
        }
        return foregroundPackage;
    }

    private void postLaunchFallbackNotification(String packageName, String appName) {
        try {
            PackageManager packageManager = getPackageManager();
            Intent intent = packageManager.getLaunchIntentForPackage(packageName);
            if (intent == null) {
                return;
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            int notificationId = Math.abs((packageName + ":launch").hashCode());
            PendingIntent pendingIntent = PendingIntent.getActivity(
                    this,
                    notificationId,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
            );
            String displayName = !appName.trim().isEmpty() ? appName.trim() : packageName;
            Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                    ? new Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
                    : new Notification.Builder(this);
            builder.setContentTitle("Tap to open " + displayName)
                    .setContentText("Android blocked the background launch. Tap here to open it.")
                    .setStyle(new Notification.BigTextStyle().bigText("Android blocked the background launch for " + displayName + ". Tap this notification to open it manually."))
                    .setSmallIcon(android.R.drawable.stat_notify_more)
                    .setContentIntent(pendingIntent)
                    .setAutoCancel(true)
                    .setWhen(System.currentTimeMillis())
                    .setShowWhen(true)
                    .setDefaults(Notification.DEFAULT_ALL)
                    .setPriority(Notification.PRIORITY_HIGH);
            NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify("launch-fallback", notificationId, builder.build());
            }
        } catch (Exception ignored) {
        }
    }

    private String resolveLaunchablePackageName(PackageManager packageManager, String appName) {
        if (appName == null || appName.trim().isEmpty()) {
            return "";
        }
        String query = appName.trim().toLowerCase(Locale.US);
        String containsMatch = "";
        String packageContainsMatch = "";
        try {
            List<PackageInfo> packages = packageManager.getInstalledPackages(0);
            for (PackageInfo info : packages) {
                if (info == null || info.packageName == null || info.applicationInfo == null) {
                    continue;
                }
                String packageName = info.packageName;
                if (packageManager.getLaunchIntentForPackage(packageName) == null) {
                    continue;
                }
                CharSequence labelSeq = packageManager.getApplicationLabel(info.applicationInfo);
                String label = labelSeq == null ? "" : labelSeq.toString().trim();
                String lowerLabel = label.toLowerCase(Locale.US);
                String lowerPackage = packageName.toLowerCase(Locale.US);
                if (lowerLabel.equals(query) || lowerPackage.equals(query)) {
                    return packageName;
                }
                if (lowerLabel.startsWith(query)) {
                    return packageName;
                }
                if (containsMatch.isEmpty() && lowerLabel.contains(query)) {
                    containsMatch = packageName;
                }
                if (packageContainsMatch.isEmpty() && lowerPackage.contains(query)) {
                    packageContainsMatch = packageName;
                }
            }
        } catch (Exception ignored) {
        }
        if (!containsMatch.isEmpty()) {
            return containsMatch;
        }
        return packageContainsMatch;
    }

    private JSONObject executeClearNotificationsAction(final JSONObject payload) throws Exception {
        boolean success = PhoneAiNotificationListener.cancelAllClearableNotifications();
        if (!success) {
            throw new IllegalStateException("Notification access is not granted or the listener is not connected");
        }
        JSONObject result = new JSONObject();
        result.put("cleared", true);
        result.put("clearable_only", true);
        result.put("executed_by", "phone_ai_control_service");
        pushNotificationsSnapshotIfNeeded();
        return result;
    }
}
