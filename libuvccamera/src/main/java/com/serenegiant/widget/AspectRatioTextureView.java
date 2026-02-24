/*
 *  UVCCamera
 *  library and sample to access to UVC web camera on non-rooted Android device
 *
 * Copyright (c) 2014-2017 saki t_saki@serenegiant.com
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 *
 *  All files in the folder are under this Apache License, Version 2.0.
 *  Files in the libjpeg-turbo, libusb, libuvc, rapidjson folder
 *  may have a different license, see the respective files.
 */

package com.serenegiant.widget;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.TextureView;

import com.serenegiant.uvccamera.BuildConfig;

/**
 * change the view size with keeping the specified aspect ratio.
 * if you set this view with in a FrameLayout and set property "android:layout_gravity="center",
 * you can show this view in the center of screen and keep the aspect ratio of content
 * XXX it is better that can set the aspect ratio as xml property
 *
 * @author admin
 */
public class AspectRatioTextureView extends TextureView    // API >= 14
        implements IAspectRatioView {

    private static final boolean DEBUG = BuildConfig.DEBUG;    // TODO set false on release
    private static final String TAG = AspectRatioTextureView.class.getSimpleName();
    private static final float MIN_SCALE = 1.0f;
    private static final float MAX_SCALE = 5.0f;

    private double mRequestedAspect = -1.0;
    private CameraViewInterface.Callback mCallback;
    private float mCurrentScale = MIN_SCALE;
    private float mTranslationX = 0f;
    private float mTranslationY = 0f;
    private final ScaleGestureDetector mScaleGestureDetector;
    private final GestureDetector mGestureDetector;

    public AspectRatioTextureView(final Context context) {
        this(context, null, 0);
    }

    public AspectRatioTextureView(final Context context, final AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AspectRatioTextureView(final Context context, final AttributeSet attrs, final int defStyle) {
        super(context, attrs, defStyle);
        setClickable(true);
        setLongClickable(true);
        mScaleGestureDetector = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                final float targetScale = clamp(mCurrentScale * detector.getScaleFactor(), MIN_SCALE, MAX_SCALE);
                final float appliedScale = targetScale / mCurrentScale;
                if (Math.abs(appliedScale - 1.0f) < 1e-4f) {
                    return false;
                }
                setPivotX(detector.getFocusX());
                setPivotY(detector.getFocusY());
                mCurrentScale = targetScale;
                applyTouchTransform();
                return true;
            }
        });
        mGestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                if (mCurrentScale <= MIN_SCALE) {
                    return false;
                }
                mTranslationX -= distanceX;
                mTranslationY -= distanceY;
                applyTouchTransform();
                return true;
            }
        });
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getParent() != null) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        final boolean scaleHandled = mScaleGestureDetector.onTouchEvent(event);
        final boolean gestureHandled = mGestureDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            if (getParent() != null) {
                getParent().requestDisallowInterceptTouchEvent(false);
            }
            if (mCurrentScale <= MIN_SCALE + 1e-4f) {
                resetTouchTransform();
            }
        }
        if (event.getPointerCount() > 1) {
            return true;
        }
        return scaleHandled || gestureHandled || super.onTouchEvent(event);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        resetTouchTransform();
    }

    private void applyTouchTransform() {
        constrainTranslation();
        setScaleX(mCurrentScale);
        setScaleY(mCurrentScale);
        setTranslationX(mTranslationX);
        setTranslationY(mTranslationY);
        invalidate();
    }

    private void resetTouchTransform() {
        mCurrentScale = MIN_SCALE;
        mTranslationX = 0f;
        mTranslationY = 0f;
        setPivotX(getWidth() * 0.5f);
        setPivotY(getHeight() * 0.5f);
        applyTouchTransform();
    }

    private void constrainTranslation() {
        final float width = getWidth();
        final float height = getHeight();
        if (width <= 0f || height <= 0f) {
            return;
        }
        final float maxX = ((mCurrentScale - 1.0f) * width) * 0.5f;
        final float maxY = ((mCurrentScale - 1.0f) * height) * 0.5f;
        if (maxX <= 0f) {
            mTranslationX = 0f;
        } else {
            mTranslationX = clamp(mTranslationX, -maxX, maxX);
        }
        if (maxY <= 0f) {
            mTranslationY = 0f;
        } else {
            mTranslationY = clamp(mTranslationY, -maxY, maxY);
        }
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    @Override
    public void setAspectRatio(final double aspectRatio) {
        if (aspectRatio < 0) {
            throw new IllegalArgumentException();
        }
        // Use a range as a standard for comparing whether floating point numbers are equal
        float diff = 1e-6f;
        if (Math.abs(mRequestedAspect - aspectRatio) > diff) {
            mRequestedAspect = aspectRatio;
            new Handler(Looper.getMainLooper()).post(() -> {
                requestLayout();
            });
        }
    }

    @Override
    public void setAspectRatio(final int width, final int height) {
        setAspectRatio(width / (double) height);
    }

    @Override
    public double getAspectRatio() {
        return mRequestedAspect;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {

        if (mRequestedAspect > 0) {
            int initialWidth = MeasureSpec.getSize(widthMeasureSpec);
            int initialHeight = MeasureSpec.getSize(heightMeasureSpec);

            final int horizPadding = getPaddingLeft() + getPaddingRight();
            final int vertPadding = getPaddingTop() + getPaddingBottom();
            initialWidth -= horizPadding;
            initialHeight -= vertPadding;

            final double viewAspectRatio = (double) initialWidth / initialHeight;
            final double aspectDiff = mRequestedAspect / viewAspectRatio - 1;

            if (Math.abs(aspectDiff) > 0.01) {
                if (aspectDiff > 0) {
                    // width priority decision
                    initialHeight = (int) (initialWidth / mRequestedAspect);
                } else {
                    // height priority decision
                    initialWidth = (int) (initialHeight * mRequestedAspect);
                }
                initialWidth += horizPadding;
                initialHeight += vertPadding;
                widthMeasureSpec = MeasureSpec.makeMeasureSpec(initialWidth, MeasureSpec.EXACTLY);
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(initialHeight, MeasureSpec.EXACTLY);
            }
        }

        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

}
