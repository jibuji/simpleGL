package com.gionee.lab.opengl;


import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Process;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.util.AttributeSet;
import android.util.Log;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.View;

import com.gionee.lab.opengllibdemo.BuildConfig;

import java.util.ArrayDeque;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;
import javax.microedition.khronos.opengles.GL11;

// The root component of all <code>GLView</code>s. The rendering is done in GL
// thread while the event handling is done in the main thread.  To synchronize
// the two threads, the entry points of this package need to synchronize on the
// <code>GLRootView</code> instance unless it can be proved that the rendering
// thread won't access the same thing as the method. The entry points include:
// (1) The public methods of HeadUpDisplay
// (2) The public methods of CameraHeadUpDisplay
// (3) The overridden methods in GLRootView.
public class GLRootView extends GLSurfaceView
        implements GLSurfaceView.Renderer {
    private static final String TAG = "GLRootView";
    private static final boolean DEBUG = BuildConfig.DEBUG;

    private static final boolean DEBUG_FPS = false;
    private static final boolean DEBUG_INVALIDATE = false;
    private static final boolean DEBUG_DRAWING_STAT = false;
    private static final boolean DEBUG_PROFILE = false;
    private static final boolean DEBUG_PROFILE_SLOW_ONLY = false;
    private static final int FLAG_INITIALIZED = 1;
    private static final int FLAG_NEED_LAYOUT = 2;
    private int mFlags = FLAG_NEED_LAYOUT;

    public static interface OnGLIdleListener {
        public boolean onGLIdle(
                GLCanvas canvas, boolean renderRequested);
    }

    private final ArrayDeque<OnGLIdleListener> mIdleListeners =
            new ArrayDeque<OnGLIdleListener>();
    private final IdleRunner mIdleRunner = new IdleRunner();
    private final ReentrantLock mRenderLock = new ReentrantLock();
    private final Condition mFreezeCondition =
            mRenderLock.newCondition();
    private int mFrameCount = 0;
    private long mFrameCountingStart = 0;
    private int mInvalidateColor = 0;   //NOSONAR
    private GL11 mGL;
    private GLCanvas mCanvas;
    private GLView mContentView;

    private volatile boolean mRenderRequested = false;
    private boolean mFreeze;

    private long mLastDrawFinishTime = 0;  //NOSONAR
    private boolean mInDownState = false;
    private Runnable mRequestRenderOnAnimationFrame = new Runnable() {
        @Override
        public void run() {
            superRequestRender();
        }
    };
    private float[] mClearBufferBackground;

    public GLRootView(Context context) {
        this(context, null);
    }

    public GLRootView(Context context, AttributeSet attrs) {
        super(context, attrs);
        mFlags |= FLAG_INITIALIZED;
        setBackground(null);
        setEGLContextClientVersion(2);
        setEGLConfigChooser(8, 8, 8, 8, 24, 0);
        setRenderer(this);
        getHolder().setFormat(PixelFormat.RGB_888);
        mClearBufferBackground = Utils.intColorToFloatARGBArray(0);
        // Uncomment this to enable gl error check.
        // setDebugFlags(DEBUG_CHECK_GL_ERROR);
    }

    public void addOnGLIdleListener(OnGLIdleListener listener) {
        synchronized (mIdleListeners) {
            mIdleListeners.addLast(listener);
            mIdleRunner.enable();
        }
    }

    public void setContentPane(GLView content) {
        if (mContentView == content) return;
        if (mContentView != null) {
            if (mInDownState) {
                long now = SystemClock.uptimeMillis();
                MotionEvent cancelEvent = MotionEvent.obtain(
                        now, now, MotionEvent.ACTION_CANCEL, 0, 0, 0);
                mContentView.dispatchTouchEvent(cancelEvent);
                cancelEvent.recycle();
                mInDownState = false;
            }
            mContentView.detachFromRoot();
            BasicTexture.yieldAllTextures();
        }
        mContentView = content;
        if (content != null) {
            content.attachToRoot(this);
            requestLayoutContentPane();
        }
    }

    public void requestRenderForced() {
        superRequestRender();
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    @Override
    public void requestRender() {
        if (DEBUG_INVALIDATE) {
            StackTraceElement e = Thread.currentThread().getStackTrace()[4];
            String caller = e.getFileName() + ":" + e.getLineNumber() + " ";
            Log.d(TAG, "invalidate: " + caller);
        }
        if (mRenderRequested) return;
        mRenderRequested = true;
        postOnAnimation(mRequestRenderOnAnimationFrame);
    }

    private void superRequestRender() {
        super.requestRender();
    }

    public void requestLayoutContentPane() {
        mRenderLock.lock();
        try {
            if (mContentView == null || (mFlags & FLAG_NEED_LAYOUT) != 0) return;

            // "View" system will invoke onLayout() for initialization(bug ?), we
            // have to ignore it since the GLThread is not ready yet.
            if ((mFlags & FLAG_INITIALIZED) == 0) return;

            mFlags |= FLAG_NEED_LAYOUT;
            requestRender();
        } finally {
            mRenderLock.unlock();
        }
    }

    private void layoutContentPane() {
        mFlags &= ~FLAG_NEED_LAYOUT;

        int w = getWidth();
        int h = getHeight();
        int displayRotation = 0;
        int compensation = 0;
        if (mContentView != null && w != 0 && h != 0) {
            mContentView.layout(0, 0, w, h);
        }
        // Uncomment this to dump the view hierarchy.
        //mContentView.dumpTree("");
    }

    @Override
    protected void onLayout(
            boolean changed, int left, int top, int right, int bottom) {
        if (changed) requestLayoutContentPane();
    }

    public void setClearColor(int color) {
        mClearBufferBackground = Utils.intColorToFloatARGBArray(color);
    }
    /**
     * Called when the context is created, possibly after automatic destruction.
     */
    // This is a GLSurfaceView.Renderer callback
    @Override
    public void onSurfaceCreated(GL10 gl1, EGLConfig config) {
        GL11 gl = (GL11) gl1;
        if (mGL != null) {
            // The GL Object has changed
            if (DEBUG) {
                Log.i(TAG, "GLObject has changed from " + mGL + " to " + gl);
            }
        }
        mRenderLock.lock();
        try {
            mGL = gl;
            mCanvas = new GLES20Canvas();
            BasicTexture.invalidateAllTextures();
        } finally {
            mRenderLock.unlock();
        }

        if (DEBUG_FPS || DEBUG_PROFILE) {
            setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        } else {
            setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        }
    }

    /**
     * Called when the OpenGL surface is recreated without destroying the
     * context.
     */
    // This is a GLSurfaceView.Renderer callback
    @Override
    public void onSurfaceChanged(GL10 gl1, int width, int height) {
        if (DEBUG) {
            Log.i(TAG, "onSurfaceChanged: " + width + "x" + height
                    + ", gl10: " + gl1.toString());
        }
        Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);

        GL11 gl = (GL11) gl1;
        Utils.assertTrue(mGL == gl);

        mCanvas.setSize(width, height);
    }

    private void outputFps() {
        long now = System.nanoTime();
        if (mFrameCountingStart == 0) {
            mFrameCountingStart = now;
        } else if ((now - mFrameCountingStart) > 1000000000) {
            Log.d(TAG, "fps: " + (double) mFrameCount
                    * 1000000000 / (now - mFrameCountingStart));
            mFrameCountingStart = now;
            mFrameCount = 0;
        }
        ++mFrameCount;
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        AnimationTime.update();
        long t0;
        if (DEBUG_PROFILE_SLOW_ONLY) {
            t0 = System.nanoTime();
        }
        mRenderLock.lock();

        try {
            while (mFreeze) {
                mFreezeCondition.awaitUninterruptibly();
            }
            onDrawFrameLocked(gl);
        } finally {
            mRenderLock.unlock();
        }

        if (DEBUG_PROFILE_SLOW_ONLY) {
            long t = System.nanoTime();
            long durationInMs = (t - mLastDrawFinishTime) / 1000000;
            long durationDrawInMs = (t - t0) / 1000000;
            mLastDrawFinishTime = t;

            if (durationInMs > 34) {  // 34ms -> we skipped at least 2 frames
                Log.v(TAG, "----- SLOW (" + durationDrawInMs + "/" +
                        durationInMs + ") -----");
            }
        }
    }

    private void onDrawFrameLocked(GL10 gl) {
        if (DEBUG_FPS) outputFps();

        // release the unbound textures and deleted buffers.
        mCanvas.deleteRecycledResources();

        // reset texture upload limit
        UploadedTexture.resetUploadLimit();

        mRenderRequested = false;

        if ((mFlags & FLAG_NEED_LAYOUT) != 0) {
            layoutContentPane();
        }

        mCanvas.save(GLCanvas.SAVE_FLAG_ALL);
        if (mContentView != null) {
            mCanvas.clearBuffer(mClearBufferBackground);
            mContentView.render(mCanvas);
        } else {
            // Make sure we always draw something to prevent displaying garbage
            mCanvas.clearBuffer();
        }
        mCanvas.restore();

        if (UploadedTexture.uploadLimitReached()) {
            requestRender();
        }

        synchronized (mIdleListeners) {
            if (!mIdleListeners.isEmpty()) mIdleRunner.enable();
        }

        if (DEBUG_INVALIDATE) {
            mCanvas.fillRect(10, 10, 5, 5, mInvalidateColor);
            mInvalidateColor = ~mInvalidateColor;
        }

        if (DEBUG_DRAWING_STAT) {
            mCanvas.dumpStatisticsAndClear();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!isEnabled()) return false;

        int action = event.getAction();
        if (action == MotionEvent.ACTION_CANCEL
                || action == MotionEvent.ACTION_UP) {
            mInDownState = false;
        } else if (!mInDownState && action != MotionEvent.ACTION_DOWN) {
            return false;
        }

        mRenderLock.lock();
        try {
            // If this has been detached from root, we don't need to handle event
            boolean handled = mContentView != null
                    && mContentView.dispatchTouchEvent(event);
            if (action == MotionEvent.ACTION_DOWN && handled) {
                mInDownState = true;
            }
            return handled;
        } finally {
            mRenderLock.unlock();
        }
    }

    public void lockRenderThread() {
        mRenderLock.lock();
    }

    public void unlockRenderThread() {
        mRenderLock.unlock();
    }

    @Override
    public void onPause() {
        unfreeze();
        super.onPause();
        if (DEBUG_PROFILE) {
            Log.d(TAG, "Stop profiling");
        }
    }

    public void freeze() {
        mRenderLock.lock();
        try {
            mFreeze = true;
        } finally {
            mRenderLock.unlock();
        }
    }

    public void unfreeze() {
        mRenderLock.lock();
        try {
            mFreeze = false;
            mFreezeCondition.signalAll();
        } finally {
            mRenderLock.unlock();
        }
    }

    // We need to unfreeze in the following methods and in onPause().
    // These methods will wait on GLThread. If we have freezed the GLRootView,
    // the GLThread will wait on main thread to call unfreeze and cause dead
    // lock.
    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int w, int h) {
        unfreeze();
        super.surfaceChanged(holder, format, w, h);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        unfreeze();
        super.surfaceCreated(holder);
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        unfreeze();
        super.surfaceDestroyed(holder);
    }

    @Override
    protected void onDetachedFromWindow() {
        unfreeze();
        super.onDetachedFromWindow();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            unfreeze();
        } finally {
            super.finalize();
        }
    }

    private class IdleRunner implements Runnable {
        // true if the idle runner is in the queue
        private boolean mActive = false;

        @Override
        public void run() {
            OnGLIdleListener listener;
            synchronized (mIdleListeners) {
                mActive = false;
                if (mIdleListeners.isEmpty()) return;
                listener = mIdleListeners.removeFirst();
            }
            mRenderLock.lock();
            boolean keepInQueue;
            try {
                if (mCanvas == null) {
                    keepInQueue = true;
                } else {
                    keepInQueue = listener.onGLIdle(mCanvas, mRenderRequested);
                }
            } finally {
                mRenderLock.unlock();
            }
            synchronized (mIdleListeners) {
                if (keepInQueue) mIdleListeners.addLast(listener);
                if (!mRenderRequested && !mIdleListeners.isEmpty()) enable();
            }
        }

        public void enable() {
            // Who gets the flag can add it to the queue
            if (mActive) return;
            mActive = true;
            queueEvent(this);
        }
    }
}
