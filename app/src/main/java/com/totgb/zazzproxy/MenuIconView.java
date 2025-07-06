package com.totgb.zazzproxy;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

public class MenuIconView extends View {
    private Paint paint;
    private boolean pressed = false;
    private OnClickListener onClickListener;

    public MenuIconView(Context context) {
        super(context);
        init();
    }
    public MenuIconView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    private void init() {
        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(8f);
        paint.setColor(Color.DKGRAY);
    }
    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        int pad = 16;
        int barHeight = 10;
        int gap = 16;
        int y = pad;
        paint.setColor(pressed ? Color.GRAY : Color.DKGRAY);
        for (int i = 0; i < 3; i++) {
            canvas.drawLine(pad, y, w - pad, y, paint);
            y += barHeight + gap;
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
}
