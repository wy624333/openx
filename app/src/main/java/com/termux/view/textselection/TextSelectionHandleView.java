package com.termux.view.textselection;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupWindow;

import com.termux.view.TerminalView;

@SuppressLint("ViewConstructor")
public class TextSelectionHandleView extends View {
    private final TerminalView terminalView;
    private PopupWindow mHandle;
    private final CursorController mCursorController;

    private final Drawable mHandleLeftDrawable;
    private final Drawable mHandleRightDrawable;
    private Drawable mHandleDrawable;

    private boolean mIsDragging;

    final int[] mTempCoords = new int[2];

    private int mPointX;
    private int mPointY;
    private float mTouchToWindowOffsetX;
    private float mTouchToWindowOffsetY;
    private float mHotspotX;
    private float mHotspotY;
    private float mTouchOffsetY;
    private int mLastParentX;
    private int mLastParentY;

    private int mHandleHeight;
    private int mHandleWidth;

    private final int mInitialOrientation;
    private int mOrientation;

    public static final int LEFT = 0;
    public static final int RIGHT = 2;

    private long mLastTime;

    private static int sLeftHandleResId;
    private static int sRightHandleResId;

    public TextSelectionHandleView(TerminalView terminalView, CursorController cursorController, int initialOrientation) {
        super(terminalView.getContext());
        this.terminalView = terminalView;
        mCursorController = cursorController;
        mInitialOrientation = initialOrientation;

        Drawable left = loadHandleDrawable(LEFT);
        Drawable right = loadHandleDrawable(RIGHT);
        mHandleLeftDrawable = left != null ? left : createFallbackDrawable(LEFT);
        mHandleRightDrawable = right != null ? right : createFallbackDrawable(RIGHT);

        setOrientation(mInitialOrientation);
    }

