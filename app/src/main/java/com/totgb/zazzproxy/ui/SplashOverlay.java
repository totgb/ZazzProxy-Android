package com.totgb.zazzproxy.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.PropertyValuesHolder;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

/** Fully programmatic splash animation; tapping anywhere completes it immediately. */
public final class SplashOverlay extends FrameLayout {
    public interface Completion { void run(); }

    private boolean completed;
    private final Completion completion;

    public SplashOverlay(Context context, Completion completion) {
        super(context);
        this.completion = completion;
        setBackgroundColor(Color.rgb(10, 18, 35));
        setClickable(true);
        TextView mark = new TextView(context);
        mark.setText("Z");
        mark.setTextColor(Color.WHITE);
        mark.setTextSize(42);
        mark.setGravity(Gravity.CENTER);
        GradientDrawable background = new GradientDrawable();
        background.setShape(GradientDrawable.OVAL);
        background.setColor(Color.rgb(37, 99, 235));
        mark.setBackground(background);
        LayoutParams params = new LayoutParams(88, 88);
        params.gravity = Gravity.BOTTOM | Gravity.START;
        params.setMargins(24, 24, 24, 24);
        addView(mark, params);
        setOnClickListener(v -> finish());
        post(() -> animateMark(mark));
    }

    private void animateMark(View mark) {
        float width = getResources().getDisplayMetrics().widthPixels - mark.getWidth() - 48;
        float height = getResources().getDisplayMetrics().heightPixels - mark.getHeight() - 96;
        PropertyValuesHolder x = PropertyValuesHolder.ofFloat(View.TRANSLATION_X, 0, 0, width, width, 0);
        PropertyValuesHolder y = PropertyValuesHolder.ofFloat(View.TRANSLATION_Y, 0, -height, -height, 0, -height / 2f);
        ObjectAnimator animator = ObjectAnimator.ofPropertyValuesHolder(mark, x, y);
        animator.setDuration(1800);
        animator.setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator());
        animator.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) { finish(); }
        });
        animator.start();
    }

    private void finish() {
        if (completed) return;
        completed = true;
        completion.run();
    }
}
