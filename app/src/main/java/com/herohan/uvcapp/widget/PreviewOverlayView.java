package com.herohan.uvcapp.widget;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

public class PreviewOverlayView extends View {

    private boolean mShowRuleOfThirds;
    private boolean mShowLevel;
    private float mLevelAngle; // radians

    private final Paint mGridPaint;
    private final Paint mLevelPaint;

    public PreviewOverlayView(Context context) {
        this(context, null);
    }

    public PreviewOverlayView(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public PreviewOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        mGridPaint = new Paint();
        mGridPaint.setStyle(Paint.Style.STROKE);
        mGridPaint.setColor(Color.WHITE);
        mGridPaint.setStrokeWidth(2f);
        mGridPaint.setAlpha(150);

        mLevelPaint = new Paint();
        mLevelPaint.setStyle(Paint.Style.STROKE);
        mLevelPaint.setColor(Color.GREEN);
        mLevelPaint.setStrokeWidth(4f);
        mLevelPaint.setAlpha(190);

        setWillNotDraw(false);
    }

    public void setShowRuleOfThirds(boolean visible) {
        mShowRuleOfThirds = visible;
        setVisibility((visible || mShowLevel) ? VISIBLE : GONE);
        invalidate();
    }

    public void setShowLevel(boolean visible) {
        mShowLevel = visible;
        setVisibility((visible || mShowRuleOfThirds) ? VISIBLE : GONE);
        invalidate();
    }

    public void setLevelAngle(float radians) {
        if (Float.isNaN(radians) || Float.isInfinite(radians)) {
            return;
        }
        mLevelAngle = radians;
        if (mShowLevel) {
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!mShowRuleOfThirds && !mShowLevel) {
            return;
        }

        final int w = getWidth();
        final int h = getHeight();

        if (mShowRuleOfThirds) {
            float oneThirdX = w / 3f;
            float twoThirdX = 2f * w / 3f;
            float oneThirdY = h / 3f;
            float twoThirdY = 2f * h / 3f;
            canvas.drawLine(oneThirdX, 0, oneThirdX, h, mGridPaint);
            canvas.drawLine(twoThirdX, 0, twoThirdX, h, mGridPaint);
            canvas.drawLine(0, oneThirdY, w, oneThirdY, mGridPaint);
            canvas.drawLine(0, twoThirdY, w, twoThirdY, mGridPaint);
        }

        if (mShowLevel) {
            float cx = w / 2f;
            float cy = h / 2f;
            float half = Math.min(w, h) / 3f;

            float dx = (float) Math.cos(mLevelAngle) * half;
            float dy = (float) Math.sin(mLevelAngle) * half;

            canvas.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, mLevelPaint);
            canvas.drawCircle(cx, cy, 10f, mLevelPaint);
        }
    }
}
