package com.example.phoneaicontrol;

import android.app.AlarmManager;
import android.app.IntentService;
import android.app.PendingIntent;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.atomic.AtomicInteger;

public class TokenResultService extends IntentService {
    public static final String EXTRA_EXECUTION_ID = "execution_id";
    public static final String EXTRA_ACTION_NAME = "action_name";
    public static final String EXTRA_CLEAR_AFTER_MS = "clear_after_ms";
    public static final String EXTRA_APPROVAL_ID = "approval_id";
    public static final String EXTRA_OK = "ok";
    public static final String EXTRA_MESSAGE = "message";
    public static final String EXTRA_RESPONSE_JSON = "response_json";
    public static final String EXTRA_STDOUT = "stdout";
    public static final String EXTRA_STDERR = "stderr";
    public static final String EXTRA_LOCAL_API_BASE = "local_api_base";
    public static final String ACTION_COPY_TOKEN = "copy_token";
    public static final String ACTION_REVIEW_PENDING_APPROVAL = "review_pending_approval";
    public static final String ACTION_APPROVE_PENDING_APPROVAL = "approve_pending_approval";
    public static final String ACTION_START_PUBLIC_API = "start_public_api";
    public static final String ACTION_STOP_PUBLIC_TUNNEL = "stop_public_tunnel";
    public static final String ACTION_STOP_ALL = "stop_all";
    public static final String ACTION_DISCOVER_LOCAL_API = "discover_local_api";
    public static final String ACTION_CONTROL_START_PUBLIC = "control_start_public";
    public static final String ACTION_CONTROL_STOP_PUBLIC = "control_stop_public";
    public static final String ACTION_CONTROL_STOP_ALL = "control_stop_all";
    public static final String BROADCAST_APPROVAL_RESULT = "com.example.phoneaicontrol.APPROVAL_RESULT";
    public static final String BROADCAST_COMMAND_RESULT = "com.example.phoneaicontrol.COMMAND_RESULT";

    private static final String TERMUX_RESULT_BUNDLE = "result";
    private static final String TERMUX_STDOUT = "stdout";
    private static final String TERMUX_STDERR = "stderr";
    private static final String TERMUX_EXIT_CODE = "exitCode";
    private static final String TERMUX_ERR = "err";
    private static final String TERMUX_ERRMSG = "errmsg";

    private static final AtomicInteger EXECUTION_COUNTER = new AtomicInteger(3000);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final String LOG_TAG = "TokenResultService";
    private String localApiBase = DEFAULT_LOCAL_API;
    private static final String DEFAULT_LOCAL_API = "http://127.0.0.1:8787";
    private static final String PHONE_AI_RUNTIME_FILE = "/storage/emulated/0/Android/media/com.example.phoneaicontrol/runtime.json";

    public static synchronized int getNextExecutionId() {
        return EXECUTION_COUNTER.getAndIncrement();
    }

    public TokenResultService() {
        super("TokenResultService");
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        if (intent == null) {
            return;
        }
        String providedLocalApiBase = intent.getStringExtra(EXTRA_LOCAL_API_BASE);
        if (providedLocalApiBase != null && !providedLocalApiBase.trim().isEmpty()) {
            localApiBase = providedLocalApiBase.trim();
        } else {
            localApiBase = resolveLocalApiBase();
        }
        Bundle resultBundle = intent.getBundleExtra(TERMUX_RESULT_BUNDLE);
        if (resultBundle == null) {
            showToast("Token copy failed: no result bundle received.");
            return;
        }

        String action = intent.getStringExtra(EXTRA_ACTION_NAME);
        int errCode = resultBundle.getInt(TERMUX_ERR, -1);
        int exitCode = resultBundle.getInt(TERMUX_EXIT_CODE, -1);
        String stderr = resultBundle.getString(TERMUX_STDERR, "");
        String errMsg = resultBundle.getString(TERMUX_ERRMSG, "");
        String stdout = resultBundle.getString(TERMUX_STDOUT, "");

        if (ACTION_START_PUBLIC_API.equals(action)
                || ACTION_STOP_PUBLIC_TUNNEL.equals(action)
                || ACTION_STOP_ALL.equals(action)
                || ACTION_DISCOVER_LOCAL_API.equals(action)) {
            handleCommandResult(action, errCode, exitCode, stdout, stderr, errMsg);
            return;
        }

        if (errCode != -1 || exitCode != 0) {
            String message = "Token copy failed.";
            if (!errMsg.isEmpty()) {
                message += " " + errMsg;
            } else if (!stderr.isEmpty()) {
                message += " " + stderr.trim();
            }
            Log.w(LOG_TAG, "Token copy failed. exitCode=" + exitCode + " errCode=" + errCode + " errMsg=" + errMsg + " stderr=" + stderr);
            if (ACTION_COPY_TOKEN.equals(action)) {
                showToast(message.trim());
            } else {
                broadcastApprovalResult(action, false, message.trim(), null);
            }
            return;
        }

        String token = stdout == null ? "" : stdout.trim();
        if (token.isEmpty()) {
            if (ACTION_COPY_TOKEN.equals(action)) {
                showToast("Token copy failed: empty token returned.");
            } else {
                broadcastApprovalResult(action, false, "Action failed: empty token returned.", null);
            }
            return;
        }

        if (ACTION_REVIEW_PENDING_APPROVAL.equals(action)) {
            handleReviewPendingApproval(token);
            return;
        }
        if (ACTION_APPROVE_PENDING_APPROVAL.equals(action)) {
            handleApprovePendingApproval(token, intent.getStringExtra(EXTRA_APPROVAL_ID));
            return;
        }
        if (ACTION_CONTROL_START_PUBLIC.equals(action)
                || ACTION_CONTROL_STOP_PUBLIC.equals(action)
                || ACTION_CONTROL_STOP_ALL.equals(action)) {
            handleControlAction(action, token);
            return;
        }
        if (!ACTION_COPY_TOKEN.equals(action)) {
            return;
        }

        ClipboardManager manager = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            showToast("Token copy failed: clipboard unavailable.");
            return;
        }

