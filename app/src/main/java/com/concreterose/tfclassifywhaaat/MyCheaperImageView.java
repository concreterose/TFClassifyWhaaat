package com.concreterose.tfclassifywhaaat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Created by darrell on 2/3/18.
 */

/**
 * ImageView.setImageBitmap allocates memory.  With previews happening
 * nearly every frame, that adds up quickly causing memory pressure and
 * frequent garbage collection.  Create a custom "image view" that does
 * not allocate memory.
 */
public class MyCheaperImageView extends View {
    private final Rect mSrcRect = new Rect();
    private final RectF mDstRectF = new RectF();
    private final Paint mPaint = new Paint();
    private Bitmap mBitmap = null;

    public MyCheaperImageView(Context pContext) {
        super(pContext);
    }

    public MyCheaperImageView(Context pContext, AttributeSet pAttrs) {
        super(pContext, pAttrs);
    }

    public void setBitmap(Bitmap pBitmap) {
        mBitmap = pBitmap;
        postInvalidate();  // redraw with the new bitmap
    }

    @Override
    protected void onDraw(Canvas pCanvas) {
        super.onDraw(pCanvas);

        if (mBitmap == null) {
            pCanvas.drawColor(Color.BLACK);
        } else {
            mSrcRect.set(0, 0, mBitmap.getWidth(), mBitmap.getHeight());
            mDstRectF.set(0, 0, getWidth(), getHeight());
            fixAspectRatio(mSrcRect, mDstRectF, false);  // stretch preview
            pCanvas.drawBitmap(mBitmap, mSrcRect, mDstRectF, mPaint);
        }
    }

    /**
     * Resize rectangle to match the desired aspect ratio.
     *
     * @param pSrc (Rect) Image size, may be changed by this method.
     * @param pDst (RectF) Destination size, may be changed by this method.
     * @param pLetterbox (boolean) If true, shrink src to fit into dst.
     */
    @SuppressWarnings("SameParameterValue")
    public static void fixAspectRatio(Rect pSrc, RectF pDst, boolean pLetterbox) {
        final float srcAspect = ((float) pSrc.width()) / pSrc.height();
        final float dstAspect = pDst.width() / pDst.height();

        if (pLetterbox) {
            // Shrink to fit larger dimension with "black bars".
            if (srcAspect <= dstAspect) {
                final float w = pDst.height() * srcAspect;
                final float dw = (pDst.width() - w) / 2f;
                pDst.left += dw;
                pDst.right -= dw;
            } else {
                final float h = pDst.width() / srcAspect;
                final float dh = (pDst.height() - h) / 2f;
                pDst.top += dh;
                pDst.bottom -= dh;
            }
        } else {
            // Zoom to fit smaller dimension, crop off extra.
            if (srcAspect <= dstAspect) {
                final float h = pSrc.width() / dstAspect;
                final float dh = (pSrc.height() - h) / 2f;
                pSrc.top += dh;
                pSrc.bottom -= dh;
            } else {
                final float w = pSrc.height() * dstAspect;
                final float dw = (pSrc.width() - w) / 2f;
                pSrc.left += dw;
                pSrc.right -= dw;
            }
        }
    }
}
