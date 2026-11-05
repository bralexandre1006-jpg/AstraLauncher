package com.rst.astralauncher;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Build;
import android.os.IBinder;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;

/** Barre des tâches flottante affichée par-dessus les autres applis. */
public class TaskbarService extends Service {
    static final String PREFS = "astra";
    private WindowManager wm;
    private LinearLayout bar, apps;
    private WindowManager.LayoutParams lp;
    private boolean shown = false, collapsed = false;

    static void show(Context c) { send(c, "show"); }
    static void hide(Context c) { send(c, "hide"); }

    private static void send(Context c, String a) {
        SharedPreferences p = c.getSharedPreferences(PREFS, MODE_PRIVATE);
        if ("show".equals(a) && !p.getBoolean("taskbar", true)) return;
        if (Build.VERSION.SDK_INT >= 23 && !Settings.canDrawOverlays(c)) return;
        Intent i = new Intent(c, TaskbarService.class).setAction(a);
        try {
            if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i);
        } catch (Exception ignored) {}
    }

    private int dp(float v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics()); }

    @Override public void onCreate() {
        super.onCreate();
        fg();
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        build();
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction("com.fyt.boot.ACCON");
        f.addAction("android.intent.action.ACC_ON");
        f.addAction("com.ts.main.uiaccon");
        f.addAction("autochips.intent.action.QB_POWERON");
        try {
            if (Build.VERSION.SDK_INT >= 33) registerReceiver(wake, f, Context.RECEIVER_EXPORTED); else registerReceiver(wake, f);
        } catch (Exception ignored) {}
    }

    private final android.content.BroadcastReceiver wake = new android.content.BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) { BootReceiver.launchBurst(getApplicationContext()); }
    };

    static void keep(Context c) {
        Intent i = new Intent(c, TaskbarService.class).setAction("keep");
        try { if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i); else c.startService(i); } catch (Exception ignored) {}
    }

    private void fg() {
        if (Build.VERSION.SDK_INT < 26) return;
        try {
            NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            nm.createNotificationChannel(new NotificationChannel("bar", "Barre des tâches", NotificationManager.IMPORTANCE_MIN));
            Notification n = new Notification.Builder(this, "bar").setContentTitle("Astra Launcher").setSmallIcon(getApplicationInfo().icon).build();
            startForeground(7, n);
        } catch (Exception ignored) {}
    }

    private void build() {
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.argb(225, 14, 16, 24));
        bg.setCornerRadius(dp(30));
        bg.setStroke(dp(1), Color.argb(40, 255, 255, 255));
        bar.setBackground(bg);
        bar.setElevation(dp(10));

        View grip = new Icon(this, 'g');
        bar.addView(grip, new LinearLayout.LayoutParams(dp(26), dp(52)));
        addBtn('h', v -> goHome(), v -> { collapsed = !collapsed; apply(); return true; });
        addBtn('p', v -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS), null);
        addBtn('t', v -> key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE), null);
        addBtn('n', v -> key(KeyEvent.KEYCODE_MEDIA_NEXT), null);
        View sep = new View(this);
        sep.setBackgroundColor(Color.argb(40, 255, 255, 255));
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(dp(1), dp(34));
        sl.setMargins(dp(6), 0, dp(6), 0);
        bar.addView(sep, sl);
        apps = new LinearLayout(this);
        apps.setOrientation(LinearLayout.HORIZONTAL);
        bar.addView(apps);

        int type = Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
        lp = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        lp.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lp.x = p.getInt("barX", 0);
        lp.y = p.getInt("barY", dp(18));

        grip.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: sx = e.getRawX(); sy = e.getRawY(); ox = lp.x; oy = lp.y; return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = ox + (int) (e.getRawX() - sx);
                        lp.y = Math.max(0, oy - (int) (e.getRawY() - sy));
                        if (shown) wm.updateViewLayout(bar, lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt("barX", lp.x).putInt("barY", lp.y).apply();
                        return true;
                }
                return false;
            }
        });
    }

    private void addBtn(char k, View.OnClickListener c, View.OnLongClickListener l) {
        Icon b = new Icon(this, k);
        b.setOnClickListener(c);
        if (l != null) b.setOnLongClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(52), dp(52));
        p.setMargins(dp(2), 0, dp(2), 0);
        bar.addView(b, p);
    }

    private void fillApps() {
        apps.removeAllViews();
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        java.util.LinkedHashSet<String> list = new java.util.LinkedHashSet<>();
        for (String s : p.getString("pinned", "").split(",")) if (!s.isEmpty()) list.add(s);
        for (String s : p.getString("recents", "").split(",")) if (!s.isEmpty()) list.add(s);
        PackageManager pm = getPackageManager();
        int n = 0;
        for (String pkg : list) {
            if (n >= 5) break;
            Intent li = pm.getLaunchIntentForPackage(pkg);
            if (li == null) continue;
            try {
                Drawable d = pm.getApplicationIcon(pkg);
                ImageView iv = new ImageView(this);
                iv.setImageDrawable(d);
                iv.setPadding(dp(6), dp(6), dp(6), dp(6));
                iv.setOnClickListener(v -> { li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(li); });
                LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(dp(52), dp(52));
                lp2.setMargins(dp(2), 0, dp(2), 0);
                apps.addView(iv, lp2);
                n++;
            } catch (Exception ignored) {}
        }
    }

    private void apply() {
        for (int i = 2; i < bar.getChildCount(); i++) bar.getChildAt(i).setVisibility(collapsed ? View.GONE : View.VISIBLE);
        if (shown) wm.updateViewLayout(bar, lp);
    }

    private void goHome() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
        startActivity(i);
    }

    private void key(int code) {
        AudioManager am = (AudioManager) getSystemService(AUDIO_SERVICE);
        long t = SystemClock.uptimeMillis();
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        am.dispatchMediaKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        fg();
        String a = intent == null ? "show" : intent.getAction();
        try {
            if ("show".equals(a)) {
                fillApps();
                apply();
                if (!shown) { wm.addView(bar, lp); shown = true; }
            } else if ("keep".equals(a)) {
                // service maintenu pour détecter le réveil, barre inchangée
            } else if (shown) {
                wm.removeView(bar);
                shown = false;
            }
        } catch (Exception ignored) {}
        return START_STICKY;
    }

    @Override public void onDestroy() {
        try { unregisterReceiver(wake); } catch (Exception ignored) {}
        if (shown) try { wm.removeView(bar); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent i) { return null; }

    /** Icônes vectorielles dessinées à la main (aucune ressource nécessaire). */
    static class Icon extends View {
        final char k;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        Icon(Context c, char k) {
            super(c);
            this.k = k;
            setClickable(true);
            GradientDrawable g = new GradientDrawable();
            g.setShape(GradientDrawable.OVAL);
            g.setColor(k == 'h' ? Color.argb(40, 255, 255, 255) : Color.TRANSPARENT);
            setBackground(g);
        }

        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), h = getHeight(), s = Math.min(w, h) / 24f, ox = (w - 24 * s) / 2, oy = (h - 24 * s) / 2;
            c.save();
            c.translate(ox, oy);
            c.scale(s, s);
            p.setColor(Color.WHITE);
            p.setStrokeWidth(2f);
            p.setStrokeCap(Paint.Cap.ROUND);
            p.setStrokeJoin(Paint.Join.ROUND);
            Path path = new Path();
            switch (k) {
                case 'h':
                    p.setStyle(Paint.Style.STROKE);
                    path.moveTo(4, 11); path.lineTo(12, 4.5f); path.lineTo(20, 11); path.lineTo(20, 19.5f); path.lineTo(14.5f, 19.5f);
                    path.lineTo(14.5f, 14); path.lineTo(9.5f, 14); path.lineTo(9.5f, 19.5f); path.lineTo(4, 19.5f); path.close();
                    break;
                case 'p':
                    p.setStyle(Paint.Style.FILL);
                    c.drawRect(5, 6, 7.5f, 18, p);
                    path.moveTo(19, 6); path.lineTo(9, 12); path.lineTo(19, 18); path.close();
                    break;
                case 'n':
                    p.setStyle(Paint.Style.FILL);
                    c.drawRect(16.5f, 6, 19, 18, p);
                    path.moveTo(5, 6); path.lineTo(15, 12); path.lineTo(5, 18); path.close();
                    break;
                case 't':
                    p.setStyle(Paint.Style.FILL);
                    path.moveTo(5, 5.5f); path.lineTo(12, 12); path.lineTo(5, 18.5f); path.close();
                    c.drawRect(14, 6, 16.2f, 18, p);
                    c.drawRect(18.2f, 6, 20.4f, 18, p);
                    break;
                case 'g':
                    p.setStyle(Paint.Style.FILL);
                    p.setColor(Color.argb(110, 255, 255, 255));
                    for (int y = 0; y < 3; y++) for (int x = 0; x < 2; x++) c.drawCircle(9 + x * 6, 7 + y * 5, 1.4f, p);
                    break;
            }
            c.drawPath(path, p);
            c.restore();
        }
    }
}
