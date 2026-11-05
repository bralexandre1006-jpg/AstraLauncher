package com.rst.astralauncher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

public class BootReceiver extends BroadcastReceiver {
    private static long lastBurst = 0;

    @Override
    public void onReceive(Context ctx, Intent intent) {
        Context app = ctx.getApplicationContext();
        TaskbarService.keep(app);
        launchBurst(app);
    }

    /**
     * Ramène le launcher au premier plan plusieurs fois après l'allumage :
     * beaucoup d'autoradios relancent la dernière appli (YouTube Music…) quelques
     * secondes après le contact, on repasse donc derrière elle.
     */
    static void launchBurst(Context app) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastBurst < 15000) return;
        lastBurst = now;
        Handler h = new Handler(Looper.getMainLooper());
        for (long d : new long[]{800, 3000, 6000, 10000}) {
            h.postDelayed(() -> {
                try {
                    Intent i = new Intent(app, MainActivity.class);
                    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                    app.startActivity(i);
                } catch (Exception ignored) {}
            }, d);
        }
    }
}
