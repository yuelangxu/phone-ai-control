package com.example.phoneaicontrol;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

public class RelayCommandReceiver extends BroadcastReceiver {
    private static final String TAG = "PhoneAiRelayReceiver";
    public static final String ACTION_SET_RELAY_MODE = "com.example.phoneaicontrol.action.SET_RELAY_MODE";
    public static final String ACTION_ENSURE_GITHUB_RELAY = "com.example.phoneaicontrol.action.ENSURE_GITHUB_RELAY";
    public static final String ACTION_SYNC_GITHUB_RELAY = "com.example.phoneaicontrol.action.SYNC_GITHUB_RELAY";
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_FORCE = "force";

    @Override
    public void onReceive(final Context context, final Intent intent) {
        if (context == null || intent == null || intent.getAction() == null) {
            return;
        }
        Log.i(TAG, "Received relay command action=" + intent.getAction());
        final PendingResult pendingResult = goAsync();
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    String action = intent.getAction();
                    if (ACTION_SET_RELAY_MODE.equals(action)) {
                        String mode = intent.getStringExtra(EXTRA_MODE);
                        if ("github".equalsIgnoreCase(mode)) {
                            AutomationSettings.setRelayMode(context, AutomationSettings.RELAY_MODE_GITHUB);
                            GitHubRelaySync.ensureRelayRepository(context, true);
                            Log.i(TAG, "Switched relay mode to GitHub via broadcast.");
                        } else {
                            AutomationSettings.setRelayMode(context, AutomationSettings.RELAY_MODE_TRADITIONAL);
                            Log.i(TAG, "Switched relay mode to traditional via broadcast.");
                        }
                        return;
                    }
                    if (ACTION_ENSURE_GITHUB_RELAY.equals(action)) {
                        AutomationSettings.setRelayMode(context, AutomationSettings.RELAY_MODE_GITHUB);
                        GitHubRelaySync.ensureRelayRepository(context, true);
                        Log.i(TAG, "Ensured GitHub relay repo via broadcast.");
                        return;
                    }
                    if (ACTION_SYNC_GITHUB_RELAY.equals(action)) {
                        AutomationSettings.setRelayMode(context, AutomationSettings.RELAY_MODE_GITHUB);
                        boolean force = intent.getBooleanExtra(EXTRA_FORCE, true);
                        GitHubRelaySync.triggerSyncFromPhoneContext(context, "broadcast_receiver", force);
                        Log.i(TAG, "Triggered GitHub relay sync via broadcast. force=" + force);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Relay command failed.", e);
                } finally {
                    pendingResult.finish();
                }
            }
        }, "PhoneAiRelayCommand").start();
    }
}
