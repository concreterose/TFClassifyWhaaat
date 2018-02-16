package com.concreterose.lib;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.os.Handler;
import android.os.Handler.Callback;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Get preview and picture images.
 *
 * Requires
 * <uses-permission android:name="android.permission.CAMERA" />
 * <uses-feature android:name="android.hardware.camera" />
 *
 * Optional
 * <uses-feature android:name="android.hardware.camera.autofocus" />
 *
 * Owner is responsible for calling start in onResume and stop on onPause.
 */
public final class CameraLib implements
        Camera.PreviewCallback,
        Camera.PictureCallback,
        Camera.AutoFocusCallback,
        View.OnTouchListener,
        Callback {
    private final static String TAG = CameraLib.class.getSimpleName();

    // Message types for the handler threads.
    private final static int WHAT_PROCESS_PREVIEW = 1;
    private final static int WHAT_CALLBACK_PREVIEW = 2;
    private final static int WHAT_PROCESS_PICTURE = 3;
    private final static int WHAT_CALLBACK_PICTURE = 4;

    private final static int NUM_PREVIEW_BUFFERS = 3;

    private final static int TARGET_PICTURE_AREA = 1166400;

    // ------------------------------------------------------------------------

    @SuppressWarnings({"EmptyMethod", "UnusedParameters"})
    public interface CameraLibListener {

        /**
         * Handle a camera preview image.  A timestamp is included in case the
         * frames are being handed to a timestamp-aware video encoder.
         *
         * @param pBitmap (Bitmap) Preview image.
         * @param pTimestampMsecs (long) Milliseconds since start.
         */
        void onPreview(Bitmap pBitmap, long pTimestampMsecs);

        /**
         * Handle a camera picture.  Pictures are much larger than previews.
         *
         * @param pBitmap (Bitmap) Picture image.
         */
        void onPicture(Bitmap pBitmap);
    }

    // ------------------------------------------------------------------------

    @SuppressWarnings("WeakerAccess")
    public final static int FLASH_MODE_OFF = 1;
    @SuppressWarnings("WeakerAccess")
    public final static int FLASH_MODE_ON = 2;
    @SuppressWarnings("WeakerAccess")
    public final static int FLASH_MODE_AUTO = 3;

    private static int translateFlashMode(String pMode) {
        if (Camera.Parameters.FLASH_MODE_OFF.equals(pMode)) {
            return FLASH_MODE_OFF;
        } else if (Camera.Parameters.FLASH_MODE_ON.equals(pMode)) {
            return FLASH_MODE_ON;
        } else if (Camera.Parameters.FLASH_MODE_AUTO.equals(pMode)) {
            return FLASH_MODE_AUTO;
        }
        return 0;
    }

    // ------------------------------------------------------------------------

    /**
     * Create a CameraLib.
     */
    public final static class Builder {
        private final Activity mActivity;
        private final BitmapFactory.Options mBitmapOptions = new BitmapFactory.Options();
        private CameraLibListener mListener;
        private View mPreviewView = null;

        public Builder(Activity pActivity) {
            mActivity = pActivity;
        }

        /**
         * Override the default bitmap config, for instance if being processed
         * by a library requiring a particular format such as face recognizer.
         *
         * @param pConfig (Bitmap.Config) Image format.
         * @return (Builder) Self, for chaining.
         */
        @SuppressWarnings("unused")
        public Builder setConfig(Bitmap.Config pConfig) {
            mBitmapOptions.inPreferredConfig = pConfig;
            return this;
        }

        /**
         * Attach the listener.
         *
         * @param pListener (CameraLibListener) Listener.
         * @return (Builder) Self, for chaining.
         */
        public Builder setListener(CameraLibListener pListener) {
            mListener = pListener;
            return this;
        }

        /**
         * Sets the onTouch listener on this view to auto-focus the preview.
         *
         * @param pPreviewView (View) Preview image view.
         * @return (Builder) Self, for chaining.
         */
        @SuppressWarnings("unused")
        public Builder setPreviewView(View pPreviewView) {
            mPreviewView = pPreviewView;
            return this;
        }

        /**
         * Build the CameraLib with the current build options.
         *
         * @return (CameraLib) CameraLib.
         */
        public CameraLib build() {
            return new CameraLib(this);
        }
    }

    // ------------------------------------------------------------------------

    private final Activity mActivity;
    private final BitmapFactory.Options mBitmapOptions;
    private final CameraLibListener mListener;

    private final Rect mPreviewRect = new Rect();
    private final Rect mPictureRect = new Rect();

    // Do preview processing and listener callbacks on separate threads (multi-core CPU).
    private HandlerThread mProcessingThread;
    private HandlerThread mCallbackThread;
    private Handler mProcessingHandler;
    private Handler mCallbackHandler;

    private final SurfaceTexture mSurfaceTexture;

    private int mCameraId;
    private Camera mCamera = null;
    private Camera.Parameters mCameraParameters = null;
    private boolean mCameraCanAutoFocus = false;
    private boolean mCameraCanZoom = false;
    private int mCameraMaxZoom = 0;

    private final List<byte[]> mPreviewBuffers = new ArrayList<>();
    private final List<Bitmap> mPreviewBitmaps = new ArrayList<>();
    private final List<Long> mPreviewTimestamps = new ArrayList<>();
    private Bitmap mTempBitmap;

    private int mPreviewJpegBufferSize = 0;
    private ByteArrayOutputStream mPreviewJpegOutputStream;

    private final Matrix mMatrix = new Matrix();
    private final Canvas mCanvas = new Canvas();
    private final Paint mPaint = new Paint();

    private long mStartTimestamp = 0L;
    private float mTouchDistance = 0f;

    private boolean mSuppressPreview = true;

    private boolean mSafeToTakePicture = false;

    private CameraLib(Builder pBuilder) {
        mActivity = pBuilder.mActivity;
        mBitmapOptions = pBuilder.mBitmapOptions;
        mListener = pBuilder.mListener;

        mBitmapOptions.inMutable = true;
        mBitmapOptions.inTempStorage = new byte[1024 * 32];  // docs suggest 16 KB

        mSurfaceTexture = new SurfaceTexture(99);  // bogus texture, needed to get preview

        // Remember preferred camera, try to use front-facing if not already set.
        mCameraId = mActivity.getSharedPreferences(TAG, Context.MODE_PRIVATE).getInt("mCameraId", -1);
        if (mCameraId == -1) {
            final android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                android.hardware.Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
                    mCameraId = i;
                    break;
                }
            }
            if (mCameraId == -1) {
                mCameraId = 0;
            }
        }

        if (pBuilder.mPreviewView != null) {
            pBuilder.mPreviewView.setOnTouchListener(this);
        }
    }

    /**
     * Start the camera and receiving preview frames.  Must do this prior to takePicture.
     */
    public void start() {
        Log.d(TAG, "start");

        if (mCamera == null) {
            Log.d(TAG, "start: creating camera");
            openCamera();  // allocates preview buffers
        }

        synchronized (this) {
            mStartTimestamp = SystemClock.elapsedRealtime();
            mSuppressPreview = false;
        }

        mCamera.setPreviewCallbackWithBuffer(this);
        mCamera.startPreview();
        mSafeToTakePicture = true;

        if (mCameraCanAutoFocus) {
            try {
                mCamera.autoFocus(this);
            } catch (RuntimeException e) {
                Log.w(TAG, "start: " + e.toString(), e);
                // ignore
            }
        }

        synchronized (this) {
            mProcessingThread = new HandlerThread(TAG + ".processing");
            mProcessingThread.setPriority(Thread.MIN_PRIORITY);
            mProcessingThread.start();
            mProcessingHandler = new Handler(mProcessingThread.getLooper(), this);
        }

        synchronized (this) {
            mCallbackThread = new HandlerThread(TAG + ".callback");
            mCallbackThread.setPriority(Thread.MIN_PRIORITY);
            mCallbackThread.start();
            mCallbackHandler = new Handler(mCallbackThread.getLooper(), this);
        }
    }

    /**
     * Stop the camera.
     */
    public void stop() {
        Log.d(TAG, "stop");

        // Stop processing previews before destroying things.
        synchronized (this) {
            mSuppressPreview = true;
        }

        if (mCamera != null) {
            try {
                mCamera.stopPreview();
            } catch (RuntimeException e) {
                Log.w(TAG, "stop: " + e.toString(), e);
                // ignore
            }
            mCamera.release();
            mCamera = null;
        }
        mSafeToTakePicture = false;

        mPreviewBuffers.clear();
        mPreviewBitmaps.clear();
        mTempBitmap = null;

        synchronized (this) {
            if (mProcessingHandler != null) {
                mProcessingHandler.removeMessages(WHAT_PROCESS_PREVIEW);
                mProcessingHandler.removeMessages(WHAT_PROCESS_PICTURE);
                mProcessingThread.quitSafely();
                mProcessingHandler = null;
                mProcessingThread = null;
            }
        }

        synchronized (this) {
            if (mCallbackHandler != null) {
                mCallbackHandler.removeMessages(WHAT_CALLBACK_PREVIEW);
                mCallbackHandler.removeMessages(WHAT_CALLBACK_PICTURE);
                mCallbackThread.quitSafely();
                mCallbackHandler = null;
                mCallbackThread = null;
            }
        }
    }

    /**
     * Able to change cameras?
     *
     * @return (boolean) True if can change cameras.
     */
    @SuppressWarnings("unused")
    public boolean canChangeCamera() {
        return Camera.getNumberOfCameras() > 1;
    }

    /**
     * Switch to a different camera.
     */
    @SuppressWarnings("unused")
    public void changeCamera() {
        Log.d(TAG, "changeCamera");
        mCameraId = (mCameraId + 1) % Camera.getNumberOfCameras();

        stop();
        start();  // start with new camera id

        // Remember camera choice.
        mActivity.getSharedPreferences(TAG, Context.MODE_PRIVATE).edit().putInt("mCameraId", mCameraId).apply();
    }

    @SuppressWarnings("unused")
    public boolean getIsFrontFacingCamera() {
        if (mCamera == null) {
            return false;
        }
        final android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(mCameraId, info);
        return info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT;
    }

    @SuppressWarnings("WeakerAccess")
    public List<Integer> getSupportedFlashModes() {
        final List<Integer> result = new ArrayList<>();
        if (mCamera != null && mCameraParameters != null && mCameraParameters.getSupportedFlashModes() != null) {
            for (String mode : mCameraParameters.getSupportedFlashModes()) {
                final int i = translateFlashMode(mode);
                if (i != 0) {
                    result.add(i);
                }
            }
        }
        return Collections.unmodifiableList(result);
    }

    @SuppressWarnings("WeakerAccess")
    public int getFlashMode() {
        Log.d(TAG, "getFlashMode");
        if (mCamera == null || mCameraParameters == null) {
            return FLASH_MODE_OFF;
        }

        return translateFlashMode(mCameraParameters.getFlashMode());
    }

    @SuppressWarnings({"UnusedReturnValue", "WeakerAccess"})
    public boolean setFlashMode(int pMode) {
        Log.d(TAG, "setFlashMode: " + pMode);
        if (mCamera == null || mCameraParameters == null) {
            return false;
        }

        switch (pMode) {
            case FLASH_MODE_OFF:
                mCameraParameters.setFlashMode(Camera.Parameters.FLASH_MODE_OFF);
                break;
            case FLASH_MODE_ON:
                mCameraParameters.setFlashMode(Camera.Parameters.FLASH_MODE_ON);
                break;
            case FLASH_MODE_AUTO:
                mCameraParameters.setFlashMode(Camera.Parameters.FLASH_MODE_AUTO);
                break;
            default:
                throw new IllegalArgumentException("mode " + pMode);
        }
        mCamera.setParameters(mCameraParameters);
        return true;
    }

    @SuppressWarnings({"UnusedReturnValue", "unused"})
    public boolean changeFlashMode() {
        Log.d(TAG, "toggleFlashMode");
        if (mCamera == null) {
            return false;
        }

        // Get current mode in the available list.
        final List<Integer> modes = getSupportedFlashModes();
        int mode = getFlashMode();
        int i = modes.indexOf(mode);
        if (i == -1 || modes.isEmpty()) {
            return false;
        }

        // Choose the next.
        i = (i + 1) % modes.size();
        mode = modes.get(i);

        // Apply.
        setFlashMode(mode);

        return true;
    }

    public Rect getPreviewSize() {
        return new Rect(mPreviewRect);
    }

    /**
     * Take a picture, invokes listener callback.
     *
     * NOTE: This stops the preview images, caller must stop/start the camera to resume them.
     */
    @SuppressWarnings({"UnusedReturnValue", "unused"})
    public boolean takePicture() {
        Log.d(TAG, "takePicture");
        if (mCamera == null) {
            Log.w(TAG, "no camera");
            return false;
        }

        if (!mSafeToTakePicture) {
            Log.w(TAG, "cannot take picture now");
            return false;
        }
        try {
            mCamera.takePicture(null, null, null, this);
        } catch (RuntimeException e) {
            Log.w(TAG, "takePicture: " + e.toString(), e);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------------

    private void openCamera() {
        Log.d(TAG, "openCamera: " + mCameraId);

        if (mCamera != null) {
            throw new IllegalStateException();
        }

        mCamera = Camera.open(mCameraId);
        mCameraParameters = mCamera.getParameters();
        mCameraParameters.setPreviewFormat(ImageFormat.NV21);  // NV21 required by YuvImage
        choosePreviewSize(mCameraParameters);  // do BEFORE setParameters
        choosePictureSize(mCameraParameters);  // do BEFORE setParameters
        mCamera.setParameters(mCameraParameters);

        mCameraCanAutoFocus = mCameraParameters.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_AUTO);
        mCameraCanZoom = mCameraParameters.isZoomSupported();
        mCameraMaxZoom = mCameraParameters.getMaxZoom();

        final boolean rotateBitmaps = fixCameraRotation(mCameraParameters);
        allocatePreviewBuffers(mCameraParameters, rotateBitmaps);

        final Camera.Size previewSize = mCameraParameters.getPreviewSize();
        mPreviewRect.set(0, 0, previewSize.width, previewSize.height);

        final Camera.Size pictureSize = mCameraParameters.getPictureSize();
        mPictureRect.set(0, 0, pictureSize.width, pictureSize.height);

        try {
            mCamera.setPreviewTexture(mSurfaceTexture);
        } catch (IOException e) {
            Log.e(TAG, e.toString(), e);
            throw new RuntimeException(e);
        }
        try {
            mCamera.enableShutterSound(true);
        } catch (NoSuchMethodError e) {
            // ignore
        }
    }

    private void choosePreviewSize(Camera.Parameters pParameters) {
        Log.d(TAG, "choosePreviewSize");

        final Point screenSize = new Point();
        mActivity.getWindowManager().getDefaultDisplay().getSize(screenSize);
        final int screenArea = screenSize.x * screenSize.y;
        Log.d(TAG, "choosePreviewSize: screen " + screenSize.x + "x" + screenSize.y + " = " + screenArea);
        final int targetArea = screenArea / 4;
        Log.d(TAG, "choosePreviewSize: target " + targetArea);

        Camera.Size bestSize = null;
        int bestArea = 0;

        for (Camera.Size size : pParameters.getSupportedPreviewSizes()) {
            final int area = size.width * size.height;
            Log.d(TAG, "choosePreviewSize: candidate " + size.width + "x" + size.height + " = " + area);
            // Always pickup first candidate.
            if (bestSize == null) {
                bestSize = size;
                bestArea = area;
            }
            // Swap if current best is over area and this is smaller.
            else if (area < bestArea && bestArea > targetArea) {
                bestSize = size;
                bestArea = area;
            }
            // Swap if closer to target area than current best (without going over).
            else if (area > bestArea && bestArea < targetArea) {
                bestSize = size;
                bestArea = area;
            }
        }

        if (bestSize != null) {
            Log.d(TAG, "choosePreviewSize: using " + bestSize.width + ", " + bestSize.height);
            pParameters.setPreviewSize(bestSize.width, bestSize.height);
        }
    }

    private void choosePictureSize(Camera.Parameters pParameters) {
        Log.d(TAG, "choosePictureSize");

        // Social media picture sizes:
        // https://blog.bufferapp.com/ideal-image-sizes-social-media-posts
        // Facebook – 1,200 x 628
        // Twitter – 1,024 x 512
        // LinkedIn – 800 x 800
        // Google+ – 800 x 1,200
        // Pinterest – 735 x 1,102
        // Instagram – 1,080 x 1,080

        final int targetArea = TARGET_PICTURE_AREA;

        Camera.Size bestSize = null;
        int bestDelta = 0;

        for (Camera.Size size : pParameters.getSupportedPictureSizes()) {
            final int area = size.width * size.height;
            final int delta = Math.abs(area - targetArea);
            Log.d(TAG, "choosePictureSize: candidate " + size.width + "x" + size.height + " = " + area + " (" + delta + ")");
            // Always pickup first candidate.
            if (bestSize == null) {
                bestSize = size;
                bestDelta = delta;
            }
            // Otherwise pick the best candidate at least as large as target area.
            else if (delta < bestDelta && area >= targetArea) {
                bestSize = size;
                bestDelta = delta;
            }
        }

        if (bestSize != null) {
            Log.d(TAG, "choosePictureSize: using " + bestSize.width + ", " + bestSize.height);
            pParameters.setPictureSize(bestSize.width, bestSize.height);
        }
    }

    /**
     * Pre-allocate and recycle buffers for preview images.
     *
     * See http://developer.android.com/reference/android/hardware/Camera.Parameters.html#setPreviewFormat(int)
     */
    private void allocatePreviewBuffers(Camera.Parameters pParameters, boolean pRotateBitmaps) {
        Log.d(TAG, "allocatePreviewBuffers");

        final Camera.Size previewSize = pParameters.getPreviewSize();
        final int width = previewSize.width;
        final int height = previewSize.height;

        final int yStride   = (int) Math.ceil(width / 16.0) * 16;
        final int uvStride  = (int) Math.ceil((yStride / 2) / 16.0) * 16;
        final int ySize     = yStride * height;
        final int uvSize    = uvStride * height / 2;
        final int size      = ySize + uvSize * 2;
        //final int yRowIndex = yStride * y;
        //final int uRowIndex = ySize + uvSize + uvStride * c;
        //final int vRowIndex = ySize + uvStride * c;

        final int w = pRotateBitmaps ? height : width;
        final int h = pRotateBitmaps ? width : height;

        synchronized (this) {
            mPreviewBuffers.clear();
            mPreviewBitmaps.clear();
            mPreviewTimestamps.clear();
            for (int i = 0; i < NUM_PREVIEW_BUFFERS; i++) {
                mPreviewBuffers.add(new byte[size]);
                mPreviewBitmaps.add(Bitmap.createBitmap(w, h, mBitmapOptions.inPreferredConfig));
                mPreviewTimestamps.add(0L);
                mCamera.addCallbackBuffer(mPreviewBuffers.get(i));
            }
            mTempBitmap = Bitmap.createBitmap(width, height, mBitmapOptions.inPreferredConfig);
        }
    }

    /**
     * See http://developer.android.com/reference/android/hardware/Camera.html#setDisplayOrientation(int)
     */
    private boolean fixCameraRotation(Camera.Parameters pParameters) {
        final android.hardware.Camera.CameraInfo info = new android.hardware.Camera.CameraInfo();
        android.hardware.Camera.getCameraInfo(mCameraId, info);
        final int rotation = mActivity.getWindowManager().getDefaultDisplay().getRotation();
        int degrees = 0;
        switch (rotation) {
            case Surface.ROTATION_0: degrees = 0; break;
            case Surface.ROTATION_90: degrees = 90; break;
            case Surface.ROTATION_180: degrees = 180; break;
            case Surface.ROTATION_270: degrees = 270; break;
        }

        int result;
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) {
            result = (info.orientation + degrees) % 360;
            result = (360 - result) % 360;  // compensate the mirror
        } else {  // back-facing
            result = (info.orientation - degrees + 360) % 360;
        }

        Log.d(TAG, "fixCameraRotation: display=" + degrees + " cameraOrientation=" + info.orientation + " ff=" + (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT) + " final=" + result);

        // Do not use "mCamera.setDisplayOrientation(result);", not all devices
        // actually rotate the pixels (some just set the rotation in the XFIF).

        mMatrix.reset();

        // Preview images are not rotated.  Compute a matrix to rotate them in software.
        if (result != 0) {
            Log.d(TAG, "fixCameraRotation: rotate " + result);
            final Camera.Size previewSize = pParameters.getPreviewSize();
            mMatrix.postRotate(result, previewSize.width / 2, previewSize.height / 2);
            if ((result % 180) == 90) {
                final int dx = (previewSize.width - previewSize.height) / 2;
                final int dy = (previewSize.height - previewSize.width) / 2;
                Log.d(TAG, "fixCameraRotation: translate " + dx + ", " + dy);
                mMatrix.postTranslate(-dx, -dy);
            }
        }

        // Front-facing camera is upside-down in portrait mode.
        if (info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT && degrees == 0) {
            Log.d(TAG, "fixCameraRotation: flip Y");
            final Camera.Size previewSize = pParameters.getPreviewSize();
            final int x;
            final int y;
            if ((result % 180) == 0) {
                x = previewSize.width / 2;
                y = previewSize.height / 2;
            } else {
                x = previewSize.height / 2;
                y = previewSize.width / 2;
            }
            mMatrix.postScale(1f, -1f, x, y);  // invert, image is mirrored -- this appears to be the standard way to deal with it
            //mMatrix.postRotate(180, x, y);  // rotated
        }

        return (result % 180) == 90;
    }

    // ------------------------------------------------------------------------

    @Override
    public void onPreviewFrame(byte[] pData, Camera pCamera) {
        // data is NV21

        if (pData == null) {
            Log.d(TAG, "onPreviewFrame: null buffer, aborting");
            return;
        }

        synchronized (this) {
            final int bufferIndex = mPreviewBuffers.indexOf(pData);
            if (bufferIndex < 0) {
                Log.d(TAG, "onPreviewFrame: not a preview buffer, aborting");
                return;
            }
            mPreviewTimestamps.set(bufferIndex, SystemClock.elapsedRealtime() - mStartTimestamp);
            if (mProcessingHandler != null) {
                mProcessingHandler.sendMessage(mProcessingHandler.obtainMessage(WHAT_PROCESS_PREVIEW, pData));
            }
        }
    }

    @Override
    public void onPictureTaken(byte[] pData, Camera pCamera) {
        Log.d(TAG, "onPictureTaken: " + (pData != null ? pData.length : 0) + " bytes");

        // Stop getting preview images.
        if (mCamera != null) {
            try {
                mCamera.stopPreview();
            } catch (RuntimeException e) {
                Log.w(TAG, "stop: " + e.toString(), e);
                // ignore
            }
        }

        // Now queue picture work, will run after any in-progress preview work finishes.
        synchronized (this) {
            if (mProcessingHandler != null) {
                mProcessingHandler.sendMessage(mProcessingHandler.obtainMessage(WHAT_PROCESS_PICTURE, pData));
            }
        }
    }

    @Override
    public void onAutoFocus(boolean pSuccess, Camera pCamera) {
        Log.d(TAG, "onAutoFocus: success=" + pSuccess);
    }

    // ------------------------------------------------------------------------

    @Override
    public boolean handleMessage(Message pMsg) {
        switch (pMsg.what) {
            case WHAT_PROCESS_PREVIEW:
                processPreview((byte[]) pMsg.obj);
                break;
            case WHAT_CALLBACK_PREVIEW:
                callbackPreview((byte[]) pMsg.obj);
                break;
            case WHAT_PROCESS_PICTURE:
                processPicture((byte[]) pMsg.obj);
                break;
            case WHAT_CALLBACK_PICTURE:
                callbackPicture((Bitmap) pMsg.obj);
                break;
            default:
                throw new IllegalArgumentException("what=" + pMsg.what);
        }
        return true;
    }

    // ------------------------------------------------------------------------

    private void processPreview(byte[] pData) {
        // Grab buffers under lock.  Abort if buffers have changed.
        final Bitmap previewBitmap;
        synchronized (this) {
            final int bufferIndex = mPreviewBuffers.indexOf(pData);
            if (bufferIndex < 0) {
                Log.d(TAG, "processPreview: not a preview buffer, aborting");
                return;
            }
            if (mSuppressPreview) {
                if (mCamera != null) {
                    mCamera.addCallbackBuffer(pData);
                }
                return;
            }
            previewBitmap = mPreviewBitmaps.get(bufferIndex);
        }

        // Convert raw YUV to JPEG.
        {
            // Load into a YuvImage (from NV21 camera format).
            final YuvImage yuvImage = new YuvImage(pData, ImageFormat.NV21, mPreviewRect.width(), mPreviewRect.height(), null);

            final int w = yuvImage.getWidth();
            final int h = yuvImage.getHeight();
            final int jpegBufferSize = (int) (w * h * 8.25) + 1024;  // 8.25 bytes per pixel, plus 1K slop
            if (mPreviewJpegOutputStream == null || mPreviewJpegBufferSize < jpegBufferSize) {
                Log.d(TAG, "handleMessage: resizing buffer to " + jpegBufferSize + " bytes");
                mPreviewJpegBufferSize = jpegBufferSize;
                mPreviewJpegOutputStream = null;  // release before attempting to reallocate at new size
                mPreviewJpegOutputStream = new ByteArrayOutputStream(jpegBufferSize);
            }
            mPreviewJpegOutputStream.reset();
            yuvImage.compressToJpeg(mPreviewRect, 90, mPreviewJpegOutputStream);
        }
        final byte[] jpeg = mPreviewJpegOutputStream.toByteArray();

        // Convert JPEG to bitmap.
        mBitmapOptions.inBitmap = mTempBitmap;  // attempt to load into pre-allocated bitmap
        Bitmap decodedBitmap;
        try {
            decodedBitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, mBitmapOptions);
        } catch (IllegalArgumentException e) {
            mBitmapOptions.inBitmap = null;
            decodedBitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, mBitmapOptions);
        }

        // Copy into the pre-allocated bitmaps ring.  If no transform is used could swap to avoid copy.
        mCanvas.setBitmap(previewBitmap);
        mCanvas.drawBitmap(decodedBitmap, mMatrix, mPaint);

        synchronized (this) {
            if (mCallbackHandler != null) {
                mCallbackHandler.sendMessage(mCallbackHandler.obtainMessage(WHAT_CALLBACK_PREVIEW, pData));
            }
        }
    }

    private void callbackPreview(byte[] pData) {
        // Grab buffers under lock.  Abort if buffers have changed.
        final Bitmap bitmap;
        final long timestamp;
        synchronized (this) {
            final int bufferIndex = mPreviewBuffers.indexOf(pData);
            if (bufferIndex < 0) {
                Log.d(TAG, "callbackPreview: not a preview buffer, aborting");
                return;
            }
            if (mSuppressPreview) {
                if (mCamera != null) {
                    mCamera.addCallbackBuffer(pData);
                }
                return;
            }
            bitmap = mPreviewBitmaps.get(bufferIndex);
            timestamp = mPreviewTimestamps.get(bufferIndex);
        }

        // Invoke the listener.
        if (mListener != null) {
            mListener.onPreview(bitmap, timestamp);
        }

        // Release original buffer for reuse / flow control.
        // Make sure buffers have not changed while unlocked!
        synchronized (this) {
            if (mCamera != null && mPreviewBuffers.contains(pData)) {
                mCamera.addCallbackBuffer(pData);
            }
        }
    }

    // ------------------------------------------------------------------------

    private void processPicture(byte[] pData) {
        Log.d(TAG, "processPicture");

        // Ask for a mutable bitmap with desired config.
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inSampleSize = 1;
        options.inMutable = true;
        options.inPreferredConfig = mBitmapOptions.inPreferredConfig;

        // data is JPEG image
        Bitmap bitmap = null;
        while (bitmap == null && options.inSampleSize <= 32) {
            try {
                bitmap = BitmapFactory.decodeByteArray(pData, 0, pData.length, options);
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), mMatrix, true);
            } catch (OutOfMemoryError e) {
                Log.w(TAG, e.toString(), e);
                options.inSampleSize *= 2;
            }
        }
        if (bitmap != null) {
            Log.d(TAG, "processPicture: " + bitmap.getWidth() + "x" + bitmap.getHeight());
        }

        synchronized (this) {
            if (mCallbackHandler != null) {
                mCallbackHandler.sendMessage(mCallbackHandler.obtainMessage(WHAT_CALLBACK_PICTURE, bitmap));
            }
        }
    }

    private void callbackPicture(Bitmap pBitmap) {
        Log.d(TAG, "callbackPicture");

        if (mListener != null) {
            mListener.onPicture(pBitmap);
        }
    }

    // ------------------------------------------------------------------------

    /**
     * Ask the camera to auto-focus the touch location.
     *
     * @param pView (View) Preview image view being touched.
     * @param pEvent (MotionEvent) Touch event.
     * @return (boolean) True if handled.
     *
     * http://stackoverflow.com/questions/18594602/how-to-implement-pinch-zoom-feature-for-camera-preview
     */
    @Override
    public boolean onTouch(View pView, MotionEvent pEvent) {
        if (mCamera == null || mCameraParameters == null) {
            return false;
        }
        final int action = pEvent.getActionMasked();

        if (pEvent.getPointerCount() > 1) {
            // Zoom with pinch.
            if (!mCameraCanZoom) {
                return true;
            }
            mCamera.cancelAutoFocus();
            final float touchDistance = getFingerSpacing(pEvent);
            int zoom = mCameraParameters.getZoom();
            final int maxZoom = mCameraMaxZoom;
            switch (action) {
                case MotionEvent.ACTION_POINTER_DOWN:
                    mTouchDistance = touchDistance;
                    break;
                case MotionEvent.ACTION_MOVE:
                    if (touchDistance > mTouchDistance) {
                        if (zoom < maxZoom) {
                            zoom++;
                        }
                    } else if (touchDistance < mTouchDistance) {
                        if (zoom > 0) {
                            zoom--;
                        }
                    }
                    mTouchDistance = touchDistance;
                    if (mCameraParameters.getZoom() != zoom) {
                        mCameraParameters.setZoom(zoom);
                        mCamera.setParameters(mCameraParameters);
                    }
                    break;
            }
        } else if (action == MotionEvent.ACTION_UP) {
            // Re-enable auto-focus.
            if (mCameraCanAutoFocus && mSafeToTakePicture) {
                try {
                    mCamera.autoFocus(this);
                } catch (RuntimeException e) {
                    // Watch out for autofocus from a bad state, ignore.
                    Log.w(TAG, "onTouch.autoFocus: " + e.toString(), e);
                }
            }
        }

        // Accessibility: tell view when clicked.
        if (action == MotionEvent.ACTION_UP) {
            pView.performClick();
        }

        return true;
    }

    private float getFingerSpacing(MotionEvent event) {
        float x = event.getX(0) - event.getX(1);
        float y = event.getY(0) - event.getY(1);
        return (float) Math.sqrt(x * x + y * y);
    }

}
