package com.rst.astralauncher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.os.Build;
import com.getcapacitor.PermissionState;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import android.content.IntentFilter;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.os.BatteryManager;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import android.app.Notification;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.media.audiofx.Visualizer;
import android.app.PendingIntent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.service.notification.StatusBarNotification;
import android.view.WindowManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Base64;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;

@CapacitorPlugin(name = "Car", permissions = { @Permission(alias = "bt", strings = { Manifest.permission.BLUETOOTH_CONNECT }), @Permission(alias = "mic", strings = { Manifest.permission.RECORD_AUDIO }) })
public class CarPlugin extends Plugin {
    private static final String YTM = "com.google.android.apps.youtube.music";
    private final Handler handler = new Handler(Looper.getMainLooper());
    private MediaSessionManager msm;
    private MediaController ctrl;
    private boolean listening = false;
    private String artKey = "", artData = null;

    private final MediaController.Callback cb = new MediaController.Callback() {
        @Override public void onMetadataChanged(MediaMetadata m) { emit(); }
        @Override public void onPlaybackStateChanged(PlaybackState s) { if (s != null && s.getState() != PlaybackState.STATE_PLAYING) handler.postDelayed(CarPlugin.this::pick, 1500); emit(); }
        @Override public void onSessionDestroyed() { pick(); }
        @Override public void onQueueChanged(List<MediaSession.QueueItem> q) { emit(); }
    };
    private final MediaSessionManager.OnActiveSessionsChangedListener sessions = this::pick;

    @Override public void load() {
        msm = (MediaSessionManager) getContext().getSystemService(Context.MEDIA_SESSION_SERVICE);
        MediaListener.plugin = this;
        init();
        if (MediaListener.inst != null) MediaListener.inst.scan();
    }

    /* ---------- Applications ---------- */

