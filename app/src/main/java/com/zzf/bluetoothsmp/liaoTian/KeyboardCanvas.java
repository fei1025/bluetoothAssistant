package com.zzf.bluetoothsmp.liaoTian;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

import com.zzf.bluetoothsmp.KeyboardLayoutState;

/** Horizontal positions follow width; vertical positions use dp inside a ScrollView. */
public class KeyboardCanvas extends ViewGroup {
    private KeyboardLayoutState state;
    private final float density;
    private final int inset;
    private final int buttonHeight;

    public KeyboardCanvas(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        inset = Math.round(8 * density);
        buttonHeight = Math.round(96 * density);
    }

    public void setState(KeyboardLayoutState state) {
        this.state = state;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int buttonWidth = Math.max(1, Math.min(Math.round(160 * density), (width - inset * 4) / 3));
        int height = Math.round(320 * density);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            child.measure(MeasureSpec.makeMeasureSpec(buttonWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(buttonHeight, MeasureSpec.EXACTLY));
            height = Math.max(height, Math.round(position(child).yDp * density) + buttonHeight + inset * 2);
        }
        setMeasuredDimension(resolveSize(width, widthMeasureSpec), resolveSize(height, heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            KeyboardLayoutState.Position position = position(child);
            int x = inset + Math.round(position.xFraction * horizontalRange(child));
            int y = inset + Math.round(position.yDp * density);
            child.setTranslationX(0);
            child.setTranslationY(0);
            child.layout(x, y, x + child.getMeasuredWidth(), y + child.getMeasuredHeight());
        }
    }

    private KeyboardLayoutState.Position position(View child) {
        KeyboardLayoutState.Position position = state == null ? null : state.buttons().get((Integer) child.getTag());
        return position == null ? new KeyboardLayoutState.Position(0, 0) : position;
    }

    private int horizontalRange(View child) {
        return Math.max(0, getMeasuredWidth() - inset * 2 - child.getMeasuredWidth());
    }

    public void drag(View child, float x, float y) {
        child.setX(Math.max(inset, Math.min(inset + horizontalRange(child), x)));
        child.setY(Math.max(inset, Math.min(getHeight() - inset - child.getHeight(), y)));
    }

    public KeyboardLayoutState.Position capture(View child) {
        int range = horizontalRange(child);
        return new KeyboardLayoutState.Position(range == 0 ? 0 : (child.getX() - inset) / range,
                (child.getY() - inset) / density);
    }
}
