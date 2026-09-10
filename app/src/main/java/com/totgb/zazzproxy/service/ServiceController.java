package com.totgb.zazzproxy.service;

import android.content.Context;
import android.content.Intent;
import android.os.Build;

/** Programmatic controller for the optional foreground sharing service. */
public final class ServiceController {
    private ServiceController() {}

    public static void start(Context context) {
        Intent intent = new Intent(context, ZazzBackgroundService.class);
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    public static void stop(Context context) {
        context.stopService(new Intent(context, ZazzBackgroundService.class));
    }
}
