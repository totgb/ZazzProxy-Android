package com.totgb.zazzproxy.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.TextView;
import android.widget.Toast;

/** Branded status toast used for network state transitions. */
public final class MacToast {
    private MacToast() {}

    public static void show(Context context, String message, boolean positive) {
        new android.os.Handler(android.os.Looper.getMainLooper()).post(
                () -> showOnMain(context, message, positive));
    }

    private static void showOnMain(Context context, String message, boolean positive) {
        TextView view = new TextView(context);
        view.setText(message);
        view.setTextColor(Color.WHITE);
        view.setTextSize(14);
        view.setGravity(Gravity.CENTER);
        view.setPadding(38, 18, 38, 18);
        GradientDrawable background = new GradientDrawable();
        background.setColor(positive ? Color.rgb(24, 130, 92) : Color.rgb(58, 63, 78));
        background.setCornerRadius(60);
        view.setBackground(background);
        Toast toast = new Toast(context);
        toast.setView(view);
        toast.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, 88);
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.show();
    }
}
