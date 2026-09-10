package com.totgb.zazzproxy.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import java.util.Random;

/** Full-screen transfer completion burst shown only after a real file transfer. */
public final class FireworksView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random(42);
    private final float[] x = new float[18];
    private final float[] y = new float[18];
    private final int[] colors = new int[18];
    private final long started = System.currentTimeMillis();
    private boolean active;

    public FireworksView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        for (int i = 0; i < x.length; i++) {
            x[i] = 0.08f + random.nextFloat() * 0.84f;
            y[i] = 0.12f + random.nextFloat() * 0.62f;
            colors[i] = Color.HSVToColor(new float[]{random.nextInt(360), 0.8f, 1f});
        }
        setBackgroundColor(Color.argb(125, 5, 8, 18));
    }

    public void play() {
        active = true;
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        if (!active) return;
        float progress = Math.min(1f, (System.currentTimeMillis() - started) / 3000f);
        paint.setStrokeWidth(dp(3));
        for (int burst = 0; burst < x.length; burst++) {
            float radius = dp(8 + Math.round(105 * Math.min(1f, progress * 1.5f)));
            int alpha = (int) (255 * Math.max(0f, 1f - Math.max(0f, progress - 0.55f) / 0.45f));
            int color = colors[burst];
            paint.setColor(Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color)));
            float cx = getWidth() * x[burst];
            float cy = getHeight() * y[burst];
            for (int ray = 0; ray < 16; ray++) {
                double angle = ray * Math.PI / 8d;
                canvas.drawLine(cx, cy, cx + (float) Math.cos(angle) * radius,
                        cy + (float) Math.sin(angle) * radius, paint);
            }
        }
        if (progress < 1f) {
            postInvalidateDelayed(16);
        } else {
            active = false;
        }
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
