package com.rst.astralauncher;

import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

public class MediaListener extends NotificationListenerService {
    static volatile MediaListener inst;
    static volatile CarPlugin plugin;

    @Override public void onListenerConnected() { inst = this; scan(); }
    @Override public void onListenerDisconnected() { inst = null; }

    private static boolean isNav(StatusBarNotification s) {
        return s != null && "com.waze".equals(s.getPackageName());
    }

    void scan() {
        try {
            StatusBarNotification[] all = getActiveNotifications();
            if (all != null) for (StatusBarNotification s : all) if (isNav(s) && plugin != null) { plugin.navUpdate(s.getNotification()); return; }
            if (plugin != null) plugin.navGone();
        } catch (Exception ignored) {}
    }

    @Override public void onNotificationPosted(StatusBarNotification s) {
        if (isNav(s) && plugin != null) plugin.navUpdate(s.getNotification());
        if (plugin != null) plugin.notifsChanged();
    }

    @Override public void onNotificationRemoved(StatusBarNotification s) {
        if (isNav(s)) scan();
        if (plugin != null) plugin.notifsChanged();
    }

    StatusBarNotification[] list() {
        try { StatusBarNotification[] a = getActiveNotifications(); return a == null ? new StatusBarNotification[0] : a; }
        catch (Exception e) { return new StatusBarNotification[0]; }
    }
}