        manager.setPrimaryClip(ClipData.newPlainText("Phone AI API Token", token));
        scheduleClipboardClear(token, intent.getIntExtra(EXTRA_CLEAR_AFTER_MS, 20_000));
        Log.i(LOG_TAG, "Copied token to clipboard and scheduled timed clear.");
        showToast("Token copied. Clipboard will clear in 20 seconds.");
    }

    private void handleCommandResult(String action, int errCode, int exitCode, String stdout, String stderr, String errMsg) {
        boolean ok = errCode == -1 && exitCode == 0;
        String message;
        if (ok) {
            if (ACTION_START_PUBLIC_API.equals(action)) {
                message = "Start command completed.";
            } else if (ACTION_STOP_PUBLIC_TUNNEL.equals(action)) {
                message = "Public tunnel stop command completed.";
            } else if (ACTION_STOP_ALL.equals(action)) {
                message = "Stop-all command completed.";
            } else {
                message = "Command completed.";
            }
        } else {
            StringBuilder builder = new StringBuilder();
            if (ACTION_START_PUBLIC_API.equals(action)) {
                builder.append("Start command failed.");
            } else if (ACTION_STOP_PUBLIC_TUNNEL.equals(action)) {
                builder.append("Public tunnel stop command failed.");
            } else if (ACTION_STOP_ALL.equals(action)) {
                builder.append("Stop-all command failed.");
            } else {
                builder.append("Command failed.");
            }
            if (errMsg != null && !errMsg.trim().isEmpty()) {
                builder.append(" ").append(errMsg.trim());
            } else if (stderr != null && !stderr.trim().isEmpty()) {
                builder.append(" ").append(stderr.trim());
            }
            message = builder.toString();
        }
        broadcastCommandResult(action, ok, message.trim(), stdout, stderr);
    }

    private void handleReviewPendingApproval(String token) {
        try {
            JSONObject response = requestJson("GET", localApiBase + "/v1/file-approvals/pending", token, null);
            broadcastApprovalResult(ACTION_REVIEW_PENDING_APPROVAL, true, "Pending file approvals loaded.", response.toString());
        } catch (Exception e) {
            Log.w(LOG_TAG, "Review pending approval failed", e);
            broadcastApprovalResult(ACTION_REVIEW_PENDING_APPROVAL, false, "Could not read pending file approvals: " + e.getMessage(), null);
        }
    }

    private void handleApprovePendingApproval(String token, String approvalId) {
        if (approvalId == null || approvalId.trim().isEmpty()) {
            broadcastApprovalResult(ACTION_APPROVE_PENDING_APPROVAL, false, "No approval id was provided.", null);
            return;
        }
        try {
            JSONObject response = requestJson("POST", localApiBase + "/v1/file-approvals/" + approvalId + "/approve", token, "{}");
            broadcastApprovalResult(ACTION_APPROVE_PENDING_APPROVAL, true, "Pending file change approved.", response.toString());
        } catch (Exception e) {
            Log.w(LOG_TAG, "Approve pending approval failed", e);
            broadcastApprovalResult(ACTION_APPROVE_PENDING_APPROVAL, false, "Could not approve file change: " + e.getMessage(), null);
        }
    }

    private void handleControlAction(String action, String token) {
        String path;
        String successMessage;
        if (ACTION_CONTROL_START_PUBLIC.equals(action)) {
            path = "/v1/control/start-public";
            successMessage = "Public tunnel start requested through local API.";
        } else if (ACTION_CONTROL_STOP_PUBLIC.equals(action)) {
            path = "/v1/control/stop-public";
            successMessage = "Public tunnel stop requested through local API.";
        } else if (ACTION_CONTROL_STOP_ALL.equals(action)) {
            path = "/v1/control/stop-all";
            successMessage = "Local API shutdown requested through local API.";
        } else {
            broadcastCommandResult(action, false, "Unknown control action.", null, null);
            return;
        }
        try {
            JSONObject response = requestJson("POST", localApiBase + path, token, "{}");
            broadcastCommandResult(action, true, successMessage, response.toString(), null);
        } catch (Exception e) {
            Log.w(LOG_TAG, "Control action failed: " + action, e);
            broadcastCommandResult(action, false, "Control action failed: " + e.getMessage(), null, null);
        }
    }

    private String resolveLocalApiBase() {
        try {
            File runtimeFile = new File(PHONE_AI_RUNTIME_FILE);
            if (runtimeFile.exists() && runtimeFile.isFile()) {
                String text = new String(java.nio.file.Files.readAllBytes(runtimeFile.toPath()), "UTF-8");
                JSONObject runtime = new JSONObject(text);
                String url = runtime.optString("local_api_url", "").trim();
                if (!url.isEmpty()) {
                    return url;
                }
                int port = runtime.optInt("local_port", 0);
                if (port >= 1000 && port <= 9999) {
                    return "http://127.0.0.1:" + port;
                }
            }
        } catch (Exception ignored) {
        }
        return DEFAULT_LOCAL_API;
    }

    private JSONObject requestJson(String method, String rawUrl, String bearerToken, String body) throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(rawUrl).openConnection();
        conn.setRequestMethod(method);
        conn.setConnectTimeout(4000);
        conn.setReadTimeout(6000);
        conn.setRequestProperty("User-Agent", "PhoneAIControl/1.0");
        conn.setRequestProperty("Authorization", "Bearer " + bearerToken);
        conn.setRequestProperty("Accept", "application/json");
        if (body != null) {
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] raw = body.getBytes("UTF-8");
            conn.setRequestProperty("Content-Length", String.valueOf(raw.length));
            OutputStream os = conn.getOutputStream();
            os.write(raw);
            os.close();
        }
        int code = conn.getResponseCode();
        BufferedReader reader = new BufferedReader(new InputStreamReader(
                code >= 200 && code < 400 ? conn.getInputStream() : conn.getErrorStream(),
                "UTF-8"));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            sb.append(line);
        }
        reader.close();
        if (code < 200 || code >= 400) {
            throw new IllegalStateException("HTTP " + code + ": " + sb);
        }
        return new JSONObject(sb.toString());
    }

    private void broadcastApprovalResult(String actionName, boolean ok, String message, String responseJson) {
        Intent intent = new Intent(BROADCAST_APPROVAL_RESULT);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_ACTION_NAME, actionName);
        intent.putExtra(EXTRA_OK, ok);
        intent.putExtra(EXTRA_MESSAGE, message);
        if (responseJson != null) {
            intent.putExtra(EXTRA_RESPONSE_JSON, responseJson);
        }
        sendBroadcast(intent);
    }

    private void broadcastCommandResult(String actionName, boolean ok, String message, String stdout, String stderr) {
        Intent intent = new Intent(BROADCAST_COMMAND_RESULT);
        intent.setPackage(getPackageName());
        intent.putExtra(EXTRA_ACTION_NAME, actionName);
        intent.putExtra(EXTRA_OK, ok);
        intent.putExtra(EXTRA_MESSAGE, message);
        if (stdout != null) {
            intent.putExtra(EXTRA_STDOUT, stdout);
        }
        if (stderr != null) {
            intent.putExtra(EXTRA_STDERR, stderr);
        }
        sendBroadcast(intent);
    }

    private void scheduleClipboardClear(String token, int delayMs) {
        Intent clearIntent = new Intent(this, ClipboardClearReceiver.class);
        clearIntent.putExtra(ClipboardClearReceiver.EXTRA_TOKEN_SHA256, ClipboardClearReceiver.sha256(token));
        PendingIntent pendingIntent = PendingIntent.getBroadcast(
                this,
                7001,
                clearIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE : 0)
        );
        AlarmManager alarmManager = (AlarmManager) getSystemService(Context.ALARM_SERVICE);
        long triggerAtMillis = System.currentTimeMillis() + Math.max(delayMs, 5_000);
        if (alarmManager == null) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        } else {
            alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent);
        }
    }

    private void showToast(final String message) {
        MAIN_HANDLER.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), message, Toast.LENGTH_LONG).show();
            }
        });
    }
}