    private static String png(Drawable d, int size) {
        Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, size, size);
        d.draw(c);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    @PluginMethod public void listApps(PluginCall call) {
        getBridge().execute(() -> {
            PackageManager pm = getContext().getPackageManager();
            Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> list = pm.queryIntentActivities(i, 0);
            Collections.sort(list, new ResolveInfo.DisplayNameComparator(pm));
            JSArray arr = new JSArray();
            String self = getContext().getPackageName();
            for (ResolveInfo ri : list) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(self)) continue;
                JSObject o = new JSObject();
                o.put("name", ri.loadLabel(pm).toString());
                o.put("pkg", pkg);
                try { o.put("icon", png(ri.loadIcon(pm), 128)); } catch (Exception ignored) {}
                arr.put(o);
            }
            JSObject res = new JSObject();
            res.put("apps", arr);
            call.resolve(res);
        });
    }

    @PluginMethod public void launch(PluginCall call) {
        String pkg = call.getString("pkg", "");
        Intent i = getContext().getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null) { call.reject("Introuvable"); return; }
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(i);
        addRecent(pkg);
        call.resolve();
    }

    @PluginMethod public void openUrl(PluginCall call) {
        String url = call.getString("url", "");
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (url.contains("waze.com")) i.setPackage("com.waze");
        try { getContext().startActivity(i); }
        catch (ActivityNotFoundException e) {
            i.setPackage(null);
            try { getContext().startActivity(i); } catch (ActivityNotFoundException e2) { call.reject("Aucune app"); return; }
        }
        call.resolve();
    }

    /* ---------- OBD2 (ELM327 Bluetooth) ---------- */

    private static final UUID SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");
    private volatile boolean obdRun = false;
    private Thread obdThread;
    private volatile BluetoothSocket sock;
    private String obdAddr;

    private boolean btOk() { return Build.VERSION.SDK_INT < 31 || getPermissionState("bt") == PermissionState.GRANTED; }

    @PluginMethod public void obdDevices(PluginCall call) {
        if (!btOk()) { requestPermissionForAlias("bt", call, "btCb"); return; }
        listBonded(call);
    }

    @PluginMethod public void obdConnect(PluginCall call) {
        if (!btOk()) { requestPermissionForAlias("bt", call, "btCb"); return; }
        startObd(call);
    }

    @PluginMethod public void obdDisconnect(PluginCall call) { stopObd(); call.resolve(); }

    @PermissionCallback private void btCb(PluginCall call) {
        if (!btOk()) { call.reject("Permission Bluetooth refusée"); return; }
        if ("obdDevices".equals(call.getMethodName())) listBonded(call); else startObd(call);
    }

    @SuppressLint("MissingPermission")
    private void listBonded(PluginCall call) {
        BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
        JSArray arr = new JSArray();
        if (a != null) for (BluetoothDevice d : a.getBondedDevices()) {
            JSObject o = new JSObject();
            o.put("name", d.getName() == null ? d.getAddress() : d.getName());
            o.put("address", d.getAddress());
            arr.put(o);
        }
        JSObject r = new JSObject();
        r.put("devices", arr);
        call.resolve(r);
    }

    private void startObd(PluginCall call) {
        String a = call.getString("address");
        if (a == null) { call.reject("Adresse manquante"); return; }
        stopObd();
        obdAddr = a;
        obdRun = true;
        obdThread = new Thread(this::obdLoop);
        obdThread.start();
        call.resolve();
    }

    private void stopObd() {
        obdRun = false;
        try { if (sock != null) sock.close(); } catch (Exception ignored) {}
        sock = null;
        if (obdThread != null) obdThread.interrupt();
    }

    private void obdStatus(String s) { JSObject o = new JSObject(); o.put("status", s); notifyListeners("obd", o); }

    @SuppressLint("MissingPermission")
    private void obdLoop() {
        while (obdRun) {
            try {
                obdStatus("connecting");
                BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter();
                a.cancelDiscovery();
                BluetoothDevice d = a.getRemoteDevice(obdAddr);
                try {
                    sock = d.createRfcommSocketToServiceRecord(SPP);
                    sock.connect();
                } catch (IOException e) {
                    sock = (BluetoothSocket) d.getClass().getMethod("createRfcommSocket", int.class).invoke(d, 1);
                    sock.connect();
                }
                InputStream in = sock.getInputStream();
                OutputStream out = sock.getOutputStream();
                cmd(in, out, "ATZ", 3000);
                for (String c : new String[]{"ATE0", "ATL0", "ATS0", "ATH0", "ATSP0"}) cmd(in, out, c, 1500);
                cmd(in, out, "0100", 10000);
                obdStatus("connected");
                Map<String, Integer> fails = new HashMap<>();
                String[] fast = {"010C", "010D", "015E", "0110"};
                String[] slow = {"0105", "010F", "0104", "012F", "0146"};
                int n = 0;
                while (obdRun) {
                    JSObject o = new JSObject();
                    o.put("status", "connected");
                    for (String p : fast) pid(in, out, p, fails, o, n);
                    if (n % 8 == 0) {
                        for (String p : slow) pid(in, out, p, fails, o, n);
                        String v = cmd(in, out, "ATRV", 1500).replaceAll("[^0-9.]", "");
                        try { if (!v.isEmpty()) o.put("volt", Double.parseDouble(v)); } catch (NumberFormatException ignored) {}
                    }
                    notifyListeners("obd", o);
                    n++;
                    Thread.sleep(120);
                }
            } catch (Exception e) {
                try { if (sock != null) sock.close(); } catch (Exception ignored) {}
                if (!obdRun) break;
                obdStatus("error");
                try { Thread.sleep(8000); } catch (InterruptedException ie) { break; }
            }
        }
        obdStatus("off");
    }

    private String cmd(InputStream in, OutputStream out, String c, int timeout) throws IOException, InterruptedException {
        out.write((c + "\r").getBytes());
        out.flush();
        StringBuilder sb = new StringBuilder();
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            if (in.available() > 0) { int b = in.read(); if (b == '>') break; sb.append((char) b); }
            else Thread.sleep(8);
        }
        return sb.toString().replace("SEARCHING...", "").replaceAll("\\s", "").toUpperCase();
    }

    private void pid(InputStream in, OutputStream out, String p, Map<String, Integer> fails, JSObject o, int n) throws IOException, InterruptedException {
        Integer f = fails.get(p);
        if (f != null && f >= 4 && n % 60 != 0) return;
        String r = cmd(in, out, p, 1200);
        int i = r.indexOf("41" + p.substring(2));
        if (i < 0) { fails.put(p, (f == null ? 0 : f) + 1); return; }
        fails.put(p, 0);
        String h = r.substring(i + 4);
        if (h.length() < 2) return;
        int A, B = 0;
        try {
            A = Integer.parseInt(h.substring(0, 2), 16);
            if (h.length() >= 4) B = Integer.parseInt(h.substring(2, 4), 16);
        } catch (NumberFormatException e) { return; }
        switch (p) {
            case "010C": o.put("rpm", (A * 256 + B) / 4); break;
            case "010D": o.put("speed", A); break;
            case "015E": o.put("fuelRate", (A * 256 + B) / 20.0); break;
            case "0110": o.put("maf", (A * 256 + B) / 100.0); break;
            case "0105": o.put("coolant", A - 40); break;
            case "010F": o.put("intake", A - 40); break;
            case "0104": o.put("load", Math.round(A * 100 / 255.0)); break;
            case "012F": o.put("fuel", Math.round(A * 100 / 255.0)); break;
            case "0146": o.put("ambient", A - 40); break;
        }
    }

    @Override protected void handleOnDestroy() { stopObd(); if (viz != null) { try { viz.release(); } catch (Exception ignored) {} viz = null; } super.handleOnDestroy(); }

    /* ---------- Musique ---------- */

    private ComponentName cn() { return new ComponentName(getContext(), MediaListener.class); }

    private boolean allowed() {
        String s = Settings.Secure.getString(getContext().getContentResolver(), "enabled_notification_listeners");
        return s != null && s.contains(getContext().getPackageName());
    }

    private void init() {
        if (listening || !allowed()) return;
        try {
            msm.addOnActiveSessionsChangedListener(sessions, cn(), handler);
            listening = true;
            pick();
        } catch (SecurityException ignored) {}
    }

    private void pick() {
        try { pick(msm.getActiveSessions(cn())); } catch (SecurityException ignored) {}
    }

    private void pick(List<MediaController> list) {
        MediaController best = null;
        if (list != null) {
            for (MediaController c : list) {
                PlaybackState ps = c.getPlaybackState();
                if (ps != null && ps.getState() == PlaybackState.STATE_PLAYING) { best = c; break; }
            }
            if (best == null) for (MediaController c : list) if (YTM.equals(c.getPackageName())) { best = c; break; }
            if (best == null && !list.isEmpty()) best = list.get(0);
        }
        if (ctrl != null) ctrl.unregisterCallback(cb);
        ctrl = best;
        if (ctrl != null) ctrl.registerCallback(cb, handler);
        emit();
    }

    private String art(MediaMetadata m, String key) {
        if (key.equals(artKey)) return artData;
        Bitmap b = m.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_ART);
        if (b == null) b = m.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON);
        artKey = key;
        artData = null;
        if (b != null) {
            int w = 360, h = Math.max(1, b.getHeight() * w / Math.max(1, b.getWidth()));
            Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            s.compress(Bitmap.CompressFormat.JPEG, 85, out);
            artData = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        }
        return artData;
    }

    private JSObject data() {
        JSObject o = new JSObject();
        o.put("permission", allowed());
        if (ctrl == null) { o.put("title", ""); o.put("artist", ""); o.put("playing", false); return o; }
        MediaMetadata m = ctrl.getMetadata();
        if (m != null) {
            String t = m.getString(MediaMetadata.METADATA_KEY_TITLE), a = m.getString(MediaMetadata.METADATA_KEY_ARTIST);
            o.put("title", t == null ? "" : t);
            o.put("artist", a == null ? "" : a);
            o.put("duration", m.getLong(MediaMetadata.METADATA_KEY_DURATION));
            o.put("art", art(m, t + "|" + a));
        }
        PlaybackState s = ctrl.getPlaybackState();
        if (s != null) {
            boolean playing = s.getState() == PlaybackState.STATE_PLAYING;
            long pos = s.getPosition();
            if (playing) pos += (long) ((SystemClock.elapsedRealtime() - s.getLastPositionUpdateTime()) * s.getPlaybackSpeed());
            o.put("playing", playing);
            o.put("position", pos);
            o.put("activeId", s.getActiveQueueItemId());
            JSArray acts = new JSArray();
            List<PlaybackState.CustomAction> cas = s.getCustomActions();
            if (cas != null) for (PlaybackState.CustomAction ca : cas) {
                JSObject a = new JSObject();
                a.put("action", ca.getAction());
                a.put("name", String.valueOf(ca.getName()));
                acts.put(a);
            }
            o.put("actions", acts);
        }
        List<MediaSession.QueueItem> q = ctrl.getQueue();
        JSArray qa = new JSArray();
        if (q != null) {
            int start = 0;
            if (s != null) for (int i = 0; i < q.size(); i++) if (q.get(i).getQueueId() == s.getActiveQueueItemId()) { start = Math.max(0, i - 1); break; }
            for (int i = start; i < Math.min(q.size(), start + 25); i++) {
                MediaSession.QueueItem it = q.get(i);
                JSObject e = new JSObject();
                e.put("id", it.getQueueId());
                e.put("title", it.getDescription().getTitle() == null ? "" : it.getDescription().getTitle().toString());
                e.put("artist", it.getDescription().getSubtitle() == null ? "" : it.getDescription().getSubtitle().toString());
                qa.put(e);
            }
        }
        o.put("queue", qa);
        return o;
    }

    private void emit() { notifyListeners("media", data()); }

    @PluginMethod public void getNowPlaying(PluginCall call) { init(); call.resolve(data()); }

    @PluginMethod public void control(PluginCall call) {
        if (ctrl == null) { call.reject("Aucun média"); return; }
        MediaController.TransportControls t = ctrl.getTransportControls();
        switch (call.getString("action", "")) {
            case "toggle":
                PlaybackState s = ctrl.getPlaybackState();
                if (s != null && s.getState() == PlaybackState.STATE_PLAYING) t.pause(); else t.play();
                break;
            case "next": t.skipToNext(); break;
            case "previous": t.skipToPrevious(); break;
        }
        call.resolve();
    }

    @PluginMethod public void openPermission(PluginCall call) {
        java.util.ArrayList<Intent> tries = new java.util.ArrayList<>();
        if (Build.VERSION.SDK_INT >= 30) {
            Intent d = new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS");
            d.putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME", cn().flattenToString());
            tries.add(d);
        }
        tries.add(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
        tries.add(new Intent().setClassName("com.android.settings", "com.android.settings.Settings$NotificationAccessSettingsActivity"));
        tries.add(new Intent().setClassName("com.android.settings", "com.android.settings.Settings$ManageApplicationsActivity"));
        for (Intent i : tries) {
            try { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(i); call.resolve(); return; }
            catch (Exception ignored) {}
        }
        call.reject("Réglage introuvable");
    }

    @PluginMethod public void seek(PluginCall call) {
        if (ctrl == null) { call.reject("Aucun média"); return; }
        ctrl.getTransportControls().seekTo(call.getLong("ms", 0L));
        call.resolve();
    }

    @PluginMethod public void queueSkip(PluginCall call) {
        if (ctrl == null) { call.reject("Aucun média"); return; }
        ctrl.getTransportControls().skipToQueueItem(call.getLong("id", 0L));
        call.resolve();
    }

    @PluginMethod public void customAction(PluginCall call) {
        if (ctrl == null) { call.reject("Aucun média"); return; }
        ctrl.getTransportControls().sendCustomAction(call.getString("action", ""), null);
        call.resolve();
    }

    @PluginMethod public void getVolume(PluginCall call) {
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        JSObject r = new JSObject();
        r.put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC));
        r.put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        call.resolve(r);
    }

    @PluginMethod public void setVolume(PluginCall call) {
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        am.setStreamVolume(AudioManager.STREAM_MUSIC, call.getInt("vol", 0), 0);
        call.resolve();
    }

    @PluginMethod public void adjustVolume(PluginCall call) {
        AudioManager am = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
        int d = call.getInt("dir", 1);
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d > 0 ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
        JSObject r = new JSObject();
        r.put("vol", am.getStreamVolume(AudioManager.STREAM_MUSIC));
        r.put("max", am.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        call.resolve(r);
    }

    /* ---------- Tension batterie (autoradio) ---------- */

    private static double plausible(double x) {
        while (x > 100) x /= 1000;
        return (x > 8 && x < 17) ? x : -1;
    }

    @PluginMethod public void getVoltage(PluginCall call) {
        double v = -1;
        Intent b = getContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (b != null) {
            int mv = b.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
            if (mv > 0) v = plausible(mv);
        }
        if (v < 0) {
            File[] fs = new File("/sys/class/power_supply").listFiles();
            if (fs != null) outer:
            for (File f : fs) for (String n : new String[]{"voltage_now", "batt_vol", "voltage_avg"}) {
                File vf = new File(f, n);
                if (!vf.canRead()) continue;
                try (BufferedReader r = new BufferedReader(new FileReader(vf))) {
                    double x = plausible(Double.parseDouble(r.readLine().trim()));
                    if (x > 0) { v = x; break outer; }
                } catch (Exception ignored) {}
            }
        }
        JSObject r = new JSObject();
        if (v > 0) r.put("volt", Math.round(v * 10) / 10.0);
        call.resolve(r);
    }

    /* ---------- Lancement automatique ---------- */

    @PluginMethod public void canAutostart(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ok", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext()));
        call.resolve(r);
    }

    @PluginMethod public void openAutostart(PluginCall call) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        } catch (Exception e) {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        }
        call.resolve();
    }

    /* ---------- Waze (notification de guidage) ---------- */

    private JSObject nav = new JSObject();
    private int navIconKey = 0;
    private String navIcon = null;

    void navUpdate(Notification n) {
        Bundle e = n.extras;
        JSObject o = new JSObject();
        o.put("active", true);
        CharSequence t = e.getCharSequence(Notification.EXTRA_TITLE), x = e.getCharSequence(Notification.EXTRA_TEXT), sub = e.getCharSequence(Notification.EXTRA_SUB_TEXT);
        o.put("title", t == null ? "" : t.toString());
        o.put("text", x == null ? "" : x.toString());
        o.put("sub", sub == null ? "" : sub.toString());
        try {
            Icon ic = n.getLargeIcon();
            if (ic != null) {
                Drawable d = ic.loadDrawable(getContext());
                int key = d == null ? 0 : (o.getString("title") + d.getIntrinsicWidth()).hashCode();
                if (d != null && key != navIconKey) { navIconKey = key; navIcon = png(d, 128); }
                o.put("icon", navIcon);
            }
        } catch (Exception ignored) {}
        nav = o;
        notifyListeners("nav", o);
    }

    void navGone() {
        JSObject o = new JSObject();
        o.put("active", false);
        nav = o;
        notifyListeners("nav", o);
    }

    @PluginMethod public void getNav(PluginCall call) {
        if (MediaListener.inst != null) MediaListener.inst.scan();
        call.resolve(nav);
    }

    /* ---------- Visualiseur audio (halo musical) ---------- */

    private Visualizer viz;
    private float vizLvl = 0f;

    @PluginMethod public void vizStart(PluginCall call) {
        if (getPermissionState("mic") != PermissionState.GRANTED) { requestPermissionForAlias("mic", call, "micCb"); return; }
        startViz(call);
    }

    @PermissionCallback private void micCb(PluginCall call) {
        if (getPermissionState("mic") == PermissionState.GRANTED) startViz(call); else call.reject("Micro refusé");
    }

    private void startViz(PluginCall call) {
        try {
            if (viz != null) { call.resolve(); return; }
            viz = new Visualizer(0);
            viz.setEnabled(false);
            viz.setCaptureSize(Visualizer.getCaptureSizeRange()[0]);
            viz.setDataCaptureListener(new Visualizer.OnDataCaptureListener() {
                @Override public void onWaveFormDataCapture(Visualizer v, byte[] w, int sr) {}
                @Override public void onFftDataCapture(Visualizer v, byte[] f, int sr) {
                    double e = 0;
                    for (int k = 1; k < 8 && 2 * k + 1 < f.length; k++) e += Math.hypot(f[2 * k], f[2 * k + 1]);
                    float x = (float) Math.min(1.0, e / 380.0);
                    vizLvl = Math.max(x, vizLvl * 0.82f);
                    JSObject o = new JSObject();
                    o.put("l", (double) vizLvl);
                    notifyListeners("viz", o);
                }
            }, Math.min(Visualizer.getMaxCaptureRate(), 20000), false, true);
            viz.setEnabled(true);
            call.resolve();
        } catch (Exception e) {
            if (viz != null) { try { viz.release(); } catch (Exception ignored) {} }
            viz = null;
            call.reject("Visualiseur indisponible");
        }
    }

    @PluginMethod public void vizStop(PluginCall call) {
        if (viz != null) { try { viz.setEnabled(false); viz.release(); } catch (Exception ignored) {} viz = null; }
        call.resolve();
    }

    /* ---------- Interface système ---------- */

    private SharedPreferences prefs() { return getContext().getSharedPreferences(TaskbarService.PREFS, Context.MODE_PRIVATE); }

    private void addRecent(String pkg) {
        java.util.ArrayList<String> l = new java.util.ArrayList<>();
        l.add(pkg);
        for (String x : prefs().getString("recents", "").split(",")) if (!x.isEmpty() && !x.equals(pkg) && l.size() < 10) l.add(x);
        prefs().edit().putString("recents", android.text.TextUtils.join(",", l)).apply();
    }

    @PluginMethod public void getRecents(PluginCall call) {
        JSArray a = new JSArray();
        for (String x : prefs().getString("recents", "").split(",")) if (!x.isEmpty()) a.put(x);
        JSObject r = new JSObject(); r.put("recents", a); call.resolve(r);
    }

    @PluginMethod public void setPinned(PluginCall call) {
        try {
            JSArray a = call.getArray("pkgs");
            java.util.ArrayList<String> l = new java.util.ArrayList<>();
            for (int i = 0; i < a.length(); i++) l.add(a.getString(i));
            prefs().edit().putString("pinned", android.text.TextUtils.join(",", l)).apply();
        } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod public void setTaskbar(PluginCall call) {
        prefs().edit().putBoolean("taskbar", call.getBoolean("on", true)).apply();
        if (!call.getBoolean("on", true)) TaskbarService.hide(getContext());
        call.resolve();
    }

    @PluginMethod public void getStatus(PluginCall call) {
        JSObject r = new JSObject();
        try {
            ConnectivityManager cm = (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            r.put("wifi", nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
            r.put("cell", nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR));
            r.put("online", nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET));
        } catch (Exception ignored) {}
        try { BluetoothAdapter a = BluetoothAdapter.getDefaultAdapter(); r.put("bt", a != null && a.isEnabled()); } catch (Exception ignored) {}
        r.put("overlay", Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(getContext()));
        r.put("writeSettings", Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(getContext()));
        try { r.put("brightness", Settings.System.getInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS) / 255.0); } catch (Exception ignored) {}
        call.resolve(r);
    }

    @PluginMethod public void setBrightness(PluginCall call) {
        final float v = Math.max(0.02f, Math.min(1f, call.getFloat("v", 0.7f)));
        boolean sys = false;
        if (Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(getContext())) {
            try {
                Settings.System.putInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL);
                Settings.System.putInt(getContext().getContentResolver(), Settings.System.SCREEN_BRIGHTNESS, Math.round(v * 255));
                sys = true;
            } catch (Exception ignored) {}
        }
        final boolean s2 = sys;
        getActivity().runOnUiThread(() -> {
            WindowManager.LayoutParams lp = getActivity().getWindow().getAttributes();
            lp.screenBrightness = s2 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : v;
            getActivity().getWindow().setAttributes(lp);
        });
        JSObject r = new JSObject(); r.put("system", sys); call.resolve(r);
    }

    @PluginMethod public void openWriteSettings(PluginCall call) {
        startSettings(new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + getContext().getPackageName())));
        call.resolve();
    }

    private void startSettings(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { getContext().startActivity(i); }
        catch (Exception e) { try { getContext().startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Exception ignored) {} }
    }

    @PluginMethod public void openSettings(PluginCall call) {
        String w = call.getString("what", "all");
        String a;
        switch (w) {
            case "wifi": a = Settings.ACTION_WIFI_SETTINGS; break;
            case "bt": a = Settings.ACTION_BLUETOOTH_SETTINGS; break;
            case "display": a = Settings.ACTION_DISPLAY_SETTINGS; break;
            case "sound": a = Settings.ACTION_SOUND_SETTINGS; break;
            case "apps": a = Settings.ACTION_APPLICATION_SETTINGS; break;
            case "date": a = Settings.ACTION_DATE_SETTINGS; break;
            case "storage": a = Settings.ACTION_INTERNAL_STORAGE_SETTINGS; break;
            case "lang": a = Settings.ACTION_LOCALE_SETTINGS; break;
            case "network": a = Settings.ACTION_WIRELESS_SETTINGS; break;
            case "home": a = Settings.ACTION_HOME_SETTINGS; break;
            case "dev": a = Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS; break;
            case "about": a = Settings.ACTION_DEVICE_INFO_SETTINGS; break;
            default: a = Settings.ACTION_SETTINGS;
        }
        startSettings(new Intent(a));
        call.resolve();
    }

    @PluginMethod public void appInfo(PluginCall call) {
        startSettings(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + call.getString("pkg", ""))));
        call.resolve();
    }

    @PluginMethod public void uninstall(PluginCall call) {
        Intent i = new Intent(Intent.ACTION_DELETE, Uri.parse("package:" + call.getString("pkg", "")));
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try { getContext().startActivity(i); call.resolve(); } catch (Exception e) { call.reject("Impossible"); }
    }

    /* ---------- Centre de notifications ---------- */

    private final java.util.HashMap<String, String> iconCache = new java.util.HashMap<>();
    private long lastNotifEmit = 0;

    void notifsChanged() {
        long t = SystemClock.uptimeMillis();
        if (t - lastNotifEmit < 300) return;
        lastNotifEmit = t;
        handler.postDelayed(() -> notifyListeners("notifs", notifData()), 50);
    }

    private JSObject notifData() {
        JSArray a = new JSArray();
        MediaListener L = MediaListener.inst;
        if (L != null) {
            PackageManager pm = getContext().getPackageManager();
            for (StatusBarNotification sb : L.list()) {
                Notification n = sb.getNotification();
                if (sb.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                if (sb.getPackageName().equals(getContext().getPackageName())) continue;
                Bundle e = n.extras;
                CharSequence t = e.getCharSequence(Notification.EXTRA_TITLE), x = e.getCharSequence(Notification.EXTRA_TEXT);
                if (t == null && x == null) continue;
                JSObject o = new JSObject();
                o.put("key", sb.getKey());
                o.put("pkg", sb.getPackageName());
                o.put("title", t == null ? "" : t.toString());
                o.put("text", x == null ? "" : x.toString());
                o.put("time", sb.getPostTime());
                String pk = sb.getPackageName();
                try {
                    ApplicationInfo ai = pm.getApplicationInfo(pk, 0);
                    o.put("app", pm.getApplicationLabel(ai).toString());
                    if (!iconCache.containsKey(pk)) iconCache.put(pk, png(pm.getApplicationIcon(pk), 64));
                    o.put("icon", iconCache.get(pk));
                } catch (Exception ignored) { o.put("app", pk); }
                a.put(o);
            }
        }
        JSObject r = new JSObject();
        r.put("list", a);
        r.put("enabled", allowed());
        return r;
    }

    @PluginMethod public void getNotifs(PluginCall call) { call.resolve(notifData()); }

    private StatusBarNotification findNotif(String key) {
        MediaListener L = MediaListener.inst;
        if (L == null) return null;
        for (StatusBarNotification sb : L.list()) if (sb.getKey().equals(key)) return sb;
        return null;
    }

    @PluginMethod public void openNotif(PluginCall call) {
        StatusBarNotification sb = findNotif(call.getString("key", ""));
        if (sb == null) { call.reject("Introuvable"); return; }
        try {
            PendingIntent pi = sb.getNotification().contentIntent;
            if (pi != null) pi.send();
            else { Intent i = getContext().getPackageManager().getLaunchIntentForPackage(sb.getPackageName()); if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); getContext().startActivity(i); } }
            if ((sb.getNotification().flags & Notification.FLAG_AUTO_CANCEL) != 0 && MediaListener.inst != null) MediaListener.inst.cancelNotification(sb.getKey());
        } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod public void dismissNotif(PluginCall call) {
        try { if (MediaListener.inst != null) MediaListener.inst.cancelNotification(call.getString("key", "")); } catch (Exception ignored) {}
        call.resolve();
    }

    @PluginMethod public void clearNotifs(PluginCall call) {
        try { if (MediaListener.inst != null) MediaListener.inst.cancelAllNotifications(); } catch (Exception ignored) {}
        call.resolve();
    }

    @Override protected void handleOnResume() { super.handleOnResume(); init(); emit(); }
}
