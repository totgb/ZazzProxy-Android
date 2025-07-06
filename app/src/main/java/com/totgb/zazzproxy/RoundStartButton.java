package com.totgb.zazzproxy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

public class RoundStartButton extends View {
    private Paint paint;
    private Paint textPaint;
    private boolean pressed = false;
    private OnClickListener onClickListener;

    public RoundStartButton(Context context) {
        super(context);
        init();
    }
    public RoundStartButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    private void init() {
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.parseColor("#2196F3")); // Default blue
        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(64f);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        float radius = Math.min(w, h) / 2f - 10;
        float cx = w / 2f;
        float cy = h / 2f;
        paint.setColor(pressed ? Color.parseColor("#1976D2") : Color.parseColor("#2196F3"));
        canvas.drawCircle(cx, cy, radius, paint);
        canvas.drawText("START", cx, cy + 24, textPaint);
    }
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                pressed = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_UP:
                pressed = false;
                invalidate();
                if (onClickListener != null) onClickListener.onClick(this);
                return true;
            case MotionEvent.ACTION_CANCEL:
                pressed = false;
                invalidate();
                return true;
        }
        return super.onTouchEvent(event);
    }
    public void setOnClickListener(OnClickListener l) {
        this.onClickListener = l;
    }
}
