package com.concreterose.tfclassifywhaaat;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.view.View;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by darrell on 2/3/18.
 */

public class MyLabelRectsView extends View {
    private final static String TAG = MyLabelRectsView.class.getSimpleName();

    private final static int MAX_ITEMS = 10;
    private final static int TEXT_SIZE = 24;

    private final Paint mPaint = new TextPaint();

    private static class LabeledRect {
        RectF mRectF = new RectF();
        String mLabel = null;
    }

    private List<LabeledRect> mActive = new ArrayList<>(MAX_ITEMS);
    private List<LabeledRect> mFreePool = new ArrayList<>(MAX_ITEMS);

    public MyLabelRectsView(Context pContext) {
        super(pContext);
    }

    public MyLabelRectsView(Context pContext, AttributeSet pAttrs) {
        super(pContext, pAttrs);
    }

    public void clear() {
        mFreePool.addAll(mActive);
        mActive.clear();

        while (mFreePool.size() > MAX_ITEMS) {
            mFreePool.remove(mFreePool.size() - 1);
        }
    }

    public void add(String pLabel, RectF pRectF) {
        final LabeledRect lr;
        if (!mFreePool.isEmpty()) {
            lr = mFreePool.remove(mFreePool.size() - 1);
        } else {
            lr = new LabeledRect();
        }

        lr.mLabel = pLabel;
        lr.mRectF.set(pRectF);
        mActive.add(lr);
    }

    @Override
    protected void onDraw(Canvas pCanvas) {
        super.onDraw(pCanvas);

        // Draw rects.
        mPaint.setColor(Color.RED);
        mPaint.setAlpha(64);
        for (int i = 0; i < mActive.size(); i++) {
            final LabeledRect lr = mActive.get(i);
            pCanvas.drawRect(lr.mRectF, mPaint);
        }

        // Draw labels.
        mPaint.setColor(Color.BLACK);
        mPaint.setTextSize(TEXT_SIZE);
        for (int i = 0; i < mActive.size(); i++) {
            final LabeledRect lr = mActive.get(i);
            final float x = lr.mRectF.left;
            float y = lr.mRectF.top;

            if (lr.mRectF.height() == 0) {
                y = i * TEXT_SIZE;
            }

            pCanvas.drawText(lr.mLabel, x, y, mPaint);
        }
    }
}