    private Drawable loadHandleDrawable(int orientation) {
        if (sLeftHandleResId == 0) {
            String pkg = getContext().getPackageName();
            sLeftHandleResId = getResources().getIdentifier(
                "text_select_handle_left_material", "drawable", pkg);
            sRightHandleResId = getResources().getIdentifier(
                "text_select_handle_right_material", "drawable", pkg);
        }
        int resId = orientation == LEFT ? sLeftHandleResId : sRightHandleResId;
        if (resId == 0) return null;
        try {
            return getContext().getDrawable(resId);
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    private static Drawable createFallbackDrawable(int orientation) {
        final int size = 48;
        android.graphics.Bitmap bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);
        android.graphics.Paint paint = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        paint.setColor(0xFF2196F3);
        paint.setStyle(android.graphics.Paint.Style.FILL);
        android.graphics.Path path = new android.graphics.Path();
        if (orientation == LEFT) {
            path.moveTo(size, 0); path.lineTo(size, size); path.lineTo(0, size / 2); path.close();
        } else {
            path.moveTo(0, 0); path.lineTo(0, size); path.lineTo(size, size / 2); path.close();
        }
        c.drawPath(path, paint);
        return new android.graphics.drawable.BitmapDrawable(bmp);
    }

    private void initHandle() {
        mHandle = new PopupWindow(terminalView.getContext(), null,
            android.R.attr.textSelectHandleWindowStyle);
        mHandle.setSplitTouchEnabled(true);
        mHandle.setClippingEnabled(false);
        mHandle.setWidth(ViewGroup.LayoutParams.WRAP_CONTENT);
        mHandle.setHeight(ViewGroup.LayoutParams.WRAP_CONTENT);
        mHandle.setBackgroundDrawable(null);
        mHandle.setAnimationStyle(0);

        mHandle.setContentView(this);
    }

    public void setOrientation(int orientation) {
        mOrientation = orientation;
        int handleWidth = 0;
        switch (orientation) {
            case LEFT: {
                mHandleDrawable = mHandleLeftDrawable;
                handleWidth = mHandleDrawable.getIntrinsicWidth();
                mHotspotX = (handleWidth * 3) / (float) 4;
                break;
            }

            case RIGHT: {
                mHandleDrawable = mHandleRightDrawable;
                handleWidth = mHandleDrawable.getIntrinsicWidth();
                mHotspotX = handleWidth / (float) 4;
                break;
            }
        }

        mHandleHeight = mHandleDrawable.getIntrinsicHeight();

        mHandleWidth = handleWidth;
        mTouchOffsetY = -mHandleHeight * 0.3f;
        mHotspotY = 0;
        invalidate();
    }

    public void show() {
        if (!isPositionVisible()) {
            hide();
            return;
        }

        removeFromParent();

        initHandle();
        invalidate();

        final int[] coords = mTempCoords;
        terminalView.getLocationInWindow(coords);
        coords[0] += mPointX;
        coords[1] += mPointY;

        if (mHandle != null)
            mHandle.showAtLocation(terminalView, 0, coords[0], coords[1]);
    }

    public void hide() {
        mIsDragging = false;

        if (mHandle != null) {
            mHandle.dismiss();

            removeFromParent();
            mHandle = null;
        }
        invalidate();
    }

    public void removeFromParent() {
        if (!isParentNull()) {
            ((ViewGroup)this.getParent()).removeView(this);
        }
    }

    public void positionAtCursor(final int cx, final int cy, boolean forceOrientationCheck) {
        int x = terminalView.getPointX(cx);
        int y = terminalView.getPointY(cy + 1);
        moveTo(x, y, forceOrientationCheck);
    }

    private void moveTo(int x, int y, boolean forceOrientationCheck) {
        float oldHotspotX = mHotspotX;
        checkChangedOrientation(x, forceOrientationCheck);
        mPointX = (int) (x - (isShowing() ? oldHotspotX : mHotspotX));
        mPointY = y;

        if (isPositionVisible()) {
            int[] coords = null;

            if (isShowing()) {
                coords = mTempCoords;
                terminalView.getLocationInWindow(coords);
                int x1 = coords[0] + mPointX;
                int y1 = coords[1] + mPointY;
                if (mHandle != null)
                    mHandle.update(x1, y1, getWidth(), getHeight());
            } else {
                show();
            }

            if (mIsDragging) {
                if (coords == null) {
                    coords = mTempCoords;
                    terminalView.getLocationInWindow(coords);
                }
                if (coords[0] != mLastParentX || coords[1] != mLastParentY) {
                    mTouchToWindowOffsetX += coords[0] - mLastParentX;
                    mTouchToWindowOffsetY += coords[1] - mLastParentY;
                    mLastParentX = coords[0];
                    mLastParentY = coords[1];
                }
            }
        } else {
            hide();
        }
    }

    public void changeOrientation(int orientation) {
        if (mOrientation != orientation) {
            setOrientation(orientation);
        }
    }

    private void checkChangedOrientation(int posX, boolean force) {
        if (!mIsDragging && !force) return;
        long millis = SystemClock.currentThreadTimeMillis();
        if (millis - mLastTime < 50 && !force) return;
        mLastTime = millis;

        final TerminalView hostView = terminalView;
        final int w = hostView.getWidth();
        final int padL = hostView.getPaddingLeft();
        final int padR = hostView.getPaddingRight();
        final int clipLeft = padL;
        final int clipRight = w - padR;

        if (posX - mHandleWidth < clipLeft)
            changeOrientation(RIGHT);
        else if (posX + mHandleWidth > clipRight)
            changeOrientation(LEFT);
        else
            changeOrientation(mInitialOrientation);
    }

    private boolean isPositionVisible() {
        if (mIsDragging) return true;
        final TerminalView hostView = terminalView;
        if (hostView.getWidth() <= 0 || hostView.getHeight() <= 0) return false;
        if (!hostView.isShown()) return false;
        return true;
    }

    @Override
    public void onDraw(Canvas c) {
        final int width = mHandleDrawable.getIntrinsicWidth();
        int height = mHandleDrawable.getIntrinsicHeight();
        mHandleDrawable.setBounds(0, 0, width, height);
        mHandleDrawable.draw(c);
    }

    @SuppressLint("ClickableViewAccessibility")
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        terminalView.updateFloatingToolbarVisibility(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                final float rawX = event.getRawX();
                final float rawY = event.getRawY();
                mTouchToWindowOffsetX = rawX - mPointX;
                mTouchToWindowOffsetY = rawY - mPointY;
                final int[] coords = mTempCoords;
                terminalView.getLocationInWindow(coords);
                mLastParentX = coords[0];
                mLastParentY = coords[1];
                mIsDragging = true;
                break;
            }

            case MotionEvent.ACTION_MOVE: {
                final float rawX = event.getRawX();
                final float rawY = event.getRawY();

                final float newPosX = rawX - mTouchToWindowOffsetX + mHotspotX;
                final float newPosY = rawY - mTouchToWindowOffsetY + mHotspotY + mTouchOffsetY;

                mCursorController.updatePosition(this, Math.round(newPosX), Math.round(newPosY));
                break;
            }

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mIsDragging = false;
        }
        return true;
    }

    @Override
    public void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(mHandleDrawable.getIntrinsicWidth(),
            mHandleDrawable.getIntrinsicHeight());
    }

    public int getHandleHeight() {
        return mHandleHeight;
    }

    public int getHandleWidth() {
        return mHandleWidth;
    }

    public boolean isShowing() {
        if (mHandle != null)
            return mHandle.isShowing();
        else
            return false;
    }

    public boolean isParentNull() {
        return this.getParent() == null;
    }

    public boolean isDragging() {
        return mIsDragging;
    }
}
