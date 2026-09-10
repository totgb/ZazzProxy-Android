package com.totgb.zazzproxy.network;

import android.os.Handler;
import android.os.Looper;

/** Keeps callback delivery off the UDP receiver and transfer scheduler threads. */
final class UiDispatcher {
    private final Handler handler = new Handler(Looper.getMainLooper());

    void post(Runnable callback) {
        handler.post(callback);
    }
}
