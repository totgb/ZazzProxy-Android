package com.totgb.zazzproxy.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

/** Occasional decorative chase using original blocky silhouettes and a sine-wave path. */
public final class BlockChaseView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private float progress;
    private boolean active;

    public BlockChaseView(Context context) {
        super(context);
        setVisibility(INVISIBLE);
    }

    public void play() {
        if (active) return;
        active = true;
        progress = 0f;
        setVisibility(VISIBLE);
        handler.post(frame);
    }

    private final Runnable frame = new Runnable() {
        @Override public void run() {
            if (!active) return;
            progress = Math.min(1f, progress + 0.0025f);
            invalidate();
            if (progress >= 1f) {
                active = false;
                setVisibility(INVISIBLE);
            } else {
                handler.postDelayed(this, 16);
            }
        }
    };

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float width = getWidth();
        float height = getHeight();
        float x = width * 0.62f;
        float centerY = height * 0.50f;
        float steveY;
        float creatureY;
        if (progress < 0.38f) {
            float approach = progress / 0.38f;
            float wave = (float) Math.sin(approach * Math.PI * 4) * dp(8);
            steveY = -dp(90) + (centerY - dp(45) + dp(90)) * approach + wave;
            creatureY = height + dp(60) - (height - centerY + dp(60)) * approach - wave;
        } else {
            float retreat = Math.min(1f, (progress - 0.38f) / 0.62f);
            float eased = retreat * retreat;
            steveY = centerY - dp(45) - (centerY + dp(100)) * eased;
            creatureY = centerY + dp(10) + (height - centerY + dp(100)) * eased;
        }
        drawCreature(canvas, x - dp(14), creatureY);
        drawAdventurer(canvas, x, steveY);
        if (progress >= 0.38f && progress < 0.62f) {
            drawFireworks(canvas, x + dp(8), centerY, progress);
        }
    }

    private void drawAdventurer(Canvas canvas, float x, float y) {
        paint.setColor(Color.rgb(205, 155, 105));
        canvas.drawRect(x, y, x + dp(20), y + dp(20), paint);
        paint.setColor(Color.rgb(54, 117, 198));
        canvas.drawRect(x - dp(2), y + dp(20), x + dp(22), y + dp(48), paint);
        paint.setColor(Color.rgb(35, 39, 52));
        canvas.drawRect(x, y + dp(48), x + dp(8), y + dp(70), paint);
        canvas.drawRect(x + dp(13), y + dp(48), x + dp(21), y + dp(70), paint);
        paint.setColor(Color.LTGRAY);
        canvas.rotate(-35, x + dp(26), y + dp(35));
        canvas.drawRect(x + dp(24), y + dp(4), x + dp(28), y + dp(48), paint);
        canvas.rotate(35, x + dp(26), y + dp(35));
    }

    private void drawCreature(Canvas canvas, float x, float y) {
        paint.setColor(Color.rgb(77, 181, 93));
        canvas.drawRect(x, y, x + dp(25), y + dp(45), paint);
        canvas.drawRect(x - dp(5), y + dp(16), x, y + dp(28), paint);
        canvas.drawRect(x + dp(25), y + dp(16), x + dp(30), y + dp(28), paint);
        paint.setColor(Color.rgb(28, 88, 46));
        canvas.drawRect(x + dp(5), y + dp(9), x + dp(9), y + dp(13), paint);
        canvas.drawRect(x + dp(16), y + dp(9), x + dp(20), y + dp(13), paint);
        canvas.drawRect(x + dp(9), y + dp(25), x + dp(16), y + dp(32), paint);
    }

    private void drawFireworks(Canvas canvas, float x, float y, float value) {
        float burst = value < 0.38f ? 0f : Math.min(1f, (value - 0.38f) / 0.24f);
        paint.setStrokeWidth(dp(3));
        int[] colors = {Color.YELLOW, Color.CYAN, Color.MAGENTA, Color.WHITE, Color.rgb(255, 120, 40)};
        for (int i = 0; i < 12; i++) {
            float alpha = value > 0.52f ? Math.max(0f, 1f - (value - 0.52f) / 0.18f) : 1f;
            paint.setColor(Color.argb((int) (255 * alpha), colors[i % colors.length] >> 16 & 255,
                    colors[i % colors.length] >> 8 & 255, colors[i % colors.length] & 255));
            float angle = i * (float) Math.PI / 6f;
            float radius = dp(8 + Math.round(48 * burst));
            canvas.drawLine(x, y, x + (float) Math.cos(angle) * radius, y + (float) Math.sin(angle) * radius, paint);
        }
    }

    private float dp(int value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
