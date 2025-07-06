package com.totgb.zazzproxy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

public class ThemeToggleView extends View {
    private Paint paint;
    private boolean pressed = false;
    private boolean isDark = false;
    private OnClickListener onClickListener;

    public ThemeToggleView(Context context) {
        super(context);
        init();
    }
    public ThemeToggleView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    private void init() {
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.FILL);
    }
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        int pad = 20;
        // Draw sun or moon icon
        if (!isDark) {
            paint.setColor(pressed ? Color.YELLOW : Color.parseColor("#FFEB3B"));
            canvas.drawCircle(w/2f, h/2f, w/2f - pad, paint);
            paint.setColor(Color.parseColor("#FFFDE7"));
            canvas.drawCircle(w/2f, h/2f, w/4f, paint);
        } else {
            paint.setColor(pressed ? Color.LTGRAY : Color.DKGRAY);
            canvas.drawCircle(w/2f, h/2f, w/2f - pad, paint);
            paint.setColor(Color.BLACK);
            canvas.drawCircle(w/2f + w/6f, h/2f - w/6f, w/4f, paint);
        }
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
    public void setDark(boolean dark) {
        isDark = dark;
        invalidate();
    }
    public boolean isDark() {
        return isDark;
    }
}
