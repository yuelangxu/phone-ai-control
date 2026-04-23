package com.example.phoneaicontrol;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

public class MainActivity extends Activity {
    private static final String RUN_COMMAND_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String LOCAL_API = "http://127.0.0.1:8787";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final int REQUEST_RUN_COMMAND = 42;

    private TextView statusText;
    private TextView addressText;
    private TextView publicText;
    private TextView detailText;
    private Button startButton;
    private Button stopPublicButton;
    private Button stopAllButton;
    private String currentPublicUrl = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildUi();
        requestTermuxPermissionIfNeeded();
        refreshStatus();
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
        addressText = card("Current Public Address", "Unknown");
        publicText = card("Public Exposure", "Checking...");
        detailText = card("Details", "No data yet.");
        root.addView(statusText);
        root.addView(addressText);
        root.addView(publicText);
        root.addView(detailText);

        startButton = button("Start Public API");
        stopPublicButton = button("Stop Public Tunnel");
        stopAllButton = button("Stop API And Tunnel");
        Button refreshButton = button("Refresh Status");
        Button copyButton = button("Copy Address");
        Button settingsButton = button("Open Termux Permission Settings");

        startButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTermuxCommand(
                        TERMUX_HOME + "/ai-phone-api/start-phone-ai-api.sh; "
                                + TERMUX_HOME + "/ai-phone-api/start-phone-ai-localtunnel.sh",
                        "Start requested. Refresh again in a few seconds.");
                delayedRefresh();
            }
        });
        stopPublicButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTermuxCommand(
                        TERMUX_HOME + "/ai-phone-api/stop-phone-ai-localtunnel.sh; "
                                + TERMUX_HOME + "/ai-phone-api/stop-phone-ai-tunnel.sh",
                        "Public tunnel stop requested.");
                delayedRefresh();
            }
        });
        stopAllButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runTermuxCommand(
                        TERMUX_HOME + "/ai-phone-api/stop-phone-ai-localtunnel.sh; "
                                + TERMUX_HOME + "/ai-phone-api/stop-phone-ai-tunnel.sh; "
                                + TERMUX_HOME + "/ai-phone-api/stop-phone-ai-api.sh",
                        "API and tunnel stop requested.");
                delayedRefresh();
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
        settingsButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openAppSettings();
            }
        });

        root.addView(startButton);
        root.addView(stopPublicButton);
        root.addView(stopAllButton);
        root.addView(refreshButton);
        root.addView(copyButton);
        root.addView(settingsButton);

        TextView note = text(
                "Security note: this app does not show your API token. It only starts/stops Termux scripts and checks public reachability.",
                13,
                Color.rgb(96, 96, 96),
                false);
        note.setPadding(0, dp(12), 0, 0);
        root.addView(note);

        setContentView(scroll);
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
        setButtons(false);
        statusText.setText("Local API\nChecking...");
        publicText.setText("Public Exposure\nChecking...");
        new Thread(new Runnable() {
            @Override
            public void run() {
            String localStatus;
            String publicStatus = "Disabled or unknown";
            String details = "";
            String publicUrl = "";
            try {
                JSONObject health = getJson(LOCAL_API + "/healthz", 2500);
                localStatus = health.optBoolean("ok") ? "Online" : "Unexpected response";
                publicUrl = normalizePublicUrl(health.optString("public_url", ""));
                boolean enabled = health.optBoolean("public_enabled", false);
                boolean lt = health.optBoolean("localtunnel_running", false);
                boolean cf = health.optBoolean("cloudflared_running", false);
                details = "localtunnel: " + (lt ? "running" : "stopped")
                        + "\ncloudflared: " + (cf ? "running" : "stopped")
                        + "\nauth required: " + health.optBoolean("auth_required", true);
                if (enabled && publicUrl.startsWith("https://")) {
                    publicStatus = "Enabled (tunnel running)";
                    try {
                        JSONObject publicHealth = getJson(publicUrl + "/healthz", 5000);
                        publicStatus = publicHealth.optBoolean("ok") ? "Enabled and reachable" : "Enabled, but health check failed";
                    } catch (Exception e) {
                        publicStatus = "Enabled (tunnel running)";
                        details += "\npublic self-check: " + e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                } else if ((lt || cf) && publicUrl.isEmpty()) {
                    publicStatus = "Starting tunnel...";
                } else {
                    publicStatus = "Off";
                }
            } catch (Exception e) {
                localStatus = "Offline";
                details = "Local API check failed: " + e.getClass().getSimpleName() + ": " + e.getMessage()
                        + "\nTap Start Public API to start Termux scripts.";
            }
            String finalLocalStatus = localStatus;
            String finalPublicStatus = publicStatus;
            String finalDetails = details;
            String finalPublicUrl = publicUrl;
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    currentPublicUrl = finalPublicUrl == null ? "" : finalPublicUrl;
                    statusText.setText("Local API\n" + finalLocalStatus);
                    addressText.setText("Current Public Address\n" + (currentPublicUrl.isEmpty() ? "None" : currentPublicUrl));
                    publicText.setText("Public Exposure\n" + finalPublicStatus);
                    detailText.setText("Details\n" + finalDetails);
                    setButtons(true);
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

    private void runTermuxCommand(String command, String toast) {
        if (!hasTermuxPermission()) {
            requestTermuxPermissionIfNeeded();
            Toast.makeText(this, "Termux command permission is required.", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Intent intent = new Intent();
            intent.setClassName("com.termux", "com.termux.app.RunCommandService");
            intent.setAction("com.termux.RUN_COMMAND");
            intent.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
            intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", command});
            intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
            intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
            startService(intent);
            Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Could not start Termux command: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
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

    private void copyAddress() {
        if (currentPublicUrl == null || currentPublicUrl.isEmpty()) {
            Toast.makeText(this, "No public address available.", Toast.LENGTH_SHORT).show();
            return;
        }
        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        manager.setPrimaryClip(ClipData.newPlainText("Phone AI API URL", currentPublicUrl));
        Toast.makeText(this, "Address copied.", Toast.LENGTH_SHORT).show();
    }

    private void delayedRefresh() {
        statusText.postDelayed(new Runnable() {
            @Override
            public void run() {
                refreshStatus();
            }
        }, 6000);
    }

    private void setButtons(boolean enabled) {
        startButton.setEnabled(enabled);
        stopPublicButton.setEnabled(enabled);
        stopAllButton.setEnabled(enabled);
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
