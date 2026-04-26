package com.example.phoneaicontrol;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class PhoneAiNotificationListener extends NotificationListenerService {
    private static volatile PhoneAiNotificationListener instance;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        instance = this;
        NotificationAccessStore.updateSnapshot(this, safeActiveNotifications(), null, false);
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
        NotificationAccessStore.updateSnapshot(this, null, null, false);
        super.onListenerDisconnected();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        NotificationAccessStore.updateSnapshot(this, safeActiveNotifications(), sbn, false);
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        NotificationAccessStore.updateSnapshot(this, safeActiveNotifications(), sbn, true);
    }

    public static boolean cancelAllClearableNotifications() {
        PhoneAiNotificationListener listener = instance;
        if (listener == null) {
            return false;
        }
        try {
            listener.cancelAllNotifications();
            NotificationAccessStore.updateSnapshot(listener, listener.safeActiveNotifications(), null, false);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    public static StatusBarNotification[] getCurrentActiveNotifications() {
        PhoneAiNotificationListener listener = instance;
        if (listener == null) {
            return null;
        }
        return listener.safeActiveNotifications();
    }

    private StatusBarNotification[] safeActiveNotifications() {
        try {
            return getActiveNotifications();
        } catch (Exception ignored) {
            return null;
        }
    }
}
