package com.example.phoneaicontrol;

import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class ClipboardClearReceiver extends BroadcastReceiver {
    public static final String EXTRA_TOKEN_SHA256 = "token_sha256";
    private static final String LOG_TAG = "ClipboardClearReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        ClipboardManager manager = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        if (manager == null) {
            return;
        }
        String expectedHash = intent.getStringExtra(EXTRA_TOKEN_SHA256);
        CharSequence current = readCurrentClipboardText(context, manager);
        if (current == null || expectedHash == null) {
            return;
        }
        if (expectedHash.equals(sha256(current.toString()))) {
            manager.setPrimaryClip(ClipData.newPlainText("Phone AI API Token", ""));
            Log.i(LOG_TAG, "Cleared token text from clipboard after timeout.");
        }
    }

    private CharSequence readCurrentClipboardText(Context context, ClipboardManager manager) {
        if (!manager.hasPrimaryClip()) {
            return null;
        }
        ClipData clipData = manager.getPrimaryClip();
        if (clipData == null || clipData.getItemCount() == 0) {
            return null;
        }
        return clipData.getItemAt(0).coerceToText(context);
    }

    public static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
