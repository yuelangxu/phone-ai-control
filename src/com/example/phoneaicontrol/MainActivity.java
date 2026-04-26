package com.example.phoneaicontrol;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.util.Base64;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class MainActivity extends Activity {
    private static final String RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND";
    private static final String RUN_COMMAND_PATH_EXTRA = "com.termux.RUN_COMMAND_PATH";
    private static final String RUN_COMMAND_ARGUMENTS_EXTRA = "com.termux.RUN_COMMAND_ARGUMENTS";
    private static final String RUN_COMMAND_WORKDIR_EXTRA = "com.termux.RUN_COMMAND_WORKDIR";
    private static final String RUN_COMMAND_BACKGROUND_EXTRA = "com.termux.RUN_COMMAND_BACKGROUND";
    private static final String RUN_COMMAND_PENDING_INTENT_EXTRA = "com.termux.RUN_COMMAND_PENDING_INTENT";
    private static final String DEFAULT_LOCAL_API = "http://127.0.0.1:8787";
    private static final String TERMUX_HOME = "/data/user/0/com.termux/files/home";
    private static final String PHONE_AI_RUNTIME_FILE = "/storage/emulated/0/Android/media/com.example.phoneaicontrol/runtime.json";
    private static final String TERMUX_BASH = "/data/user/0/com.termux/files/usr/bin/bash";
    private static final int REQUEST_RUN_COMMAND = 42;
    private static final int REQUEST_CONFIRM_CREDENTIAL = 43;
    private static final int REQUEST_STORAGE_PERMISSION = 44;
    private static final int REQUEST_ALL_FILES_ACCESS = 45;
    private static final int REQUEST_CONTACTS_AND_CALLS = 46;
    private static final int TOKEN_CLEAR_AFTER_MS = 20_000;
    private static final long MAX_LOCAL_READ_BYTES = 4L * 1024 * 1024 * 1024;
    private static final int DIRECT_INLINE_READ_BYTES = 1 * 1024 * 1024;
    private static final int CHUNK_UPLOAD_BYTES = 4 * 1024 * 1024;
    private static final int MAX_LOCAL_LIST_ENTRIES = 200;
    private static final boolean AUTO_APPROVE_SHARED_STORAGE = true;
    private static final long AUTO_APPROVAL_MIN_INTERVAL_MS = 1500L;
    private static final long AUTO_INSTALL_MIN_INTERVAL_MS = 2000L;
    private static final long AUTO_DEVICE_ACTION_MIN_INTERVAL_MS = 1500L;
    private static final long BATTERY_PUSH_MIN_INTERVAL_MS = 15000L;
    private static final long USAGE_PUSH_MIN_INTERVAL_MS = 15000L;
    private static final long DISCOVERY_RETRY_MIN_INTERVAL_MS = 3000L;
    private static final long PERIODIC_REFRESH_MS = 15000L;
    private static final String NOTIFICATION_CHANNEL_ID = "phone_ai_control_alerts";

    private TextView statusText;
    private TextView addressText;
    private TextView publicText;
    private TextView detailText;
    private TextView approvalText;
    private TextView pollingText;
    private EditText pollIntervalInput;
    private Button startButton;
    private Button stopPublicButton;
    private Button stopAllButton;
    private Button applyPollingButton;
    private String currentPublicUrl = "";
    private String currentLocalApiBase = DEFAULT_LOCAL_API;
    private String currentPendingApprovalId = "";
    private String pendingLocalApprovalJson = "";
    private boolean discoveryInFlight = false;
    private long lastDiscoveryKickMs = 0L;
    private long lastAutoApprovalKickMs = 0L;
    private boolean autoApprovalRequestInFlight = false;
    private long lastAutoInstallKickMs = 0L;
    private boolean autoInstallRequestInFlight = false;
    private long lastAutoDeviceActionKickMs = 0L;
    private boolean autoDeviceActionRequestInFlight = false;
    private long lastBatteryPushMs = 0L;
    private boolean batteryPushInFlight = false;
    private long lastUsagePushMs = 0L;
    private boolean usagePushInFlight = false;
    private final Runnable periodicRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            if (statusText != null) {
                statusText.postDelayed(this, PERIODIC_REFRESH_MS);
            }
        }
    };

    private final BroadcastReceiver approvalReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String broadcastAction = intent.getAction();
            if (TokenResultService.BROADCAST_COMMAND_RESULT.equals(broadcastAction)) {
                handleCommandResult(
                        intent.getStringExtra(TokenResultService.EXTRA_ACTION_NAME),
                        intent.getBooleanExtra(TokenResultService.EXTRA_OK, false),
                        intent.getStringExtra(TokenResultService.EXTRA_MESSAGE),
                        intent.getStringExtra(TokenResultService.EXTRA_STDOUT),
                        intent.getStringExtra(TokenResultService.EXTRA_STDERR)
                );
                return;
            }
            if (!TokenResultService.BROADCAST_APPROVAL_RESULT.equals(broadcastAction)) {
                return;
            }
            String action = intent.getStringExtra(TokenResultService.EXTRA_ACTION_NAME);
            boolean ok = intent.getBooleanExtra(TokenResultService.EXTRA_OK, false);
            String message = intent.getStringExtra(TokenResultService.EXTRA_MESSAGE);
            String responseJson = intent.getStringExtra(TokenResultService.EXTRA_RESPONSE_JSON);
            if (TokenResultService.ACTION_REVIEW_PENDING_APPROVAL.equals(action)) {
                handleApprovalReviewResult(ok, message, responseJson);
            } else if (TokenResultService.ACTION_APPROVE_PENDING_APPROVAL.equals(action)) {
                handleApprovalApproveResult(ok, message, responseJson);
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        ensureNotificationChannel();
        if (AutomationSettings.isPollingEnabled(this)) {
            AutomationLoopService.start(this);
        }
        requestTermuxPermissionIfNeeded();
        refreshStatus();
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(TokenResultService.BROADCAST_APPROVAL_RESULT);
        filter.addAction(TokenResultService.BROADCAST_COMMAND_RESULT);
        registerReceiver(approvalReceiver, filter);
        refreshStatus();
        restartUiRefreshLoopIfNeeded();
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (statusText != null) {
            statusText.removeCallbacks(periodicRefreshRunnable);
        }
        try {
            unregisterReceiver(approvalReceiver);
        } catch (Exception ignored) {
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CONFIRM_CREDENTIAL) {
            if (resultCode == RESULT_OK) {
                requestApprovalGrant();
            } else {
                Toast.makeText(this, "Approval cancelled.", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        if (requestCode == REQUEST_ALL_FILES_ACCESS) {
            if (hasSharedStoragePermission()) {
                if (pendingLocalApprovalJson != null && !pendingLocalApprovalJson.isEmpty()) {
                    try {
                        executeSharedStorageApprovalLocally(new JSONObject(pendingLocalApprovalJson));
                    } catch (Exception e) {
                        approvalText.setText("Pending File Approval\nAll-files access was granted, but the pending approval could not resume: " + e.getMessage());
                        setButtons(true);
                    }
                } else {
                    setButtons(true);
                }
            } else if (Build.VERSION.SDK_INT >= 23) {
                requestPermissions(
                        new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                        REQUEST_STORAGE_PERMISSION
                );
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQUEST_STORAGE_PERMISSION) {
            return;
        }
        boolean granted = true;
        if (grantResults.length == 0) {
            granted = false;
        } else {
            for (int grantResult : grantResults) {
                if (grantResult != PackageManager.PERMISSION_GRANTED) {
                    granted = false;
                    break;
                }
            }
        }
        if (!granted) {
            approvalText.setText("Pending File Approval\nStorage permission was denied. Shared-storage file operations need explicit storage access in Phone AI Control.");
            setButtons(true);
            return;
        }
        if (pendingLocalApprovalJson == null || pendingLocalApprovalJson.isEmpty()) {
            setButtons(true);
            return;
        }
        try {
            executeSharedStorageApprovalLocally(new JSONObject(pendingLocalApprovalJson));
        } catch (Exception e) {
            approvalText.setText("Pending File Approval\nStorage permission was granted, but the pending approval could not be resumed: " + e.getMessage());
            setButtons(true);
        }
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(22));
        root.setBackgroundColor(Color.rgb(246, 248, 244));
        scroll.addView(root);

        TextView title = text("Phone AI Control", 30, Color.rgb(18, 42, 34), true);
        root.addView(title);
        root.addView(text("Control the Termux build/simulation API and its public tunnel.", 15, Color.rgb(76, 87, 81), false));

        statusText = card("Local API", "Checking...");
        addressText = card("Direct GPT Schema URL", "Unknown");
        publicText = card("Public Exposure", "Checking...");
        detailText = card("Details", "No data yet.");
        pollingText = card("Background Polling", "Checking...");
        approvalText = card("Pending File Approval", "Auto mode is enabled. Shared-storage file changes will be approved and executed automatically while Phone AI Control is open.");
        root.addView(statusText);
        root.addView(addressText);
        root.addView(publicText);
        root.addView(pollingText);

        LinearLayout pollRow = new LinearLayout(this);
        pollRow.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams pollRowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        pollRowParams.setMargins(0, dp(10), 0, 0);
        pollRow.setLayoutParams(pollRowParams);

        pollIntervalInput = new EditText(this);
        pollIntervalInput.setSingleLine(true);
        pollIntervalInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        pollIntervalInput.setHint("Polling seconds");
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
        );
        pollIntervalInput.setLayoutParams(inputParams);
        pollIntervalInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        pollRow.addView(pollIntervalInput);

        applyPollingButton = new Button(this);
        applyPollingButton.setText("Apply Polling");
        applyPollingButton.setAllCaps(false);
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        applyParams.setMargins(dp(8), 0, 0, 0);
        applyPollingButton.setLayoutParams(applyParams);
        applyPollingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyPollingInterval();
            }
        });
        pollRow.addView(applyPollingButton);
        root.addView(pollRow);

        startButton = button("Start / Restart Public API");
        stopPublicButton = button("Stop Public Tunnel Only");
        stopAllButton = button("Stop API And Tunnel");
        Button refreshButton = button("Refresh Status");
        Button copyButton = button("Copy Direct Schema URL");
        Button copyTokenButton = button("Copy Phone API Token (20s)");
        Button allFilesButton = button("Open All Files Access Settings");
        Button usageAccessButton = button("Open Usage Access Settings");
        Button notificationAccessButton = button("Open Notification Access Settings");
        Button contactsCallLogButton = button("Grant Contacts And Call Log Access");
        Button batteryOptimizationButton = button("Request Battery Optimization Exemption");
        Button unknownSourcesButton = button("Open Install Unknown Apps Settings");
        Button settingsButton = button("Open Phone AI Control Settings");

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startOrRestartPublicApi();
            }
        });
        stopPublicButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPublicTunnelOnly();
            }
        });
        stopAllButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopApiAndTunnel();
            }
        });
        refreshButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refreshStatus();
            }
        });
        copyButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyAddress();
            }
        });
        copyTokenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyToken();
            }
        });
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppSettings();
            }
        });
        allFilesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAllFilesAccessSettings();
            }
        });
        usageAccessButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUsageAccessSettings();
            }
        });
        notificationAccessButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openNotificationAccessSettings();
            }
        });
        contactsCallLogButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestContactsAndCallLogAccess();
            }
        });
        batteryOptimizationButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestIgnoreBatteryOptimizations();
            }
        });
        unknownSourcesButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openUnknownAppSourcesSettings();
            }
        });

        root.addView(startButton);
        root.addView(stopPublicButton);
        root.addView(stopAllButton);
        root.addView(refreshButton);
        root.addView(copyButton);
        root.addView(copyTokenButton);
        root.addView(allFilesButton);
        root.addView(usageAccessButton);
        root.addView(notificationAccessButton);
        root.addView(contactsCallLogButton);
        root.addView(batteryOptimizationButton);
        root.addView(unknownSourcesButton);
        root.addView(settingsButton);
        root.addView(detailText);
        root.addView(approvalText);

        TextView note = text(
                "Security note: the token stays in Termux private storage and is only copied on demand. This app clears copied token text from clipboard after 20 seconds.",
                13,
                Color.rgb(96, 96, 96),
                false);
        note.setPadding(0, dp(12), 0, 0);
        root.addView(note);

        TextView pollingNote = text(
                "Polling note: enter seconds to control the background check interval. Enter 0 to disable polling completely.",
                13,
                Color.rgb(124, 124, 124),
                false);
        pollingNote.setPadding(0, dp(8), 0, 0);
        root.addView(pollingNote);

        setContentView(scroll);
        refreshPollingUi();
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(5));
        if (bold) {
            view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private TextView card(String title, String body) {
        TextView view = text(title + "\n" + body, 16, Color.rgb(23, 36, 31), false);
        view.setBackgroundColor(Color.WHITE);
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(12), 0, 0);
        view.setLayoutParams(params);
        return view;
    }

    private Button button(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private void refreshStatus() {
        refreshPollingUi();
        statusText.setText("Local API\nChecking...");
        publicText.setText("Public Exposure\nChecking...");
        new Thread(new Runnable() {
            @Override
            public void run() {
            String localStatus;
            String publicStatus = "Disabled or unknown";
            String details = "";
            String publicUrl = "";
            final boolean allFiles = hasAllFilesAccess();
            final boolean usageAccess = hasUsageAccess();
            final boolean notificationAccess = hasNotificationAccess();
            final boolean contactsAccess = hasContactsAccess();
            final boolean callLogAccess = hasCallLogAccess();
            final boolean batteryOptimizationIgnored = isIgnoringBatteryOptimizations();
            final boolean installUnknownAppsAllowed = canRequestPackageInstallsCompat();
            try {
                String localApiBase = resolveLocalApiBase(2500);
                JSONObject health = getJson(localApiBase + "/healthz", 2500);
                localStatus = health.optBoolean("ok") ? "Online" : "Unexpected response";
                currentLocalApiBase = localApiBase;
                publicUrl = normalizePublicUrl(health.optString("public_url", ""));
                boolean enabled = health.optBoolean("public_enabled", false);
                boolean lt = health.optBoolean("localtunnel_running", false);
                boolean cf = health.optBoolean("cloudflared_running", false);
                details = "localtunnel: " + (lt ? "running" : "stopped")
                        + "\ncloudflared: " + (cf ? "running" : "stopped")
                        + "\nauth required: " + health.optBoolean("auth_required", true)
                        + "\nall-files access: " + (allFiles ? "granted" : "not granted")
                        + "\nusage access: " + (usageAccess ? "granted" : "not granted")
                        + "\nnotification access: " + (notificationAccess ? "granted" : "not granted")
                        + "\ncontacts access: " + (contactsAccess ? "granted" : "not granted")
                        + "\ncall log access: " + (callLogAccess ? "granted" : "not granted")
                        + "\nbattery optimization exemption: " + (batteryOptimizationIgnored ? "granted" : "not granted")
                        + "\ninstall unknown apps: " + (installUnknownAppsAllowed ? "granted" : "not granted");
                if (enabled && publicUrl.startsWith("https://")) {
                    publicStatus = "Enabled (tunnel running)";
                    try {
                        JSONObject publicHealth = getJson(publicUrl + "/healthz", 5000);
                        publicStatus = publicHealth.optBoolean("ok") ? "Enabled and reachable" : "Enabled, but health check failed";
                    } catch (Exception e) {
                        publicStatus = "Enabled (tunnel running)";
                        details += "\npublic self-check pending: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                } else if ((lt || cf) && publicUrl.isEmpty()) {
                    publicStatus = "Starting tunnel...";
                } else {
                    publicStatus = "Off";
                }
            } catch (Exception e) {
                localStatus = "Offline";
                details = "Local API check failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "\nall-files access: " + (allFiles ? "granted" : "not granted")
                        + "\nusage access: " + (usageAccess ? "granted" : "not granted")
                        + "\nnotification access: " + (notificationAccess ? "granted" : "not granted")
                        + "\ncontacts access: " + (contactsAccess ? "granted" : "not granted")
                        + "\ncall log access: " + (callLogAccess ? "granted" : "not granted")
                        + "\nbattery optimization exemption: " + (batteryOptimizationIgnored ? "granted" : "not granted")
                        + "\ninstall unknown apps: " + (installUnknownAppsAllowed ? "granted" : "not granted")
                        + "\nTap Start Public API to start Termux scripts.";
                long nowMs = System.currentTimeMillis();
                if (!discoveryInFlight && hasTermuxPermission() && nowMs - lastDiscoveryKickMs >= DISCOVERY_RETRY_MIN_INTERVAL_MS) {
                    discoveryInFlight = true;
                    lastDiscoveryKickMs = nowMs;
                    runManagedTermuxCommand(
                            "sh -c 'head -n 1 " + TERMUX_HOME + "/ai-phone-api/port.txt 2>/dev/null | tr -cd \"0-9\\n\"'",
                            TokenResultService.ACTION_DISCOVER_LOCAL_API,
                            null
                    );
                }
            }
            String finalLocalStatus = localStatus;
            String finalPublicStatus = publicStatus;
            String finalDetails = details;
            String finalPublicUrl = publicUrl;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    currentPublicUrl = buildGptSchemaUrl(finalPublicUrl == null ? "" : finalPublicUrl);
                    statusText.setText("Local API\n" + finalLocalStatus);
                    addressText.setText("Direct GPT Schema URL\n" + (currentPublicUrl.isEmpty() ? "None" : currentPublicUrl));
                    publicText.setText("Public Exposure\n" + finalPublicStatus);
                    detailText.setText("Details\n" + finalDetails);
                    refreshPollingUi();
                    if (!"Online".equals(finalLocalStatus)) {
                        currentPendingApprovalId = "";
                        approvalText.setText("Pending File Approval\nLocal API is offline.");
                    } else if ("Pending File Approval\nLocal API is offline.".contentEquals(approvalText.getText())) {
                        approvalText.setText("Pending File Approval\nAuto mode is enabled. Shared-storage file changes will be approved and executed automatically while Phone AI Control is open.");
                    }
                    pushBatteryStatusIfNeeded();
                    pushUsageSnapshotIfNeeded();
                    maybeAutoReviewPendingApprovals();
                    maybeAutoHandleInstallRequests();
                    maybeAutoHandleDeviceActions();
                }
            });
            }
        }).start();
    }

    private JSONObject getJson(String rawUrl, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(rawUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "PhoneAIControl/1.0");
        conn.setRequestProperty("bypass-tunnel-reminder", "1");
        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream()));
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
                    return candidate;
                }
            } catch (Exception e) {
                lastError = e;
            }
        }
        if (lastError != null) {
            throw lastError;
        }
        throw new IllegalStateException("Local API is unavailable");
    }

    private JSONObject readRuntimeState() {
        if (!hasSharedStoragePermission()) {
            return null;
        }
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

    private void startOrRestartPublicApi() {
        setButtons(false);
        detailText.setText("Details\nChecking whether the local API is already online...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean localApiOnline = false;
                try {
                    JSONObject health = getJson(resolveLocalApiBase(1500) + "/healthz", 1500);
                    localApiOnline = health.optBoolean("ok", false);
                } catch (Exception ignored) {
                }
                final boolean finalLocalApiOnline = localApiOnline;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (finalLocalApiOnline) {
                            requestTokenBackedAction(
                                    TokenResultService.ACTION_CONTROL_START_PUBLIC,
                                    null,
                                    "Public tunnel restart requested through the local API."
                            );
                        } else {
                            runManagedTermuxCommand(
                                    TERMUX_HOME + "/ai-phone-api/start-phone-ai-public.sh",
                                    TokenResultService.ACTION_START_PUBLIC_API,
                                    "Start requested. Phone AI Control will verify the result and keep polling for the public URL."
                            );
                        }
                    }
                });
            }
        }).start();
    }

    private void stopPublicTunnelOnly() {
        requestStopThroughLocalApi(
                TokenResultService.ACTION_CONTROL_STOP_PUBLIC,
                "Public tunnel stop requested through the local API. The local API should stay online by design.",
                "Local API is already offline, so there is no trusted local control channel to stop the tunnel."
        );
    }

    private void stopApiAndTunnel() {
        requestStopThroughLocalApi(
                TokenResultService.ACTION_CONTROL_STOP_ALL,
                "Stop-all requested through the local API. Phone AI Control will verify whether the local API actually went offline.",
                "Local API is already offline."
        );
    }

    private void requestStopThroughLocalApi(final String actionName, final String toastMessage, final String offlineMessage) {
        setButtons(false);
        detailText.setText("Details\nChecking whether the local API is online before sending the stop request...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean localApiOnline = false;
                try {
                    JSONObject health = getJson(resolveLocalApiBase(1500) + "/healthz", 1500);
                    localApiOnline = health.optBoolean("ok", false);
                } catch (Exception ignored) {
                }
                final boolean finalLocalApiOnline = localApiOnline;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (finalLocalApiOnline) {
                            requestTokenBackedAction(actionName, null, toastMessage);
                        } else {
                            if (TokenResultService.ACTION_CONTROL_STOP_ALL.equals(actionName)) {
                                runManagedTermuxCommand(
                                        TERMUX_HOME + "/ai-phone-api/stop-phone-ai-all.sh",
                                        TokenResultService.ACTION_STOP_ALL,
                                        "Stop-all requested through the Termux fallback path."
                                );
                            } else if (TokenResultService.ACTION_CONTROL_STOP_PUBLIC.equals(actionName)) {
                                runManagedTermuxCommand(
                                        TERMUX_HOME + "/ai-phone-api/stop-phone-ai-localtunnel.sh; " + TERMUX_HOME + "/ai-phone-api/stop-phone-ai-tunnel.sh",
                                        TokenResultService.ACTION_STOP_PUBLIC_TUNNEL,
                                        "Public tunnel stop requested through the Termux fallback path."
                                );
                            } else {
                                detailText.setText("Details\n" + offlineMessage);
                                refreshStatus();
                            }
                        }
                    }
                });
            }
        }).start();
    }

    private void runManagedTermuxCommand(String command, String actionName, String toast) {
        if (!hasTermuxPermission()) {
            requestTermuxPermissionIfNeeded();
            Toast.makeText(this, "Termux command permission is required.", Toast.LENGTH_LONG).show();
            setButtons(true);
            return;
        }
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
            if (toast != null && !toast.trim().isEmpty()) {
                Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not start Termux command: " + e.getMessage(), Toast.LENGTH_LONG).show();
            setButtons(true);
        }
    }

    private void copyToken() {
        if (!hasTermuxPermission()) {
            requestTermuxPermissionIfNeeded();
            Toast.makeText(this, "Termux command permission is required.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent resultIntent = new Intent(this, TokenResultService.class);
            int executionId = TokenResultService.getNextExecutionId();
            resultIntent.putExtra(TokenResultService.EXTRA_EXECUTION_ID, executionId);
            resultIntent.putExtra(TokenResultService.EXTRA_ACTION_NAME, TokenResultService.ACTION_COPY_TOKEN);
            resultIntent.putExtra(TokenResultService.EXTRA_CLEAR_AFTER_MS, TOKEN_CLEAR_AFTER_MS);
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
            intent.putExtra(RUN_COMMAND_ARGUMENTS_EXTRA, new String[]{"-lc", "cat " + TERMUX_HOME + "/ai-phone-api/token.txt"});
            intent.putExtra(RUN_COMMAND_WORKDIR_EXTRA, TERMUX_HOME);
            intent.putExtra(RUN_COMMAND_BACKGROUND_EXTRA, true);
            intent.putExtra(RUN_COMMAND_PENDING_INTENT_EXTRA, pendingIntent);
            startService(intent);
            Toast.makeText(this, "Copy Token requested. Clipboard will auto-clear after 20 seconds.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not request token copy: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void requestApprovalReview() {
        requestApprovalReview(false);
    }

    private void requestApprovalReview(boolean silent) {
        requestTokenBackedAction(
                TokenResultService.ACTION_REVIEW_PENDING_APPROVAL,
                null,
                silent ? null : "Checking pending file approvals..."
        );
    }

    private void requestApprovalGrant() {
        requestApprovalGrant(false);
    }

    private void requestApprovalGrant(boolean silent) {
        if (currentPendingApprovalId == null || currentPendingApprovalId.isEmpty()) {
            Toast.makeText(this, "No pending file approval selected.", Toast.LENGTH_SHORT).show();
            return;
        }
        requestTokenBackedAction(
                TokenResultService.ACTION_APPROVE_PENDING_APPROVAL,
                currentPendingApprovalId,
                silent ? null : "Approval requested. AI will receive a short-lived one-time execution token."
        );
    }

    private void requestTokenBackedAction(String actionName, String approvalId, String toast) {
        if (!hasTermuxPermission()) {
            requestTermuxPermissionIfNeeded();
            Toast.makeText(this, "Termux command permission is required.", Toast.LENGTH_LONG).show();
            setButtons(true);
            return;
        }
        try {
            Intent resultIntent = new Intent(this, TokenResultService.class);
            int executionId = TokenResultService.getNextExecutionId();
            resultIntent.putExtra(TokenResultService.EXTRA_EXECUTION_ID, executionId);
            resultIntent.putExtra(TokenResultService.EXTRA_ACTION_NAME, actionName);
            resultIntent.putExtra(TokenResultService.EXTRA_LOCAL_API_BASE, currentLocalApiBase);
            if (approvalId != null) {
                resultIntent.putExtra(TokenResultService.EXTRA_APPROVAL_ID, approvalId);
            }
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
            intent.putExtra(RUN_COMMAND_ARGUMENTS_EXTRA, new String[]{"-lc", "cat " + TERMUX_HOME + "/ai-phone-api/token.txt"});
            intent.putExtra(RUN_COMMAND_WORKDIR_EXTRA, TERMUX_HOME);
            intent.putExtra(RUN_COMMAND_BACKGROUND_EXTRA, true);
            intent.putExtra(RUN_COMMAND_PENDING_INTENT_EXTRA, pendingIntent);
            startService(intent);
            if (toast != null && !toast.trim().isEmpty()) {
                Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Could not request approval action: " + e.getMessage(), Toast.LENGTH_LONG).show();
            setButtons(true);
        }
    }

    private void startApprovalChallenge() {
        requestApprovalGrant(false);
    }

    private void handleApprovalReviewResult(boolean ok, String message, String responseJson) {
        autoApprovalRequestInFlight = false;
        if (!ok) {
            currentPendingApprovalId = "";
            approvalText.setText("Pending File Approval\n" + (message == null ? "Could not read pending approvals." : message));
            setButtons(true);
            return;
        }
        try {
            JSONObject payload = new JSONObject(responseJson);
            JSONArray pending = payload.optJSONArray("pending");
            JSONArray approvedReady = payload.optJSONArray("approved_ready");
            if (pending != null && pending.length() > 0) {
                JSONObject first = pending.getJSONObject(0);
                currentPendingApprovalId = first.optString("id", "");
                if (AUTO_APPROVE_SHARED_STORAGE && isSharedStorageApproval(first)) {
                    approvalText.setText("Pending File Approval\nAuto-approving shared-storage file change:\n" + first.optString("summary", "Pending file approval"));
                    requestApprovalGrant(true);
                    return;
                }
                String body = first.optString("summary", "Pending file approval")
                        + "\nPath: " + first.optString("path", "")
                        + "\nRequested: " + first.optString("requested_at", "")
                        + "\nExecution mode: " + (first.optBoolean("shared_storage", false) ? "Phone AI Control local shared-storage execution" : "Termux/direct execution")
                        + "\nPending count: " + pending.length();
                approvalText.setText("Pending File Approval\n" + body);
            } else if (approvedReady != null && approvedReady.length() > 0) {
                JSONObject first = approvedReady.getJSONObject(0);
                boolean sharedStorage = first.optBoolean("shared_storage", false);
                currentPendingApprovalId = sharedStorage ? first.optString("id", "") : "";
                String body = first.optString("summary", "Approved file change")
                        + "\nApproved: " + first.optString("approved_at", "")
                        + "\nToken valid until: " + first.optString("execution_token_expires_at", "")
                        + (sharedStorage
                        ? "\nShared storage: Phone AI Control is executing it locally."
                        : "");
                approvalText.setText("Pending File Approval\n" + body);
                if (sharedStorage && first.optString("execution_token", "").trim().length() > 0) {
                    beginSharedStorageLocalExecution(first);
                    return;
                }
            } else {
                currentPendingApprovalId = "";
                approvalText.setText("Pending File Approval\nNo pending file changes.");
            }
        } catch (Exception e) {
            currentPendingApprovalId = "";
            approvalText.setText("Pending File Approval\nCould not parse approval list: " + e.getMessage());
        }
        setButtons(true);
    }

    private void handleApprovalApproveResult(boolean ok, String message, String responseJson) {
        autoApprovalRequestInFlight = false;
        if (!ok) {
            approvalText.setText("Pending File Approval\n" + (message == null ? "Approval failed." : message));
            setButtons(true);
            return;
        }
        try {
            JSONObject payload = new JSONObject(responseJson);
            JSONObject approval = payload.optJSONObject("approval");
            if (approval == null) {
                throw new IllegalStateException("approval payload missing");
            }
            if (isSharedStorageApproval(approval)) {
                beginSharedStorageLocalExecution(approval);
                return;
            }
            String body = (approval != null ? approval.optString("summary", "Approved file change") : "Approved file change")
                    + "\nApproved: " + (approval != null ? approval.optString("approved_at", "") : "")
                    + "\nExecution token valid until: " + (approval != null ? approval.optString("execution_token_expires_at", "") : "");
            approvalText.setText("Pending File Approval\n" + body);
            currentPendingApprovalId = "";
        } catch (Exception e) {
            approvalText.setText("Pending File Approval\nApproval granted, but result parsing failed: " + e.getMessage());
            currentPendingApprovalId = "";
        }
        scheduleRefreshes(1500, 5000);
        requestApprovalReview(true);
    }

    private void handleCommandResult(String actionName, boolean ok, String message, String stdout, String stderr) {
        String trimmedStdout = stdout == null ? "" : stdout.trim();
        String trimmedStderr = stderr == null ? "" : stderr.trim();
        StringBuilder details = new StringBuilder();
        if (message != null && !message.isEmpty()) {
            details.append(message);
        }
        if (!trimmedStdout.isEmpty()) {
            if (details.length() > 0) {
                details.append("\n");
            }
            details.append(trimmedStdout);
        }
        if (!trimmedStderr.isEmpty()) {
            if (details.length() > 0) {
                details.append("\n");
            }
            details.append(trimmedStderr);
        }
        if (details.length() == 0) {
            details.append(ok ? "Command completed." : "Command failed.");
        }
        if (TokenResultService.ACTION_DISCOVER_LOCAL_API.equals(actionName)) {
            discoveryInFlight = false;
            if (ok && !trimmedStdout.isEmpty()) {
                String discovered = parseDiscoveredPort(trimmedStdout);
                if (!discovered.isEmpty()) {
                    final String discoveredBase = "http://127.0.0.1:" + discovered;
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                JSONObject health = getJson(discoveredBase + "/healthz", 1200);
                                if (!health.optBoolean("ok", false)) {
                                    return;
                                }
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        currentLocalApiBase = discoveredBase;
                                        detailText.setText("Details\nDiscovered local API at " + currentLocalApiBase + " via Termux.");
                                        scheduleRefreshes(250);
                                    }
                                });
                            } catch (Exception ignored) {
                            }
                        }
                    }).start();
                    return;
                }
            }
        }
        detailText.setText("Details\n" + details);
        if (TokenResultService.ACTION_START_PUBLIC_API.equals(actionName)
                || TokenResultService.ACTION_CONTROL_START_PUBLIC.equals(actionName)) {
            scheduleRefreshes(1500, 5000, 12000, 20000, 35000);
        } else if (TokenResultService.ACTION_STOP_PUBLIC_TUNNEL.equals(actionName)
                || TokenResultService.ACTION_CONTROL_STOP_PUBLIC.equals(actionName)) {
            scheduleRefreshes(1500, 5000, 10000, 18000);
        } else if (TokenResultService.ACTION_STOP_ALL.equals(actionName)
                || TokenResultService.ACTION_CONTROL_STOP_ALL.equals(actionName)) {
            scheduleRefreshes(1500, 5000, 10000, 18000);
        }
        if (!ok) {
            Toast.makeText(this, details.toString(), Toast.LENGTH_LONG).show();
        }
        setButtons(true);
    }

    private static String parseDiscoveredPort(String stdout) {
        if (stdout == null) {
            return "";
        }
        String[] lines = stdout.split("\\r?\\n");
        for (String rawLine : lines) {
            String line = rawLine == null ? "" : rawLine.trim();
            if (line.matches("^[0-9]{4,5}$")) {
                return line;
            }
        }
        return "";
    }

    private boolean hasSharedStoragePermission() {
        if (hasAllFilesAccess()) {
            return true;
        }
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean hasAllFilesAccess() {
        return Build.VERSION.SDK_INT >= 30 && Environment.isExternalStorageManager();
    }

    private void requestSharedStoragePermission() {
        if (Build.VERSION.SDK_INT >= 30 && !hasAllFilesAccess()) {
            openAllFilesAccessSettings();
            return;
        }
        if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_STORAGE_PERMISSION
            );
        }
    }

    private boolean isSharedStorageApproval(JSONObject approval) {
        if (approval == null) {
            return false;
        }
        if (approval.optBoolean("shared_storage", false) || approval.optBoolean("requires_local_completion", false)) {
            return true;
        }
        String path = approval.optString("path", "");
        return path.equals("/storage/emulated/0") || path.startsWith("/storage/emulated/0/");
    }

    private void beginSharedStorageLocalExecution(JSONObject approval) {
        pendingLocalApprovalJson = approval.toString();
        if (!hasSharedStoragePermission()) {
            approvalText.setText(
                    "Pending File Approval\nShared-storage approval granted. Phone AI Control now needs storage access so it can execute this local file operation after your confirmation."
            );
            requestSharedStoragePermission();
            return;
        }
        executeSharedStorageApprovalLocally(approval);
    }

    private void executeSharedStorageApprovalLocally(final JSONObject approval) {
        final String approvalId = approval.optString("id", "");
        final String executionToken = approval.optString("execution_token", "");
        if (approvalId.isEmpty() || executionToken.isEmpty()) {
            approvalText.setText("Pending File Approval\nShared-storage approval is missing its one-time execution token.");
            setButtons(true);
            return;
        }
        approvalText.setText("Pending File Approval\nApproved. Executing shared-storage file operation locally through Phone AI Control...");
        setButtons(false);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject localResult = applySharedStorageChange(approval);
                    JSONObject body = new JSONObject();
                    body.put("execution_token", executionToken);
                    body.put("result", localResult);
                    JSONObject response = requestJson(
                            "POST",
                            resolveLocalApiBase(1500) + "/v1/file-approvals/" + approvalId + "/complete-local",
                            body.toString(),
                            6000
                    );
                    JSONObject updatedApproval = response.optJSONObject("approval");
                    final String bodyText = (updatedApproval != null ? updatedApproval.optString("summary", "Shared-storage file change executed locally.") : "Shared-storage file change executed locally.")
                            + "\nExecuted: " + (updatedApproval != null ? updatedApproval.optString("executed_at", "") : "")
                            + "\nPath: " + approval.optString("path", "");
                    pendingLocalApprovalJson = "";
                    currentPendingApprovalId = "";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            approvalText.setText("Pending File Approval\n" + bodyText);
                            setButtons(true);
                            scheduleRefreshes(1500, 5000);
                            requestApprovalReview(true);
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            approvalText.setText("Pending File Approval\nLocal shared-storage execution failed: " + e.getMessage());
                            setButtons(true);
                            requestApprovalReview(true);
                        }
                    });
                }
            }
        }).start();
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
            java.io.OutputStream stream = conn.getOutputStream();
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
                StandardCharsets.UTF_8));
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

    private JSONObject applySharedStorageChange(JSONObject approval) throws Exception {
        String path = approval.optString("path", "").trim();
        if (!(path.equals("/storage/emulated/0") || path.startsWith("/storage/emulated/0/"))) {
            throw new IllegalArgumentException("Approval path is not inside shared storage.");
        }
        File target = new File(path);
        String operation = approval.optString("operation", "");
        if ("write_file".equals(operation)) {
            if (target.exists() && target.isDirectory()) {
                throw new IllegalStateException("Target is a directory: " + path);
            }
            if (target.exists() && !approval.optBoolean("overwrite", false)) {
                throw new IllegalStateException("Target already exists and overwrite was not approved.");
            }
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                throw new IllegalStateException("Could not create parent folder: " + parent.getAbsolutePath());
            }
            byte[] raw = decodeApprovalContent(approval);
            FileOutputStream stream = new FileOutputStream(target, false);
            try {
                stream.write(raw);
                stream.flush();
            } finally {
                stream.close();
            }
            JSONObject result = new JSONObject();
            result.put("path", target.getAbsolutePath());
            result.put("bytes_written", raw.length);
            result.put("sha256", sha256Hex(raw));
            result.put("operation", "write_file");
            result.put("executed_by", "phone_ai_control");
            return result;
        }
        if ("delete_file".equals(operation)) {
            if (!target.exists()) {
                throw new IllegalStateException("Target does not exist: " + path);
            }
            if (target.isDirectory()) {
                throw new IllegalStateException("Refusing to delete directory: " + path);
            }
            long bytesDeleted = target.length();
            if (!target.delete()) {
                throw new IllegalStateException("Could not delete file: " + path);
            }
            JSONObject result = new JSONObject();
            result.put("path", target.getAbsolutePath());
            result.put("bytes_deleted", bytesDeleted);
            result.put("operation", "delete_file");
            result.put("executed_by", "phone_ai_control");
            return result;
        }
        if ("read_file".equals(operation)) {
            if (!target.exists()) {
                throw new IllegalStateException("Target does not exist: " + path);
            }
            if (target.isDirectory()) {
                throw new IllegalStateException("Target is a directory: " + path);
            }
            long maxBytes = clampPositiveLong(approval.optLong("max_bytes", MAX_LOCAL_READ_BYTES), MAX_LOCAL_READ_BYTES, MAX_LOCAL_READ_BYTES);
            long totalBytes = target.length();
            if (totalBytes > maxBytes) {
                throw new IllegalStateException("File is larger than the approved maximum: " + totalBytes + " > " + maxBytes);
            }
            if (totalBytes <= DIRECT_INLINE_READ_BYTES) {
                byte[] raw = readFileUpTo(target, (int) totalBytes);
                JSONObject result = new JSONObject();
                result.put("path", target.getAbsolutePath());
                result.put("bytes_read", raw.length);
                result.put("sha256", sha256Hex(raw));
                result.put("encoding", "base64");
                result.put("content_base64", Base64.encodeToString(raw, Base64.NO_WRAP));
                result.put("mime_type", guessMimeType(target));
                result.put("truncated", false);
                result.put("transfer_mode", "inline");
                result.put("operation", "read_file");
                result.put("executed_by", "phone_ai_control");
                return result;
            }
            return readLargeFileInChunks(approval, target, totalBytes);
        }
        if ("list_dir".equals(operation)) {
            if (!target.exists()) {
                throw new IllegalStateException("Target does not exist: " + path);
            }
            if (!target.isDirectory()) {
                throw new IllegalStateException("Target is not a directory: " + path);
            }
            int maxEntries = clampPositiveInt(approval.optInt("max_entries", MAX_LOCAL_LIST_ENTRIES), MAX_LOCAL_LIST_ENTRIES, MAX_LOCAL_LIST_ENTRIES);
            boolean includeHidden = approval.optBoolean("include_hidden", false);
            File[] children = target.listFiles();
            if (children == null) {
                throw new IllegalStateException("Could not list directory: " + path);
            }
            Arrays.sort(children, new Comparator<File>() {
                @Override
                public int compare(File left, File right) {
                    if (left.isDirectory() != right.isDirectory()) {
                        return left.isDirectory() ? -1 : 1;
                    }
                    return left.getName().compareToIgnoreCase(right.getName());
                }
            });
            JSONArray entries = new JSONArray();
            boolean truncated = false;
            for (File child : children) {
                if (!includeHidden && child.getName().startsWith(".")) {
                    continue;
                }
                JSONObject entry = new JSONObject();
                entry.put("name", child.getName());
                entry.put("path", child.getAbsolutePath());
                entry.put("is_directory", child.isDirectory());
                entry.put("size", child.isFile() ? child.length() : JSONObject.NULL);
                entry.put("mtime_epoch", child.lastModified() / 1000L);
                if (child.isFile()) {
                    entry.put("mime_type", guessMimeType(child));
                }
                entries.put(entry);
                if (entries.length() >= maxEntries) {
                    truncated = children.length > entries.length();
                    break;
                }
            }
            JSONObject result = new JSONObject();
            result.put("path", target.getAbsolutePath());
            result.put("entries", entries);
            result.put("entry_count", entries.length());
            result.put("include_hidden", includeHidden);
            result.put("truncated", truncated);
            result.put("operation", "list_dir");
            result.put("executed_by", "phone_ai_control");
            return result;
        }
        throw new IllegalArgumentException("Unsupported approval operation: " + operation);
    }

    private byte[] decodeApprovalContent(JSONObject approval) throws Exception {
        String encoding = approval.optString("encoding", "text");
        String content = approval.optString("content", "");
        if ("base64".equals(encoding)) {
            return Base64.decode(content, Base64.DEFAULT);
        }
        if ("text".equals(encoding)) {
            return content.getBytes(StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("Unsupported approval encoding: " + encoding);
    }

    private int clampPositiveInt(int value, int fallback, int hardMax) {
        if (value < 1) {
            value = fallback;
        }
        return Math.min(value, hardMax);
    }

    private long clampPositiveLong(long value, long fallback, long hardMax) {
        if (value < 1L) {
            value = fallback;
        }
        return Math.min(value, hardMax);
    }

    private byte[] readFileUpTo(File target, int maxBytes) throws Exception {
        FileInputStream stream = new FileInputStream(target);
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

    private JSONObject readLargeFileInChunks(JSONObject approval, File target, long totalBytes) throws Exception {
        String approvalId = approval.optString("id", "");
        String executionToken = approval.optString("execution_token", "");
        if (approvalId.isEmpty() || executionToken.isEmpty()) {
            throw new IllegalStateException("Large file transfer approval is missing its execution token.");
        }
        int chunkSize = CHUNK_UPLOAD_BYTES;
        int chunkCount = (int) ((totalBytes + chunkSize - 1L) / chunkSize);
        MessageDigest wholeDigest = MessageDigest.getInstance("SHA-256");
        FileInputStream stream = new FileInputStream(target);
        try {
            byte[] buffer = new byte[chunkSize];
            int index = 0;
            while (true) {
                int read = stream.read(buffer);
                if (read < 0) {
                    break;
                }
                byte[] raw = read == buffer.length ? buffer.clone() : Arrays.copyOf(buffer, read);
                wholeDigest.update(raw);
                uploadApprovalChunk(approvalId, executionToken, index, chunkCount, totalBytes, raw);
                index++;
            }
            if (index != chunkCount) {
                throw new IllegalStateException("Chunk upload count mismatch: expected " + chunkCount + ", uploaded " + index);
            }
        } finally {
            stream.close();
        }
        JSONObject result = new JSONObject();
        result.put("path", target.getAbsolutePath());
        result.put("bytes_read", totalBytes);
        result.put("total_bytes", totalBytes);
        result.put("sha256", hexEncode(wholeDigest.digest()));
        result.put("mime_type", guessMimeType(target));
        result.put("transfer_mode", "chunked");
        result.put("chunk_size", chunkSize);
        result.put("chunk_count", chunkCount);
        result.put("operation", "read_file");
        result.put("executed_by", "phone_ai_control");
        return result;
    }

    private JSONObject uploadApprovalChunk(String approvalId, String executionToken, int chunkIndex, int chunkCount, long totalBytes, byte[] raw) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(resolveLocalApiBase(1500) + "/v1/file-approvals/" + approvalId + "/upload-chunk").openConnection();
        conn.setRequestMethod("POST");
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);
        conn.setDoOutput(true);
        conn.setRequestProperty("User-Agent", "PhoneAIControl/1.0");
        conn.setRequestProperty("X-Execution-Token", executionToken);
        conn.setRequestProperty("X-Chunk-Index", String.valueOf(chunkIndex));
        conn.setRequestProperty("X-Chunk-Count", String.valueOf(chunkCount));
        conn.setRequestProperty("X-Total-Bytes", String.valueOf(totalBytes));
        conn.setRequestProperty("X-Chunk-Sha256", sha256Hex(raw));
        conn.setRequestProperty("Content-Type", "application/octet-stream");
        conn.setRequestProperty("Content-Length", String.valueOf(raw.length));
        java.io.OutputStream stream = conn.getOutputStream();
        try {
            stream.write(raw);
            stream.flush();
        } finally {
            stream.close();
        }
        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream(),
                StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        if (code < 200 || code >= 400) {
            throw new IllegalStateException("Chunk upload failed with HTTP " + code + ": " + sb.toString());
        }
        return new JSONObject(sb.toString());
    }

    private String guessMimeType(File file) {
        String mime = URLConnection.guessContentTypeFromName(file.getName());
        if (mime == null || mime.trim().isEmpty()) {
            mime = "application/octet-stream";
        }
        return mime;
    }

    private String sha256Hex(byte[] raw) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(raw);
        return hexEncode(hash);
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

    private boolean hasTermuxPermission() {
        if (Build.VERSION.SDK_INT < 23) {
            return true;
        }
        return checkSelfPermission(RUN_COMMAND_PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestTermuxPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 23 && !hasTermuxPermission()) {
            requestPermissions(new String[]{RUN_COMMAND_PERMISSION}, REQUEST_RUN_COMMAND);
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void refreshPollingUi() {
        if (pollingText == null || pollIntervalInput == null) {
            return;
        }
        int seconds = AutomationSettings.getPollIntervalSeconds(this);
        if (seconds == 0) {
            pollingText.setText("Background Polling\nDisabled");
            if (!"0".contentEquals(pollIntervalInput.getText())) {
                pollIntervalInput.setText("0");
            }
        } else {
            pollingText.setText("Background Polling\nEnabled every " + seconds + "s");
            String current = pollIntervalInput.getText() == null ? "" : pollIntervalInput.getText().toString();
            if (!String.valueOf(seconds).equals(current)) {
                pollIntervalInput.setText(String.valueOf(seconds));
            }
        }
        pollIntervalInput.setSelection(pollIntervalInput.getText().length());
    }

    private void applyPollingInterval() {
        String raw = pollIntervalInput == null || pollIntervalInput.getText() == null
                ? ""
                : pollIntervalInput.getText().toString().trim();
        int requested;
        try {
            requested = raw.isEmpty() ? AutomationSettings.getDefaultPollIntervalSeconds() : Integer.parseInt(raw);
        } catch (Exception e) {
            Toast.makeText(this, "Polling interval must be a number.", Toast.LENGTH_SHORT).show();
            refreshPollingUi();
            return;
        }
        int normalized = AutomationSettings.normalizeSeconds(requested);
        AutomationSettings.setPollIntervalSeconds(this, normalized);
        if (normalized == 0) {
            AutomationLoopService.stop(this);
            if (statusText != null) {
                statusText.removeCallbacks(periodicRefreshRunnable);
            }
            Toast.makeText(this, "Background polling disabled.", Toast.LENGTH_SHORT).show();
        } else {
            AutomationLoopService.start(this);
            restartUiRefreshLoopIfNeeded();
            Toast.makeText(this, "Background polling set to " + normalized + " seconds.", Toast.LENGTH_SHORT).show();
        }
        refreshPollingUi();
    }

    private void restartUiRefreshLoopIfNeeded() {
        if (statusText == null) {
            return;
        }
        statusText.removeCallbacks(periodicRefreshRunnable);
        if (AutomationSettings.isPollingEnabled(this)) {
            statusText.postDelayed(periodicRefreshRunnable, PERIODIC_REFRESH_MS);
        }
    }

    private void openAllFilesAccessSettings() {
        if (Build.VERSION.SDK_INT < 30) {
            openAppSettings();
            return;
        }
        Intent intent = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        intent.setData(Uri.parse("package:" + getPackageName()));
        if (intent.resolveActivity(getPackageManager()) == null) {
            intent = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        }
        startActivityForResult(intent, REQUEST_ALL_FILES_ACCESS);
    }

    private void copyAddress() {
        if (currentPublicUrl == null || currentPublicUrl.isEmpty()) {
            Toast.makeText(this, "No direct schema URL available.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        manager.setPrimaryClip(ClipData.newPlainText("Phone AI Direct Schema URL", currentPublicUrl));
        Toast.makeText(this, "Direct schema URL copied.", Toast.LENGTH_SHORT).show();
    }

    private void ensureNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return;
        }
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel existing = manager.getNotificationChannel(NOTIFICATION_CHANNEL_ID);
        if (existing != null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Phone AI Control",
                NotificationManager.IMPORTANCE_DEFAULT
        );
        channel.setDescription("Notifications created by the Phone AI Control API.");
        manager.createNotificationChannel(channel);
    }

    private void scheduleRefreshes(long... delaysMs) {
        for (long delayMs : delaysMs) {
            statusText.postDelayed(new Runnable() {
                @Override
                public void run() {
                    refreshStatus();
                }
            }, delayMs);
        }
    }

    private void setButtons(boolean enabled) {
        startButton.setEnabled(enabled);
        stopPublicButton.setEnabled(enabled);
        stopAllButton.setEnabled(enabled);
        if (applyPollingButton != null) {
            applyPollingButton.setEnabled(enabled);
        }
        if (pollIntervalInput != null) {
            pollIntervalInput.setEnabled(enabled);
        }
    }

    private void openUsageAccessSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open Usage Access settings: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openNotificationAccessSettings() {
        try {
            Intent intent = new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS");
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open Notification Access settings: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void requestContactsAndCallLogAccess() {
        if (Build.VERSION.SDK_INT < 23) {
            Toast.makeText(this, "Contacts and call log access are already available on this Android version.", Toast.LENGTH_SHORT).show();
            return;
        }
        requestPermissions(
                new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG},
                REQUEST_CONTACTS_AND_CALLS
        );
    }

    private void requestIgnoreBatteryOptimizations() {
        try {
            Intent intent;
            if (isIgnoringBatteryOptimizations()) {
                intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            } else {
                intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
                intent.setData(Uri.parse("package:" + getPackageName()));
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open battery optimization settings: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openUnknownAppSourcesSettings() {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open install-unknown-apps settings: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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

    private boolean hasNotificationAccess() {
        return NotificationAccessStore.hasNotificationAccess(this);
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
        payload.put("source", "phone_ai_control");
        payload.put("captured_epoch_ms", System.currentTimeMillis());
        return payload;
    }

    private JSONObject collectUsageSnapshot() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("source", "phone_ai_control");
        payload.put("captured_epoch_ms", System.currentTimeMillis());
        payload.put("usage_access_granted", hasUsageAccess());

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
        detailText.post(new Runnable() {
            @Override
            public void run() {
                detailText.setText("Details\nDownloading pending APK install request for " + apkName + "...");
            }
        });
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
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                detailText.setText("Details\nPhone AI Control needs permission to install unknown apps. Opening the Android settings page for this app now.");
                                Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getPackageName()));
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                            }
                        });
                        completeInstallRequestAsync(requestId, "needs_unknown_sources_permission", result, "Phone AI Control cannot request package installs yet.");
                        return;
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                Intent intent = new Intent(Intent.ACTION_VIEW);
                                intent.setDataAndType(apkUri, "application/vnd.android.package-archive");
                                intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                                startActivity(intent);
                                detailText.setText("Details\nAPK installer launched for " + apkName + ".");
                            } catch (Exception launchError) {
                                detailText.setText("Details\nAPK installer could not be launched: " + launchError.getMessage());
                            }
                        }
                    });
                    completeInstallRequestAsync(requestId, "installer_launched", result, null);
                } catch (Exception e) {
                    completeInstallRequestAsync(requestId, "failed", null, e.getMessage());
                    detailText.post(new Runnable() {
                        @Override
                        public void run() {
                            detailText.setText("Details\nDedicated APK install request failed.");
                        }
                    });
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
                    scheduleRefreshes(1500, 5000);
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
                    scheduleRefreshes(1500, 5000);
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
        result.put("executed_by", "phone_ai_control");
        return result;
    }

    private JSONObject executeAlarmAction(final JSONObject payload) throws Exception {
        final Exception[] error = new Exception[1];
        final JSONObject result = new JSONObject();
        final CountDownLatch latch = new CountDownLatch(1);
        final ArrayList<Integer> normalizedDays = AlarmStateStore.normalizeAlarmDays(payload.optJSONArray("days"));
        runOnUiThread(new Runnable() {
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
                    if (!normalizedDays.isEmpty()) {
                        intent.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, normalizedDays);
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(intent);
                    result.put("hour", payload.getInt("hour"));
                    result.put("minutes", payload.getInt("minutes"));
                    result.put("message", payload.optString("message", ""));
                    result.put("note", payload.optString("message", ""));
                    result.put("calendar_days", AlarmStateStore.calendarDaysJson(normalizedDays));
                    result.put("weekdays", AlarmStateStore.weekdayNamesJson(normalizedDays));
                    result.put("executed_by", "phone_ai_control");
                    AlarmStateStore.recordManagedAlarm(MainActivity.this, payload, result);
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
        runOnUiThread(new Runnable() {
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
                    result.put("executed_by", "phone_ai_control");
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
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                try {
                    String packageName = payload.optString("package_name", "").trim();
                    if (packageName.isEmpty()) {
                        throw new IllegalArgumentException("package_name is required");
                    }
                    PackageManager packageManager = getPackageManager();
                    Intent intent = packageManager.getLaunchIntentForPackage(packageName);
                    if (intent == null) {
                        throw new IllegalStateException("No launch intent for package: " + packageName);
                    }
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
                    startActivity(intent);
                    result.put("package_name", packageName);
                    result.put("launched", true);
                    result.put("executed_by", "phone_ai_control");
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

    private byte[] downloadBinary(String rawUrl, int timeoutMs) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(rawUrl).openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(timeoutMs);
        conn.setReadTimeout(timeoutMs);
        conn.setRequestProperty("User-Agent", "PhoneAIControl/1.0");
        conn.setRequestProperty("Accept", "*/*");
        int code = conn.getResponseCode();
        InputStream input = code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream();
        if (code < 200 || code >= 400) {
            BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            reader.close();
            throw new IllegalStateException("HTTP " + code + ": " + sb.toString());
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try {
            byte[] chunk = new byte[8192];
            int read;
            while ((read = input.read(chunk)) >= 0) {
                if (read == 0) {
                    continue;
                }
                buffer.write(chunk, 0, read);
            }
            return buffer.toByteArray();
        } finally {
            input.close();
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
            values.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            values.put(MediaStore.MediaColumns.MIME_TYPE, "application/vnd.android.package-archive");
            values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
            values.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("Could not create a Downloads entry for the APK");
            }
            OutputStream output = getContentResolver().openOutputStream(uri, "w");
            if (output == null) {
                throw new IllegalStateException("Could not open APK output stream");
            }
            try {
                output.write(raw);
                output.flush();
            } finally {
                output.close();
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            getContentResolver().update(uri, done, null, null);
            return uri;
        }
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists() && !dir.mkdirs() && !dir.exists()) {
            throw new IllegalStateException("Could not create Downloads directory");
        }
        File target = new File(dir, displayName);
        FileOutputStream stream = new FileOutputStream(target, false);
        try {
            stream.write(raw);
            stream.flush();
        } finally {
            stream.close();
        }
        return Uri.fromFile(target);
    }

    private void maybeAutoReviewPendingApprovals() {
        if (!AUTO_APPROVE_SHARED_STORAGE) {
            return;
        }
        if (pendingLocalApprovalJson != null && !pendingLocalApprovalJson.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (autoApprovalRequestInFlight || now - lastAutoApprovalKickMs < AUTO_APPROVAL_MIN_INTERVAL_MS) {
            return;
        }
        autoApprovalRequestInFlight = true;
        lastAutoApprovalKickMs = now;
        requestApprovalReview(true);
    }

    private String normalizePublicUrl(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed)) {
            return "";
        }
        return trimmed;
    }

    private String buildGptSchemaUrl(String publicUrl) {
        String normalized = normalizePublicUrl(publicUrl);
        if (normalized.isEmpty()) {
            return "";
        }
        return normalized + "/openapi-gpt.json";
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
