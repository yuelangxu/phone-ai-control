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

import java.util.concurrent.atomic.AtomicInteger;

public class TokenResultService extends IntentService {
    public static final String EXTRA_EXECUTION_ID = "execution_id";
    public static final String EXTRA_ACTION_NAME = "action_name";
    public static final String EXTRA_CLEAR_AFTER_MS = "clear_after_ms";
    public static final String ACTION_COPY_TOKEN = "copy_token";

    private static final String TERMUX_RESULT_BUNDLE = "result";
    private static final String TERMUX_STDOUT = "stdout";
    private static final String TERMUX_STDERR = "stderr";
    private static final String TERMUX_EXIT_CODE = "exitCode";
    private static final String TERMUX_ERR = "err";
    private static final String TERMUX_ERRMSG = "errmsg";

    private static final AtomicInteger EXECUTION_COUNTER = new AtomicInteger(3000);
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());
    private static final String LOG_TAG = "TokenResultService";

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
        Bundle resultBundle = intent.getBundleExtra(TERMUX_RESULT_BUNDLE);
        if (resultBundle == null) {
            showToast("Token copy failed: no result bundle received.");
            return;
        }

        String action = intent.getStringExtra(EXTRA_ACTION_NAME);
        if (!ACTION_COPY_TOKEN.equals(action)) {
            return;
        }

        int errCode = resultBundle.getInt(TERMUX_ERR, -1);
        int exitCode = resultBundle.getInt(TERMUX_EXIT_CODE, -1);
        String stderr = resultBundle.getString(TERMUX_STDERR, "");
        String errMsg = resultBundle.getString(TERMUX_ERRMSG, "");
        String stdout = resultBundle.getString(TERMUX_STDOUT, "");

        if (errCode != -1 || exitCode != 0) {
            String message = "Token copy failed.";
            if (!errMsg.isEmpty()) {
                message += " " + errMsg;
            } else if (!stderr.isEmpty()) {
                message += " " + stderr.trim();
            }
            Log.w(LOG_TAG, "Token copy failed. exitCode=" + exitCode + " errCode=" + errCode + " errMsg=" + errMsg + " stderr=" + stderr);
            showToast(message.trim());
            return;
        }

        String token = stdout == null ? "" : stdout.trim();
        if (token.isEmpty()) {
            showToast("Token copy failed: empty token returned.");
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
