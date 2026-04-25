package com.example.phoneaicontrol;

import android.Manifest;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.AlarmManager;
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
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.PowerManager;
import android.provider.CallLog;
import android.provider.AlarmClock;
import android.provider.ContactsContract;
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
import android.widget.ImageView;
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
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String PHONE_AI_RUNTIME_FILE = "/storage/emulated/0/Android/media/com.example.phoneaicontrol/runtime.json";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final int REQUEST_RUN_COMMAND = 42;
    private static final int REQUEST_CONFIRM_CREDENTIAL = 43;
    private static final int REQUEST_STORAGE_PERMISSION = 44;
    private static final int REQUEST_ALL_FILES_ACCESS = 45;
    private static final int REQUEST_CONTACTS_AND_CALL_LOG_PERMISSION = 46;
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
    private static final long NOTIFICATION_PUSH_MIN_INTERVAL_MS = 10000L;
    private static final long CONTACTS_PUSH_MIN_INTERVAL_MS = 60000L;
    private static final long MISSED_CALLS_PUSH_MIN_INTERVAL_MS = 30000L;
    private static final long DISCOVERY_RETRY_MIN_INTERVAL_MS = 3000L;
    private static final long PERIODIC_REFRESH_MS = 15000L;
    private static final long PUBLIC_RECONNECT_MIN_INTERVAL_MS = 45000L;
    private static final int PUBLIC_PROBE_TIMEOUT_MS = 5000;
    private static final String NOTIFICATION_CHANNEL_ID = "phone_ai_control_alerts";

    private TextView statusText;
    private TextView addressText;
    private TextView publicText;
    private TextView detailText;
    private TextView relayRepoText;
    private TextView approvalText;
    private TextView pollingText;
    private TextView modeText;
    private TextView githubAccountText;
    private TextView phoneTokenInfoText;
    private TextView githubOauthInfoText;
    private EditText pollIntervalInput;
    private EditText githubOauthClientIdInput;
    private ImageView githubAvatarView;
    private Button startButton;
    private Button stopPublicButton;
    private Button stopAllButton;
    private Button applyPollingButton;
    private Button githubRepoButton;
    private Button copyGithubTokenButton;
    private Button connectGithubButton;
    private Button importGithubTokenButton;
    private Button resetGithubBindingButton;
    private Button githubLoginSettingsButton;
    private Button githubLogoutSettingsButton;
    private Button rotatePhoneTokenButton;
    private Button copyPhoneTokenSettingsButton;
    private Button saveGithubOauthClientIdButton;
    private Button openGithubOauthGuideButton;
    private Button modeTraditionalButton;
    private Button modeGithubButton;
    private Button navStatusButton;
    private Button navControlButton;
    private Button navSettingsButton;
    private LinearLayout statusSection;
    private LinearLayout controlSection;
    private LinearLayout settingsSection;
    private int currentSection = 0;
    private String currentPublicUrl = "";
    private String currentPreferredAccessUrl = "";
    private String currentLocalApiBase = DEFAULT_LOCAL_API;
    private String currentPendingApprovalId = "";
    private String pendingLocalApprovalJson = "";
    private String pendingGitHubRelayToken = "";
    private String currentGitHubAvatarUrl = "";
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
    private long lastNotificationPushMs = 0L;
    private boolean notificationPushInFlight = false;
    private long lastContactsPushMs = 0L;
    private boolean contactsPushInFlight = false;
    private long lastMissedCallsPushMs = 0L;
    private boolean missedCallsPushInFlight = false;
    private long lastPublicReconnectKickMs = 0L;
    private static final String OAUTH_PREFS = "phone_ai_oauth";
    private static final String PREF_OAUTH_WIZARD_ACK = "oauth_wizard_ack";
    private final Runnable periodicRefreshRunnable = new Runnable() {
        @Override
        public void run() {
            refreshStatus();
            if (statusText != null) {
                statusText.postDelayed(this, PERIODIC_REFRESH_MS);
            }
        }
    };

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

    private static final int SECTION_STATUS = 0;
    private static final int SECTION_CONTROL = 1;
    private static final int SECTION_SETTINGS = 2;

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
            if (requestCode == REQUEST_CONTACTS_AND_CALL_LOG_PERMISSION) {
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
                Toast.makeText(this, granted ? "Contacts and call log access granted." : "Contacts or call log access was denied.", Toast.LENGTH_SHORT).show();
                if (granted) {
                    pushContactsSnapshotIfNeeded();
                    pushMissedCallsSnapshotIfNeeded();
                }
                refreshStatus();
            }
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
        LinearLayout shell = new LinearLayout(this);
        shell.setOrientation(LinearLayout.VERTICAL);
        shell.setBackgroundColor(Color.parseColor("#0C1015"));

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f));

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(16));
        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        shell.addView(scroll);

        LinearLayout modePanel = panel();
        modePanel.addView(sectionTitle("连接模式 (Connection Mode)"));
        LinearLayout modeRow = new LinearLayout(this);
        modeRow.setOrientation(LinearLayout.HORIZONTAL);
        modeRow.setPadding(0, dp(6), 0, 0);
        modeTraditionalButton = modeButton("传统直连");
        modeGithubButton = modeButton("GitHub Relay");
        LinearLayout.LayoutParams leftModeParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        leftModeParams.setMargins(0, 0, dp(8), 0);
        modeTraditionalButton.setLayoutParams(leftModeParams);
        LinearLayout.LayoutParams rightModeParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        modeGithubButton.setLayoutParams(rightModeParams);
        modeTraditionalButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyRelayMode(AutomationSettings.RELAY_MODE_TRADITIONAL);
            }
        });
        modeGithubButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyRelayMode(AutomationSettings.RELAY_MODE_GITHUB);
            }
        });
        modeRow.addView(modeTraditionalButton);
        modeRow.addView(modeGithubButton);
        modePanel.addView(modeRow);
        root.addView(modePanel);

        statusSection = sectionContainer();
        controlSection = sectionContainer();
        settingsSection = sectionContainer();
        root.addView(statusSection);
        root.addView(controlSection);
        root.addView(settingsSection);

        LinearLayout statusPanel = panel();
        statusPanel.addView(sectionTitle("状态 (Status)"));
        modeText = card("模式 (Mode)", "Traditional Direct");
        statusText = card("Local API", "Checking...");
        publicText = card("Public Exposure", "Checking...");
        statusPanel.addView(modeText);
        statusPanel.addView(statusText);
        statusPanel.addView(publicText);
        statusSection.addView(statusPanel);

        LinearLayout githubPanel = panel();
        githubPanel.addView(sectionTitle("GitHub 仓库"));
        relayRepoText = card("GitHub Relay", "Waiting for the first check.");
        githubPanel.addView(relayRepoText);
        connectGithubButton = actionButton("登录 GitHub / 创建 Token", true);
        importGithubTokenButton = actionButton("导入剪贴板中的 GitHub Token", false);
        Button copyButton = actionButton("复制地址 (URL)", false);
        copyGithubTokenButton = actionButton("复制 GitHub Token (20s)", true);
        githubRepoButton = actionButton("检测 / 创建中继仓库", true);
        resetGithubBindingButton = actionButton("清空 GitHub 绑定", false);
        githubPanel.addView(connectGithubButton);
        githubPanel.addView(importGithubTokenButton);
        githubPanel.addView(copyGithubTokenButton);
        githubPanel.addView(copyButton);
        githubPanel.addView(githubRepoButton);
        githubPanel.addView(resetGithubBindingButton);
        statusSection.addView(githubPanel);

        LinearLayout addressPanel = panel();
        addressPanel.addView(sectionTitle("当前地址 (Current URL)"));
        addressText = card("Phone AI API URL", "Unknown");
        addressPanel.addView(addressText);
        statusSection.addView(addressPanel);

        LinearLayout approvalPanel = panel();
        approvalPanel.addView(sectionTitle("待处理文件审批 (Pending Approval)"));
        approvalText = card("Shared Storage", "Auto mode is enabled. Shared-storage file changes will be approved and executed automatically while Phone AI Control is open.");
        approvalPanel.addView(approvalText);
        statusSection.addView(approvalPanel);

        LinearLayout pollingPanel = panel();
        pollingPanel.addView(sectionTitle("后台轮询 (Background Polling)"));
        pollingText = card("Polling", "Checking...");
        pollingPanel.addView(pollingText);
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
        pollIntervalInput.setHint("秒 / seconds");
        pollIntervalInput.setTextColor(Color.WHITE);
        pollIntervalInput.setHintTextColor(Color.parseColor("#68717E"));
        pollIntervalInput.setBackground(makeRoundedDrawable("#12161C", "#2A303A", 1));
        pollIntervalInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        pollIntervalInput.setLayoutParams(inputParams);
        pollRow.addView(pollIntervalInput);

        applyPollingButton = actionButton("应用轮询", true);
        LinearLayout.LayoutParams applyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        applyParams.setMargins(dp(8), 0, 0, 0);
        applyPollingButton.setLayoutParams(applyParams);
        applyPollingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                applyPollingInterval();
            }
        });
        pollRow.addView(applyPollingButton);
        pollingPanel.addView(pollRow);
        statusSection.addView(pollingPanel);

        LinearLayout controlPanel = panel();
        controlPanel.addView(sectionTitle("控制 (Control)"));
        startButton = actionButton("Start / Restart Public API", true);
        stopPublicButton = actionButton("Stop Public Tunnel Only", false);
        stopAllButton = actionButton("Stop API And Tunnel", false);
        Button refreshButton = actionButton("Refresh Status", false);
        Button copyTokenButton = actionButton("Copy Token (20s)", false);
        controlPanel.addView(startButton);
        controlPanel.addView(stopPublicButton);
        controlPanel.addView(stopAllButton);
        controlPanel.addView(refreshButton);
        controlPanel.addView(copyTokenButton);
        controlSection.addView(controlPanel);

        LinearLayout settingsPanel = panel();
        settingsPanel.addView(sectionTitle("设置 (Settings)"));
        LinearLayout accountPanel = panel();
        accountPanel.addView(sectionTitle("GitHub 账户 (GitHub Account)"));
        LinearLayout accountRow = new LinearLayout(this);
        accountRow.setOrientation(LinearLayout.HORIZONTAL);
        accountRow.setGravity(Gravity.CENTER_VERTICAL);
        accountRow.setPadding(0, 0, 0, dp(8));
        githubAvatarView = new ImageView(this);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(44), dp(44));
        avatarParams.setMargins(0, 0, dp(10), 0);
        githubAvatarView.setLayoutParams(avatarParams);
        githubAvatarView.setImageResource(android.R.drawable.sym_def_app_icon);
        accountRow.addView(githubAvatarView);
        githubAccountText = text("Not connected yet.\nUse the login button below to link a GitHub relay account.", 14, Color.WHITE, false);
        LinearLayout.LayoutParams accountTextParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        githubAccountText.setLayoutParams(accountTextParams);
        accountRow.addView(githubAccountText);
        githubLogoutSettingsButton = dangerButton("Log out");
        LinearLayout.LayoutParams logoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        logoutParams.setMargins(dp(10), 0, 0, 0);
        githubLogoutSettingsButton.setLayoutParams(logoutParams);
        accountRow.addView(githubLogoutSettingsButton);
        accountPanel.addView(accountRow);
        githubLoginSettingsButton = actionButton("Login / Relink GitHub Account", true);
        accountPanel.addView(githubLoginSettingsButton);
        settingsPanel.addView(accountPanel);

        LinearLayout oauthPanel = panel();
        oauthPanel.addView(sectionTitle("GitHub OAuth Device Flow"));
        githubOauthInfoText = card(
                "OAuth Client ID",
                "Leave this blank to use the built-in public Client ID. Paste your own Client ID here only if you want this app to use your own GitHub OAuth App."
        );
        oauthPanel.addView(githubOauthInfoText);
        githubOauthClientIdInput = new EditText(this);
        githubOauthClientIdInput.setSingleLine(true);
        githubOauthClientIdInput.setInputType(InputType.TYPE_CLASS_TEXT);
        githubOauthClientIdInput.setHint("Optional custom GitHub OAuth Client ID");
        githubOauthClientIdInput.setTextColor(Color.WHITE);
        githubOauthClientIdInput.setHintTextColor(Color.parseColor("#68717E"));
        githubOauthClientIdInput.setBackground(makeRoundedDrawable("#12161C", "#2A303A", 1));
        githubOauthClientIdInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        oauthPanel.addView(githubOauthClientIdInput);
        saveGithubOauthClientIdButton = actionButton("Save / Clear Custom Client ID", false);
        openGithubOauthGuideButton = actionButton("Open GitHub OAuth Setup Guide", false);
        oauthPanel.addView(saveGithubOauthClientIdButton);
        oauthPanel.addView(openGithubOauthGuideButton);
        settingsPanel.addView(oauthPanel);

        LinearLayout tokenPanel = panel();
        tokenPanel.addView(sectionTitle("Phone API Token"));
        phoneTokenInfoText = card(
                "Bearer Token",
                "This token is created once on first setup and stays the same across tunnel changes. It only changes if you rotate it manually."
        );
        tokenPanel.addView(phoneTokenInfoText);
        copyPhoneTokenSettingsButton = actionButton("Copy Phone API Token (20s)", false);
        rotatePhoneTokenButton = actionButton("Rotate Phone API Token", false);
        tokenPanel.addView(copyPhoneTokenSettingsButton);
        tokenPanel.addView(rotatePhoneTokenButton);
        settingsPanel.addView(tokenPanel);

        Button allFilesButton = actionButton("Open All Files Access Settings", false);
        Button usageAccessButton = actionButton("Open Usage Access Settings", false);
        Button notificationAccessButton = actionButton("Open Notification Access Settings", false);
        Button contactsCallLogButton = actionButton("Grant Contacts And Call Log Access", false);
        Button batteryOptimizationButton = actionButton("Request Battery Optimization Exemption", false);
        Button unknownSourcesButton = actionButton("Open Install Unknown Apps Settings", false);
        Button settingsButton = actionButton("Open Phone AI Control Settings", false);
        settingsPanel.addView(allFilesButton);
        settingsPanel.addView(usageAccessButton);
        settingsPanel.addView(notificationAccessButton);
        settingsPanel.addView(contactsCallLogButton);
        settingsPanel.addView(batteryOptimizationButton);
        settingsPanel.addView(unknownSourcesButton);
        settingsPanel.addView(settingsButton);
        detailText = card("运行诊断 (Diagnostics)", "Waiting for the first check.");
        settingsPanel.addView(detailText);
        TextView note = text(
                "Security note: tokens stay in local app-controlled storage. Copied token text is cleared from clipboard after 20 seconds. Each device gets its own randomly generated Phone API token on first setup.",
                12,
                Color.parseColor("#8D96A2"),
                false);
        note.setPadding(0, dp(10), 0, 0);
        settingsPanel.addView(note);
        settingsSection.addView(settingsPanel);

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
        if (copyPhoneTokenSettingsButton != null) {
            copyPhoneTokenSettingsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    copyToken();
                }
            });
        }
        if (rotatePhoneTokenButton != null) {
            rotatePhoneTokenButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    rotatePhoneApiToken();
                }
            });
        }
        connectGithubButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startGitHubLoginFlow();
            }
        });
        if (githubLoginSettingsButton != null) {
            githubLoginSettingsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    startGitHubLoginFlow();
                }
            });
        }
        if (saveGithubOauthClientIdButton != null) {
            saveGithubOauthClientIdButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    saveGitHubOauthClientIdFromInput();
                }
            });
        }
        if (openGithubOauthGuideButton != null) {
            openGithubOauthGuideButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    openGitHubOAuthAppGuide();
                }
            });
        }
        importGithubTokenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                importGitHubTokenFromClipboard();
            }
        });
        copyGithubTokenButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                copyGitHubToken();
            }
        });
        githubRepoButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                checkOrCreateGitHubRelayRepo();
            }
        });
        resetGithubBindingButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                resetGitHubRelayBinding();
            }
        });
        if (githubLogoutSettingsButton != null) {
            githubLogoutSettingsButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    resetGitHubRelayBinding();
                }
            });
        }
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
                requestContactsAndCallLogPermissions();
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

        LinearLayout navBar = new LinearLayout(this);
        navBar.setOrientation(LinearLayout.HORIZONTAL);
        navBar.setPadding(dp(10), dp(8), dp(10), dp(10));
        navBar.setBackground(makeRoundedDrawable("#11151B", "#11151B", 0));
        navStatusButton = navButton("状态");
        navControlButton = navButton("控制");
        navSettingsButton = navButton("设置");
        navStatusButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSection(SECTION_STATUS);
            }
        });
        navControlButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSection(SECTION_CONTROL);
            }
        });
        navSettingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showSection(SECTION_SETTINGS);
            }
        });
        navBar.addView(navStatusButton, bottomNavParams(true));
        navBar.addView(navControlButton, bottomNavParams(true));
        navBar.addView(navSettingsButton, bottomNavParams(false));
        shell.addView(navBar);

        setContentView(shell);
        showSection(SECTION_STATUS);
        refreshPollingUi();
        refreshRelayModeUi();
    }

    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setPadding(0, dp(5), 0, dp(5));
        if (bold) {
            view.setTypeface(Typeface.DEFAULT_BOLD);
        }
        return view;
    }

    private LinearLayout sectionContainer() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private LinearLayout panel() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setBackground(makeRoundedDrawable("#171B22", "#171B22", 0));
        layout.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, 0, 0, dp(12));
        layout.setLayoutParams(params);
        return layout;
    }

    private TextView sectionTitle(String value) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(15);
        view.setTypeface(Typeface.DEFAULT_BOLD);
        view.setTextColor(Color.parseColor("#F4F7FB"));
        view.setPadding(0, 0, 0, dp(6));
        return view;
    }

    private TextView card(String title, String body) {
        TextView view = text(title + "\n" + body, 15, Color.parseColor("#E6EBF3"), false);
        view.setLineSpacing(0f, 1.12f);
        view.setBackground(makeRoundedDrawable("#11151B", "#262C35", 1));
        view.setPadding(dp(14), dp(12), dp(14), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, 0);
        view.setLayoutParams(params);
        return view;
    }

    private Button actionButton(String label, boolean accent) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(accent ? Color.parseColor("#081108") : Color.parseColor("#E7ECF2"));
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding(dp(10), dp(12), dp(10), dp(12));
        button.setBackground(makeRoundedDrawable(accent ? "#4AE02C" : "#2B313B", accent ? "#4AE02C" : "#2B313B", 0));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(10), 0, 0);
        button.setLayoutParams(params);
        return button;
    }

    private Button dangerButton(String label) {
        Button button = actionButton(label, false);
        button.setTextColor(Color.WHITE);
        button.setBackground(makeRoundedDrawable("#D44949", "#D44949", 0));
        return button;
    }

    private Button modeButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding(dp(10), dp(12), dp(10), dp(12));
        return button;
    }

    private Button navButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(14);
        button.setTypeface(Typeface.DEFAULT_BOLD);
        button.setPadding(dp(8), dp(10), dp(8), dp(10));
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        return button;
    }

    private LinearLayout.LayoutParams bottomNavParams(boolean rightMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f);
        if (rightMargin) {
            params.setMargins(0, 0, dp(8), 0);
        }
        return params;
    }

    private GradientDrawable makeRoundedDrawable(String fillHex, String strokeHex, int strokeDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(fillHex));
        drawable.setCornerRadius(dp(12));
        if (strokeDp > 0) {
            drawable.setStroke(dp(strokeDp), Color.parseColor(strokeHex));
        }
        return drawable;
    }

    private void refreshStatus() {
        refreshPollingUi();
        statusText.setText("状态 (Status)\nChecking...");
        publicText.setText("公网暴露 (Public Exposure)\nChecking...");
        new Thread(new Runnable() {
            @Override
            public void run() {
            String localStatus;
            String publicStatus = "Disabled or unknown";
            String details = "";
            String repoSummary = "";
            String publicUrl = "";
            JSONObject githubAccountProfile = null;
            boolean shouldReconnectPublicTunnel = false;
            String reconnectReason = "";
            final boolean allFiles = hasAllFilesAccess();
            final boolean usageAccess = hasUsageAccess();
            final boolean notificationAccess = hasNotificationAccess();
            final boolean batteryOptimizationIgnored = isIgnoringBatteryOptimizations();
            final boolean installUnknownAppsAllowed = canRequestPackageInstallsCompat();
            final boolean contactsAccess = hasContactsAccess();
            final boolean callLogAccess = hasCallLogAccess();
            final boolean githubRelayMode = AutomationSettings.isGitHubRelayMode(MainActivity.this);
            JSONObject permissionState = collectPermissionState();
            String syncLocalApiBase = currentLocalApiBase;
            boolean syncLocalApiOk = false;
            String syncPublicProbeDetail = "";
            boolean syncPublicReachable = false;
            JSONObject syncHealth = null;
            try {
                String localApiBase = resolveLocalApiBase(2500);
                JSONObject health = getJson(localApiBase + "/healthz", 2500);
                localStatus = health.optBoolean("ok") ? "Online" : "Unexpected response";
                currentLocalApiBase = localApiBase;
                syncLocalApiBase = localApiBase;
                syncLocalApiOk = "Online".equals(localStatus);
                syncHealth = health;
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
                    PublicTunnelProbeResult probe = probePublicTunnel(publicUrl, PUBLIC_PROBE_TIMEOUT_MS);
                    syncPublicReachable = probe.reachable;
                    syncPublicProbeDetail = probe.detail;
                    if (probe.reachable) {
                        publicStatus = "Enabled and reachable";
                    } else {
                        publicStatus = "Enabled, but tunnel is unavailable";
                        details += "\npublic self-check failed: " + probe.detail;
                        if (probe.shouldReconnect()) {
                            shouldReconnectPublicTunnel = true;
                            reconnectReason = probe.detail;
                        }
                    }
                } else if ((lt || cf) && publicUrl.isEmpty()) {
                    publicStatus = "Starting tunnel...";
                } else {
                    publicStatus = "Off";
                    syncPublicReachable = false;
                    syncPublicProbeDetail = enabled ? "Public URL missing while tunnel reports enabled." : "Public exposure disabled.";
                }
            } catch (Exception e) {
                localStatus = "Offline";
                syncLocalApiOk = false;
                syncPublicReachable = false;
                syncPublicProbeDetail = e.getClass().getSimpleName() + ": " + e.getMessage();
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
                            "cat " + TERMUX_HOME + "/ai-phone-api/port.txt 2>/dev/null || true",
                            TokenResultService.ACTION_DISCOVER_LOCAL_API,
                            null
                    );
                }
            }
            if (githubRelayMode) {
                try {
                    JSONObject relayState = GitHubRelaySync.buildDeviceState(
                            MainActivity.this,
                            "main_activity",
                            syncLocalApiBase,
                            syncLocalApiOk,
                            localStatus,
                            syncHealth,
                            syncPublicReachable,
                            syncPublicProbeDetail,
                            permissionState
                    );
                    GitHubRelaySync.maybeSyncCurrentDevice(MainActivity.this, relayState, false);
                } catch (Exception ignored) {
                }
                repoSummary = GitHubRelaySync.describeForUi(MainActivity.this);
            } else {
                repoSummary = "traditional direct mode\nGitHub relay is idle in this mode.";
            }
            githubAccountProfile = GitHubRelaySync.loadGitHubAccountProfile(MainActivity.this, true);
            String finalLocalStatus = localStatus;
            String finalPublicStatus = publicStatus;
            String finalDetails = details;
            String finalRepoSummary = repoSummary;
            String finalPublicUrl = publicUrl;
            final String finalPreferredAccessUrl = GitHubRelaySync.getPreferredAccessUrl(MainActivity.this, publicUrl);
            final JSONObject finalGithubAccountProfile = githubAccountProfile;
            final boolean finalShouldReconnectPublicTunnel = shouldReconnectPublicTunnel;
            final String finalReconnectReason = reconnectReason;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    currentPublicUrl = finalPublicUrl == null ? "" : finalPublicUrl;
                    currentPreferredAccessUrl = finalPreferredAccessUrl == null ? "" : finalPreferredAccessUrl;
                    statusText.setText("状态 (Status)\nLocal API: " + finalLocalStatus);
                    if (AutomationSettings.isGitHubRelayMode(MainActivity.this)
                            && !currentPreferredAccessUrl.isEmpty()
                            && !currentPreferredAccessUrl.equals(currentPublicUrl)) {
                        addressText.setText(
                                "当前地址 (URL)\n"
                                        + currentPreferredAccessUrl
                                        + "\nvia bridge; current phone tunnel: "
                                        + (currentPublicUrl.isEmpty() ? "unknown" : currentPublicUrl)
                        );
                    } else {
                        addressText.setText("当前地址 (URL)\n" + (currentPreferredAccessUrl.isEmpty() ? "None" : currentPreferredAccessUrl));
                    }
                    publicText.setText("公网暴露 (Public Exposure)\n" + finalPublicStatus);
                    if (relayRepoText != null) {
                        relayRepoText.setText("GitHub Relay\n" + finalRepoSummary);
                    }
                    detailText.setText("运行诊断 (Diagnostics)\n" + finalDetails);
                    refreshPollingUi();
                    refreshRelayModeUi();
                    refreshGitHubAccountUi(finalGithubAccountProfile);
                    if (!"Online".equals(finalLocalStatus)) {
                        currentPendingApprovalId = "";
                        approvalText.setText("Pending Approval\nLocal API is offline.");
                    } else if ("Pending Approval\nLocal API is offline.".contentEquals(approvalText.getText())) {
                        approvalText.setText("Pending Approval\nAuto mode is enabled. Shared-storage file changes will be approved and executed automatically while Phone AI Control is open.");
                    }
                    if ("Online".equals(finalLocalStatus)
                            && finalShouldReconnectPublicTunnel
                            && requestPublicTunnelReconnectSilently(finalReconnectReason)) {
                        String reconnectDetails = detailText.getText() == null
                                ? ""
                                : detailText.getText().toString().replaceFirst("^运行诊断 \\(Diagnostics\\)\\n", "");
                        if (!reconnectDetails.isEmpty()) {
                            reconnectDetails += "\n";
                        }
                        reconnectDetails += "auto-reconnect requested after public tunnel failure: " + finalReconnectReason;
                        currentPublicUrl = "";
                        publicText.setText("公网暴露 (Public Exposure)\nEnabled, but tunnel was unhealthy - reconnecting...");
                        addressText.setText("当前地址 (URL)\nRefreshing tunnel...");
                        detailText.setText("运行诊断 (Diagnostics)\n" + reconnectDetails);
                    }
                    pushBatteryStatusIfNeeded();
                    pushUsageSnapshotIfNeeded();
                    pushNotificationsSnapshotIfNeeded();
                    pushContactsSnapshotIfNeeded();
                    pushMissedCallsSnapshotIfNeeded();
                    maybeAutoReviewPendingApprovals();
                    maybeAutoHandleInstallRequests();
                    maybeAutoHandleDeviceActions();
                }
            });
            }
        }).start();
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
        lastPublicReconnectKickMs = System.currentTimeMillis();
        setButtons(false);
        detailText.setText("运行诊断 (Diagnostics)\nChecking whether the local API is already online...");
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
        detailText.setText("运行诊断 (Diagnostics)\nChecking whether the local API is online before sending the stop request...");
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
                                detailText.setText("运行诊断 (Diagnostics)\n" + offlineMessage);
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

    private boolean requestTokenBackedAction(String actionName, String approvalId, String toast) {
        if (!hasTermuxPermission()) {
            requestTermuxPermissionIfNeeded();
            Toast.makeText(this, "Termux command permission is required.", Toast.LENGTH_LONG).show();
            setButtons(true);
            return false;
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
            return true;
        } catch (Exception e) {
            Toast.makeText(this, "Could not request approval action: " + e.getMessage(), Toast.LENGTH_LONG).show();
            setButtons(true);
            return false;
        }
    }

    private boolean requestPublicTunnelReconnectSilently(String reason) {
        long now = System.currentTimeMillis();
        if (!hasTermuxPermission()) {
            return false;
        }
        if (now - lastPublicReconnectKickMs < PUBLIC_RECONNECT_MIN_INTERVAL_MS) {
            return false;
        }
        lastPublicReconnectKickMs = now;
        boolean started = requestTokenBackedAction(
                TokenResultService.ACTION_CONTROL_START_PUBLIC,
                null,
                null
        );
        if (!started) {
            lastPublicReconnectKickMs = 0L;
        }
        return started;
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
        if (TokenResultService.ACTION_READ_PHONE_API_TOKEN.equals(actionName)) {
            if (!ok) {
                pendingGitHubRelayToken = "";
                String failure = message == null || message.trim().isEmpty() ? "Phone API token read failed." : message.trim();
                detailText.setText("运行诊断 (Diagnostics)\n" + failure);
                Toast.makeText(this, failure, Toast.LENGTH_LONG).show();
                setButtons(true);
                return;
            }
            final String githubToken = pendingGitHubRelayToken == null ? "" : pendingGitHubRelayToken.trim();
            final String phoneToken = trimmedStdout;
            detailText.setText("运行诊断 (Diagnostics)\nGitHub token imported. Creating or checking the relay repository now...");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final JSONObject state = GitHubRelaySync.bootstrapFromGitHubToken(MainActivity.this, githubToken, phoneToken);
                    pendingGitHubRelayToken = "";
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (relayRepoText != null) {
                                relayRepoText.setText("GitHub 仓库\n" + GitHubRelaySync.describeForUi(MainActivity.this));
                            }
                            String statusMessage = state.optString("last_message", state.optBoolean("last_ok", false)
                                    ? "GitHub relay bootstrap finished."
                                    : "GitHub relay bootstrap failed.");
                            detailText.setText("运行诊断 (Diagnostics)\n" + statusMessage);
                            Toast.makeText(MainActivity.this, statusMessage, Toast.LENGTH_LONG).show();
                            setButtons(true);
                            refreshStatus();
                        }
                    });
                }
            }).start();
            return;
        }
        if (TokenResultService.ACTION_ROTATE_PHONE_API_TOKEN.equals(actionName)) {
            if (!ok) {
                String failure = message == null || message.trim().isEmpty() ? "Phone API token rotation failed." : message.trim();
                detailText.setText("运行诊断 (Diagnostics)\n" + failure);
                Toast.makeText(this, failure, Toast.LENGTH_LONG).show();
                setButtons(true);
                return;
            }
            final String newPhoneToken = trimmedStdout;
            detailText.setText("运行诊断 (Diagnostics)\nPhone API token rotated. Syncing the updated token to GitHub relay if configured...");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final JSONObject relayState;
                    if (GitHubRelaySync.hasLocalRelayConfig(MainActivity.this)) {
                        relayState = GitHubRelaySync.updatePhoneApiBearerToken(MainActivity.this, newPhoneToken);
                    } else {
                        relayState = null;
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            StringBuilder builder = new StringBuilder();
                            builder.append("Phone API token rotated successfully.\n");
                            builder.append("This device will keep using the new token until you rotate it again manually.");
                            if (relayState != null) {
                                builder.append("\nGitHub relay: ").append(relayState.optString("last_message", "updated"));
                            } else {
                                builder.append("\nGitHub relay: not configured locally, so no relay token update was needed.");
                            }
                            detailText.setText("运行诊断 (Diagnostics)\n" + builder);
                            Toast.makeText(MainActivity.this, "Phone API token rotated.", Toast.LENGTH_LONG).show();
                            setButtons(true);
                            scheduleRefreshes(1500, 5000, 12000);
                        }
                    });
                }
            }).start();
            return;
        }
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
                String discovered = trimmedStdout.replaceAll("[^0-9]", "");
                if (discovered.length() == 4) {
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
                                        detailText.setText("运行诊断 (Diagnostics)\nDiscovered local API at " + currentLocalApiBase + " via Termux.");
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
        detailText.setText("运行诊断 (Diagnostics)\n" + details);
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

    private byte[] readStreamFully(InputStream input) throws Exception {
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
            pollingText.setText("后台轮询 (Background Polling)\n已关闭 / Disabled");
            if (!"0".contentEquals(pollIntervalInput.getText())) {
                pollIntervalInput.setText("0");
            }
        } else {
            pollingText.setText("后台轮询 (Background Polling)\n已启用，每 " + seconds + " 秒 / Enabled every " + seconds + "s");
            String current = pollIntervalInput.getText() == null ? "" : pollIntervalInput.getText().toString();
            if (!String.valueOf(seconds).equals(current)) {
                pollIntervalInput.setText(String.valueOf(seconds));
            }
        }
        pollIntervalInput.setSelection(pollIntervalInput.getText().length());
    }

    private void refreshRelayModeUi() {
        int mode = AutomationSettings.getRelayMode(this);
        boolean hasRelayConfig = GitHubRelaySync.hasLocalRelayConfig(this);
        boolean hasDeviceFlow = GitHubDeviceFlow.isConfigured(this);
        boolean hasCustomClientId = GitHubDeviceFlow.hasUserConfiguredClientId(this);
        if (modeText != null) {
            if (mode == AutomationSettings.RELAY_MODE_GITHUB) {
                modeText.setText("模式 (Mode)\nGitHub Relay (中继模式)\nPhone AI Control will sync device state to your configured private GitHub relay repo.");
            } else {
                modeText.setText("模式 (Mode)\n传统直连 (Traditional Direct)\nCustom GPT should talk to the phone tunnel directly.");
            }
        }
        if (modeTraditionalButton != null) {
            boolean active = mode == AutomationSettings.RELAY_MODE_TRADITIONAL;
            modeTraditionalButton.setTextColor(active ? Color.parseColor("#081108") : Color.parseColor("#D9DFE7"));
            modeTraditionalButton.setBackground(makeRoundedDrawable(active ? "#4AE02C" : "#222831", active ? "#4AE02C" : "#222831", 0));
        }
        if (modeGithubButton != null) {
            boolean active = mode == AutomationSettings.RELAY_MODE_GITHUB;
            modeGithubButton.setTextColor(active ? Color.parseColor("#081108") : Color.parseColor("#D9DFE7"));
            modeGithubButton.setBackground(makeRoundedDrawable(active ? "#4AE02C" : "#222831", active ? "#4AE02C" : "#222831", 0));
        }
        if (githubRepoButton != null) {
            githubRepoButton.setAlpha(1f);
        }
        if (copyGithubTokenButton != null) {
            copyGithubTokenButton.setAlpha(1f);
        }
        if (connectGithubButton != null) {
            connectGithubButton.setText(
                    hasDeviceFlow
                            ? (hasRelayConfig ? "重新登录 GitHub 账户" : "登录 GitHub 账户")
                            : (hasRelayConfig ? "重新连接 GitHub / 更换 Token" : "登录 GitHub / 创建 Token")
            );
            connectGithubButton.setAlpha(1f);
        }
        if (importGithubTokenButton != null) {
            importGithubTokenButton.setAlpha(1f);
        }
        if (resetGithubBindingButton != null) {
            resetGithubBindingButton.setAlpha(hasRelayConfig ? 1f : 0.55f);
        }
        if (githubLoginSettingsButton != null) {
            githubLoginSettingsButton.setText(
                    hasDeviceFlow
                            ? (hasRelayConfig ? "Relogin GitHub Account" : "Login GitHub Account")
                            : (hasRelayConfig ? "Relink GitHub Account" : "Login / Link GitHub Account")
            );
            githubLoginSettingsButton.setAlpha(1f);
        }
        if (githubOauthInfoText != null) {
            String effectiveClientId = GitHubDeviceFlow.loadClientId(this);
            if (hasCustomClientId) {
                githubOauthInfoText.setText(
                        "OAuth Client ID\n"
                                + "Custom override is active.\n"
                                + "Current custom Client ID: " + effectiveClientId + "\n"
                                + "Clear the field and save if you want to go back to the built-in public Client ID."
                );
            } else {
                githubOauthInfoText.setText(
                        "OAuth Client ID\n"
                                + "No custom override is set.\n"
                                + "This app will use the built-in public GitHub OAuth Client ID by default.\n"
                                + "Paste your own Client ID only if you want to override it."
                );
            }
        }
        if (githubOauthClientIdInput != null) {
            String customClientId = GitHubDeviceFlow.loadUserConfiguredClientId(this);
            String current = githubOauthClientIdInput.getText() == null ? "" : githubOauthClientIdInput.getText().toString();
            if (!customClientId.equals(current)) {
                githubOauthClientIdInput.setText(customClientId);
                githubOauthClientIdInput.setSelection(githubOauthClientIdInput.getText().length());
            }
        }
        if (githubLogoutSettingsButton != null) {
            githubLogoutSettingsButton.setEnabled(hasRelayConfig);
            githubLogoutSettingsButton.setAlpha(hasRelayConfig ? 1f : 0.55f);
        }
        if (phoneTokenInfoText != null) {
            phoneTokenInfoText.setText(
                    "Phone API Token\n"
                            + "Created once on first setup, shared across tunnel changes, and kept stable until you rotate it manually.\n"
                            + "Different users/devices get different random first tokens."
            );
        }
    }

    private void refreshGitHubAccountUi(JSONObject profile) {
        boolean configured = GitHubRelaySync.hasLocalRelayConfig(this);
        if (githubAccountText != null) {
            String login = normalizedJsonString(profile, "login");
            String name = normalizedJsonString(profile, "name");
            if (!login.isEmpty()) {
                String repoUrl = GitHubRelaySync.getConfiguredRepoUrl(this);
                StringBuilder body = new StringBuilder();
                if (!name.isEmpty()) {
                    body.append(name).append("\n");
                }
                body.append("@").append(login);
                if (!repoUrl.isEmpty()) {
                    body.append("\n").append(repoUrl);
                }
                githubAccountText.setText(body.toString());
            } else if (configured) {
                githubAccountText.setText("GitHub relay is linked locally.\nProfile details will appear after the next successful account refresh.");
            } else {
                githubAccountText.setText("Not connected yet.\nUse the login button below to link a GitHub relay account.");
            }
        }
        String avatarUrl = normalizedJsonString(profile, "avatar_url");
        loadGitHubAvatarAsync(avatarUrl);
        if (githubLogoutSettingsButton != null) {
            githubLogoutSettingsButton.setEnabled(configured);
            githubLogoutSettingsButton.setAlpha(configured ? 1f : 0.55f);
        }
    }

    private void loadGitHubAvatarAsync(String avatarUrl) {
        if (githubAvatarView == null) {
            return;
        }
        final String normalizedUrl = avatarUrl == null ? "" : avatarUrl.trim();
        if (normalizedUrl.isEmpty()) {
            currentGitHubAvatarUrl = "";
            githubAvatarView.setImageResource(android.R.drawable.sym_def_app_icon);
            return;
        }
        if (normalizedUrl.equals(currentGitHubAvatarUrl)) {
            return;
        }
        currentGitHubAvatarUrl = normalizedUrl;
        githubAvatarView.setImageResource(android.R.drawable.sym_def_app_icon);
        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection conn = null;
                InputStream stream = null;
                try {
                    conn = (HttpURLConnection) new URL(normalizedUrl).openConnection();
                    conn.setConnectTimeout(5000);
                    conn.setReadTimeout(5000);
                    conn.setRequestProperty("User-Agent", "PhoneAIControl/1.0");
                    conn.connect();
                    stream = conn.getInputStream();
                    final Bitmap bitmap = BitmapFactory.decodeStream(stream);
                    if (bitmap == null) {
                        return;
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (githubAvatarView != null && normalizedUrl.equals(currentGitHubAvatarUrl)) {
                                githubAvatarView.setImageBitmap(bitmap);
                            }
                        }
                    });
                } catch (Exception ignored) {
                } finally {
                    try {
                        if (stream != null) {
                            stream.close();
                        }
                    } catch (Exception ignored) {
                    }
                    if (conn != null) {
                        conn.disconnect();
                    }
                }
            }
        }).start();
    }

    private String normalizedJsonString(JSONObject object, String key) {
        if (object == null || key == null || !object.has(key) || object.isNull(key)) {
            return "";
        }
        String value = object.optString(key, "").trim();
        return "null".equalsIgnoreCase(value) ? "" : value;
    }

    private void showSection(int section) {
        currentSection = section;
        if (statusSection != null) {
            statusSection.setVisibility(section == SECTION_STATUS ? View.VISIBLE : View.GONE);
        }
        if (controlSection != null) {
            controlSection.setVisibility(section == SECTION_CONTROL ? View.VISIBLE : View.GONE);
        }
        if (settingsSection != null) {
            settingsSection.setVisibility(section == SECTION_SETTINGS ? View.VISIBLE : View.GONE);
        }
        updateBottomNavUi();
    }

    private void updateBottomNavUi() {
        styleBottomNavButton(navStatusButton, currentSection == SECTION_STATUS);
        styleBottomNavButton(navControlButton, currentSection == SECTION_CONTROL);
        styleBottomNavButton(navSettingsButton, currentSection == SECTION_SETTINGS);
    }

    private void styleBottomNavButton(Button button, boolean active) {
        if (button == null) {
            return;
        }
        button.setTextColor(active ? Color.parseColor("#4AE02C") : Color.parseColor("#A1AAB6"));
        button.setBackground(makeRoundedDrawable(active ? "#1A2216" : "#11151B", active ? "#1A2216" : "#11151B", 0));
    }

    private void applyRelayMode(int mode) {
        int normalized = AutomationSettings.normalizeRelayMode(mode);
        AutomationSettings.setRelayMode(this, normalized);
        refreshRelayModeUi();
        if (normalized == AutomationSettings.RELAY_MODE_GITHUB) {
            Toast.makeText(this, "Switched to GitHub relay mode.", Toast.LENGTH_SHORT).show();
            if (!GitHubRelaySync.hasLocalRelayConfig(this)) {
                if (relayRepoText != null) {
                    relayRepoText.setText("GitHub 仓库\nGitHub relay is not configured yet. Connect GitHub first.");
                }
                detailText.setText(
                        "运行诊断 (Diagnostics)\n"
                                + "GitHub relay mode is enabled, but no relay binding exists yet.\n"
                                + "Tap '登录 GitHub / 创建 Token' to open the browser, then import the copied token here."
                );
                openGitHubTokenSetup();
            } else {
                checkOrCreateGitHubRelayRepo();
            }
        } else {
            Toast.makeText(this, "Switched to traditional direct mode.", Toast.LENGTH_SHORT).show();
            refreshStatus();
        }
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

    private void checkOrCreateGitHubRelayRepo() {
        if (!GitHubRelaySync.hasLocalRelayConfig(this)) {
            Toast.makeText(this, "GitHub relay is not configured yet. Connect GitHub first.", Toast.LENGTH_LONG).show();
            openGitHubTokenSetup();
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                final JSONObject state = GitHubRelaySync.ensureRelayRepository(MainActivity.this, true);
                final String summary = GitHubRelaySync.describeForUi(MainActivity.this);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (relayRepoText != null) {
                            relayRepoText.setText("GitHub Relay\n" + summary);
                        }
                        Toast.makeText(
                                MainActivity.this,
                                state.optBoolean("last_ok", false)
                                        ? state.optString("last_message", "GitHub relay repo is ready.")
                                        : state.optString("last_message", "GitHub relay repo check failed."),
                                Toast.LENGTH_LONG
                        ).show();
                        refreshStatus();
                    }
                });
            }
        }).start();
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
        String value = GitHubRelaySync.getPreferredAccessUrl(this, currentPublicUrl);
        if (value == null || value.isEmpty()) {
            Toast.makeText(this, "No URL is available yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        String bridge = GitHubRelaySync.getBridgeBaseUrl(this);
        String label;
        if (!bridge.isEmpty() && bridge.equals(value)) {
            label = "Phone AI Bridge URL";
        } else if (AutomationSettings.isGitHubRelayMode(this) && (currentPublicUrl == null || currentPublicUrl.trim().isEmpty())) {
            label = "GitHub Relay Repo URL";
        } else {
            label = "Phone AI API URL";
        }
        copyTextToClipboard(label, value, false);
    }

    private void copyGitHubToken() {
        String token = GitHubRelaySync.loadGitHubTokenForCopy(this);
        if (token == null || token.trim().isEmpty()) {
            Toast.makeText(this, "No GitHub token is configured yet.", Toast.LENGTH_SHORT).show();
            return;
        }
        copyTextToClipboard("GitHub Relay Token", token.trim(), true);
    }

    private void startGitHubLoginFlow() {
        if (!GitHubDeviceFlow.hasUserConfiguredClientId(this) && !isOAuthWizardAcknowledged()) {
            showGitHubOAuthSetupWizard();
            return;
        }
        beginGitHubLoginFlow();
    }

    private void beginGitHubLoginFlow() {
        if (!GitHubDeviceFlow.isConfigured(this)) {
            openGitHubTokenSetup();
            return;
        }
        setButtons(false);
        detailText.setText("运行诊断 (Diagnostics)\nRequesting a GitHub Device Flow code...");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final JSONObject session = GitHubDeviceFlow.start(MainActivity.this);
                    final String verificationUri = GitHubDeviceFlow.buildVerificationUri(session);
                    final String userCode = GitHubDeviceFlow.buildUserCode(session);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (!userCode.isEmpty()) {
                                copyTextToClipboard("GitHub Device Code", userCode, false);
                            }
                            openBrowserForGitHubDeviceFlow(verificationUri, userCode);
                            detailText.setText(
                                    "运行诊断 (Diagnostics)\n"
                                            + "GitHub login started.\n"
                                            + "1. Finish sign-in in the browser.\n"
                                            + "2. Enter the device code: " + (userCode.isEmpty() ? "(not provided)" : userCode) + "\n"
                                            + "3. The app will keep polling GitHub until the authorization is complete."
                            );
                            Toast.makeText(MainActivity.this, "GitHub sign-in opened in browser.", Toast.LENGTH_LONG).show();
                        }
                    });
                    final JSONObject tokenPayload = GitHubDeviceFlow.pollForAccessToken(session, 900);
                    final String accessToken = normalizedJsonString(tokenPayload, "access_token");
                    if (accessToken.isEmpty()) {
                        throw new IllegalStateException("GitHub Device Flow completed without an access token.");
                    }
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            pendingGitHubRelayToken = accessToken;
                            detailText.setText("运行诊断 (Diagnostics)\nGitHub sign-in succeeded. Reading the Phone API token from Termux to finish relay bootstrap...");
                            runManagedTermuxCommand(
                                    "cat " + TERMUX_HOME + "/ai-phone-api/token.txt",
                                    TokenResultService.ACTION_READ_PHONE_API_TOKEN,
                                    "GitHub sign-in complete. Loading the Phone API token..."
                            );
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            detailText.setText("运行诊断 (Diagnostics)\nGitHub Device Flow failed: " + e.getMessage());
                            Toast.makeText(MainActivity.this, "GitHub login failed: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            setButtons(true);
                        }
                    });
                }
            }
        }, "PhoneAiGitHubDeviceFlow").start();
    }

    private void showGitHubOAuthSetupWizard() {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint("Optional custom GitHub OAuth Client ID");
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(Color.parseColor("#68717E"));
        input.setBackground(makeRoundedDrawable("#12161C", "#2A303A", 1));
        input.setPadding(dp(12), dp(10), dp(12), dp(10));

        LinearLayout container = new LinearLayout(this);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(dp(4), dp(4), dp(4), 0);

        TextView guide = text(
                "This app can use a built-in public GitHub OAuth Client ID for normal users.\n\n"
                        + "If you want your own GitHub OAuth App instead:\n"
                        + "1. Open GitHub OAuth App setup in the browser.\n"
                        + "2. Create an OAuth App and enable Device Flow.\n"
                        + "3. Copy the Client ID.\n"
                        + "4. Come back here and paste it below.\n\n"
                        + "Leave the field blank if you want to continue with the built-in public Client ID.",
                14,
                Color.WHITE,
                false
        );
        guide.setPadding(0, 0, 0, dp(12));
        container.addView(guide);
        container.addView(input);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("GitHub OAuth Setup")
                .setView(container)
                .setNegativeButton("Not now", null)
                .setNeutralButton("Open GitHub OAuth App Page", null)
                .setPositiveButton("Continue to GitHub Login", null)
                .create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String typed = input.getText() == null ? "" : input.getText().toString().trim();
                if (!typed.isEmpty()) {
                    GitHubRelaySync.saveOAuthClientOverride(MainActivity.this, typed, GitHubDeviceFlow.loadScope(MainActivity.this));
                    refreshRelayModeUi();
                }
                acknowledgeOAuthWizard();
                openGitHubOAuthAppGuide();
            }
        });
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String typed = input.getText() == null ? "" : input.getText().toString().trim();
                if (!typed.isEmpty()) {
                    GitHubRelaySync.saveOAuthClientOverride(MainActivity.this, typed, GitHubDeviceFlow.loadScope(MainActivity.this));
                    refreshRelayModeUi();
                }
                acknowledgeOAuthWizard();
                dialog.dismiss();
                beginGitHubLoginFlow();
            }
        });
    }

    private void openGitHubTokenSetup() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GitHubRelaySync.buildTokenSetupUrl()));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            detailText.setText(
                    "运行诊断 (Diagnostics)\n"
                            + "GitHub token setup opened in your browser as a fallback flow.\n"
                            + "If you are not signed in, GitHub will ask you to sign in first.\n"
                            + "After you create the token, copy it and come back here to tap '导入剪贴板中的 GitHub Token'.\n"
                            + "Once imported, the linked GitHub avatar and username will appear in Settings.\n"
                            + "To enable the smoother standard login flow, configure a GitHub OAuth Device Flow client_id for this app."
            );
            Toast.makeText(this, "Browser opened for GitHub token setup.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not open GitHub token setup: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void openGitHubOAuthAppGuide() {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/settings/applications/new"));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            detailText.setText(
                    "运行诊断 (Diagnostics)\n"
                            + "GitHub OAuth App setup opened.\n"
                            + "Create an OAuth App, enable Device Flow, copy the Client ID, then come back here and paste it into the custom Client ID field in Settings.\n"
                            + "If you do not want your own OAuth App, you can leave the field blank and use the built-in public Client ID."
            );
            Toast.makeText(this, "GitHub OAuth App setup opened in browser.", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not open GitHub OAuth App setup: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void saveGitHubOauthClientIdFromInput() {
        if (githubOauthClientIdInput == null) {
            return;
        }
        String clientId = githubOauthClientIdInput.getText() == null
                ? ""
                : githubOauthClientIdInput.getText().toString().trim();
        boolean ok = GitHubRelaySync.saveOAuthClientOverride(this, clientId, GitHubDeviceFlow.loadScope(this));
        if (!ok) {
            Toast.makeText(this, "Could not save the GitHub OAuth Client ID.", Toast.LENGTH_LONG).show();
            return;
        }
        acknowledgeOAuthWizard();
        if (clientId.isEmpty()) {
            Toast.makeText(this, "Cleared the custom Client ID. The app will use its built-in public GitHub OAuth Client ID.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Saved the custom GitHub OAuth Client ID.", Toast.LENGTH_LONG).show();
        }
        refreshRelayModeUi();
    }

    private boolean isOAuthWizardAcknowledged() {
        return getSharedPreferences(OAUTH_PREFS, MODE_PRIVATE).getBoolean(PREF_OAUTH_WIZARD_ACK, false);
    }

    private void acknowledgeOAuthWizard() {
        getSharedPreferences(OAUTH_PREFS, MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_OAUTH_WIZARD_ACK, true)
                .apply();
    }

    private void openBrowserForGitHubDeviceFlow(String verificationUri, String userCode) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(verificationUri));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open GitHub login page: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void importGitHubTokenFromClipboard() {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        String token = "";
        if (manager != null && manager.hasPrimaryClip()) {
            ClipData clip = manager.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence text = clip.getItemAt(0).coerceToText(this);
                token = text == null ? "" : text.toString().trim();
            }
        }
        if (token.isEmpty()) {
            token = GitHubRelaySync.loadBootstrapToken(this);
        }
        if (token.isEmpty()) {
            Toast.makeText(this, "No GitHub token was found in the clipboard or the default token file.", Toast.LENGTH_LONG).show();
            return;
        }
        pendingGitHubRelayToken = token;
        detailText.setText("运行诊断 (Diagnostics)\nImporting GitHub token and reading the phone API token from Termux...");
        setButtons(false);
        runManagedTermuxCommand(
                "cat " + TERMUX_HOME + "/ai-phone-api/token.txt",
                TokenResultService.ACTION_READ_PHONE_API_TOKEN,
                "Reading the phone API token from Termux..."
        );
    }

    private void resetGitHubRelayBinding() {
        pendingGitHubRelayToken = "";
        currentPublicUrl = "";
        currentGitHubAvatarUrl = "";
        JSONObject state = GitHubRelaySync.clearLocalBinding(this);
        if (relayRepoText != null) {
            relayRepoText.setText("GitHub 仓库\n" + GitHubRelaySync.describeForUi(this));
        }
        detailText.setText("运行诊断 (Diagnostics)\n" + state.optString("last_message", "GitHub relay local binding was cleared."));
        Toast.makeText(this, "GitHub relay local binding was cleared.", Toast.LENGTH_LONG).show();
        refreshStatus();
    }

    private void rotatePhoneApiToken() {
        if (!hasTermuxPermission()) {
            requestTermuxPermissionIfNeeded();
            Toast.makeText(this, "Termux command permission is required.", Toast.LENGTH_LONG).show();
            return;
        }
        setButtons(false);
        detailText.setText("运行诊断 (Diagnostics)\nRotating the Phone API token and restarting the local API...");
        String command =
                "python - <<'PY'\n" +
                "from pathlib import Path\n" +
                "import secrets\n" +
                "base = Path.home() / 'ai-phone-api'\n" +
                "base.mkdir(parents=True, exist_ok=True)\n" +
                "token_file = base / 'token.txt'\n" +
                "token = secrets.token_urlsafe(32)\n" +
                "token_file.write_text(token + '\\n', encoding='utf-8')\n" +
                "print(token)\n" +
                "PY\n" +
                "chmod 600 \"$HOME/ai-phone-api/token.txt\" >/dev/null 2>&1 || true\n" +
                "if [ -x \"$HOME/ai-phone-api/stop-phone-ai-api.sh\" ]; then \"$HOME/ai-phone-api/stop-phone-ai-api.sh\" >/dev/null 2>&1 || true; fi\n" +
                "sleep 1\n" +
                "if [ -x \"$HOME/ai-phone-api/start-phone-ai-api.sh\" ]; then \"$HOME/ai-phone-api/start-phone-ai-api.sh\" >/dev/null 2>&1 || true; fi";
        runManagedTermuxCommand(
                command,
                TokenResultService.ACTION_ROTATE_PHONE_API_TOKEN,
                "Rotating the Phone API token..."
        );
    }

    private void copyTextToClipboard(String label, String value, boolean autoClear) {
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            Toast.makeText(this, "Clipboard is unavailable.", Toast.LENGTH_SHORT).show();
            return;
        }
        manager.setPrimaryClip(ClipData.newPlainText(label, value));
        if (autoClear) {
            scheduleClipboardClear(value, TOKEN_CLEAR_AFTER_MS);
            Toast.makeText(this, label + " copied. Clipboard will auto-clear in 20 seconds.", Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, label + " copied.", Toast.LENGTH_SHORT).show();
        }
    }

    private void scheduleClipboardClear(String text, int delayMs) {
        Intent clearIntent = new Intent(this, ClipboardClearReceiver.class);
        clearIntent.putExtra(ClipboardClearReceiver.EXTRA_TOKEN_SHA256, ClipboardClearReceiver.sha256(text));
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                7002,
                clearIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        if (alarmManager == null) {
            return;
        }
        long triggerAtMillis = System.currentTimeMillis() + Math.max(delayMs, 5_000);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        }
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
        channel.setDescription("Notifications created by the Phone AI Control API bridge.");
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
        if (githubRepoButton != null) {
            githubRepoButton.setEnabled(enabled);
        }
        if (copyGithubTokenButton != null) {
            copyGithubTokenButton.setEnabled(enabled);
        }
        if (connectGithubButton != null) {
            connectGithubButton.setEnabled(enabled);
        }
        if (importGithubTokenButton != null) {
            importGithubTokenButton.setEnabled(enabled);
        }
        if (resetGithubBindingButton != null) {
            resetGithubBindingButton.setEnabled(enabled && GitHubRelaySync.hasLocalRelayConfig(this));
        }
        if (githubLoginSettingsButton != null) {
            githubLoginSettingsButton.setEnabled(enabled);
        }
        if (githubLogoutSettingsButton != null) {
            githubLogoutSettingsButton.setEnabled(enabled && GitHubRelaySync.hasLocalRelayConfig(this));
        }
        if (rotatePhoneTokenButton != null) {
            rotatePhoneTokenButton.setEnabled(enabled);
        }
        if (copyPhoneTokenSettingsButton != null) {
            copyPhoneTokenSettingsButton.setEnabled(enabled);
        }
        if (modeTraditionalButton != null) {
            modeTraditionalButton.setEnabled(enabled);
        }
        if (modeGithubButton != null) {
            modeGithubButton.setEnabled(enabled);
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

    private void requestContactsAndCallLogPermissions() {
        if (Build.VERSION.SDK_INT < 23) {
            refreshStatus();
            return;
        }
        if (hasContactsAccess() && hasCallLogAccess()) {
            Toast.makeText(this, "Contacts and call log access already granted.", Toast.LENGTH_SHORT).show();
            refreshStatus();
            return;
        }
        requestPermissions(
                new String[]{Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG},
                REQUEST_CONTACTS_AND_CALL_LOG_PERMISSION
        );
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
        payload.put("source", "phone_ai_control");
        payload.put("captured_epoch_ms", System.currentTimeMillis());
        return payload;
    }

    private JSONObject collectUsageSnapshot() throws Exception {
        JSONObject payload = new JSONObject();
        payload.put("source", "phone_ai_control");
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
            payload.put("source", "phone_ai_control");
            payload.put("captured_epoch_ms", System.currentTimeMillis());
            payload.put("permission_state", collectPermissionState());
        } catch (Exception ignored) {
        }
        return payload;
    }

    private JSONObject collectContactsSnapshot() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("source", "phone_ai_control");
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
            payload.put("source", "phone_ai_control");
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
            permissionState.put("notification_access", hasNotificationAccess());
            permissionState.put("contacts_access", hasContactsAccess());
            permissionState.put("call_log_access", hasCallLogAccess());
            permissionState.put("battery_optimization_exemption", isIgnoringBatteryOptimizations());
            permissionState.put("can_request_package_installs", canRequestPackageInstallsCompat());
            permissionState.put("termux_run_command_permission", hasTermuxPermission());
        } catch (Exception ignored) {
        }
        return permissionState;
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
                detailText.setText("运行诊断 (Diagnostics)\nDownloading pending APK install request for " + apkName + "...");
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
                                detailText.setText("运行诊断 (Diagnostics)\nPhone AI Control needs permission to install unknown apps. Opening the Android settings page for this app now.");
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
                                detailText.setText("运行诊断 (Diagnostics)\nAPK installer launched for " + apkName + ".");
                            } catch (Exception launchError) {
                                detailText.setText("运行诊断 (Diagnostics)\nAPK installer could not be launched: " + launchError.getMessage());
                            }
                        }
                    });
                    completeInstallRequestAsync(requestId, "installer_launched", result, null);
                } catch (Exception e) {
                    completeInstallRequestAsync(requestId, "failed", null, e.getMessage());
                    detailText.post(new Runnable() {
                        @Override
                        public void run() {
                            detailText.setText("运行诊断 (Diagnostics)\nDedicated APK install request failed.");
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

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
