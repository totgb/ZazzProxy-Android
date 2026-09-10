package com.totgb.zazzproxy.ui;

import android.content.Context;
import android.view.MotionEvent;

import com.google.android.material.button.MaterialButton;

/** A deterministic waypoint press response that does not depend on system animations. */
public final class MacMotionButton extends MaterialButton {
    private boolean pressed;

    public MacMotionButton(Context context) {
        super(context);
        setAllCaps(false);
        setCornerRadius(28);
        setOnTouchListener((view, event) -> {
            if (event.getAction() == MotionEvent.ACTION_DOWN) {
                pressed = true;
                moveWaypoints(1);
            } else if (event.getAction() == MotionEvent.ACTION_UP
                    || event.getAction() == MotionEvent.ACTION_CANCEL) {
                pressed = false;
                moveWaypoints(0);
            }
            return false;
        });
    }

    private void moveWaypoints(int stage) {
        if (!pressed && stage != 0) return;
        setTranslationX(stage == 1 ? 2 : stage == 2 ? -1 : 0);
        setTranslationY(stage == 1 ? 3 : stage == 2 ? 1 : 0);
        if (stage == 1) postDelayed(() -> moveWaypoints(2), 45);
        if (stage == 2) postDelayed(() -> moveWaypoints(3), 45);
    }
}
