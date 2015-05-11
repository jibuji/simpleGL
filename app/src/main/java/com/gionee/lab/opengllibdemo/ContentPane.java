package com.gionee.lab.opengllibdemo;

import android.content.Context;
import android.graphics.Rect;
import android.util.Log;

import com.gionee.lab.opengl.AnimationTime;
import com.gionee.lab.opengl.FloatAnimation;
import com.gionee.lab.opengl.GLCanvas;
import com.gionee.lab.opengl.GLView;
import com.gionee.lab.opengl.ResourceTexture;

/**
 * Created by jiengfei on 15-5-9.
 */
public class ContentPane extends GLView {
    private static final String TAG = "T";
    ResourceTexture mTex = null;
    int mStartX = 0;
    int mEndX = 0;
    int mTargetY = 0;
    FloatAnimation mAnim = null;
    public ContentPane(Context context) {
        mTex = new ResourceTexture(context, R.drawable.weather_fs_cloud);
        mTex.setOpaque(false);
    }

    @Override
    protected void render(GLCanvas canvas) {
        long time = System.currentTimeMillis();
        int width = mTex.getWidth();
        int height = mTex.getHeight();
        if (mEndX == 0) {
            Rect bounds = mBounds;
            mStartX = 0;
            mEndX = -(width - bounds.width());
            mTargetY = (bounds.height() - height) / 2;
            mAnim = new FloatAnimation(mStartX, mEndX, 5000);
            mAnim.start();
        }
        boolean animating = mAnim.calculate(AnimationTime.get());
        if (!animating) {
            mAnim.start();
        }
        canvas.drawTexture(mTex, (int)mAnim.get(), mTargetY, width, height);
        long end = System.currentTimeMillis();
        Log.d(TAG, "span = "+(end  - time));
        getGLRoot().requestRender();
    }

    @Override
    protected void onLayout(boolean changeSize, int left, int top, int right, int bottom) {
        Log.d(TAG, "onLayout changeSize=" + changeSize + ";left=" + left + ";top=" + top + ";right=" + right + ";" +
                "bottom=" +bottom);
    }
}
