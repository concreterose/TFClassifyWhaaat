package com.concreterose.tfclassifywhaaat;

import android.Manifest;
import android.app.Activity;
import android.graphics.Bitmap;
import android.os.AsyncTask;
import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.TextView;

import com.concreterose.lib.CameraLib;
import com.concreterose.lib.PermissionLib;

import org.tensorflow.demo.Classifier;

import java.util.List;

public class ClassifyActivity extends Activity implements CameraLib.CameraLibListener, PermissionLib.PermissionListener {
    private final static String TAG = ClassifyActivity.class.getSimpleName();

    private final static int PERMISSION_REQUEST_CODE_CAMERA = 1;

    private CameraLib mCameraLib;
    private PermissionLib mPermissionLib;
    private TFImageClassifierHelper mClassifier;

    private MyCheaperImageView mMyCheaperImageView;
    private MyLabelRectsView mMyLabelRectsView;
    private TextView mTextView;

    private boolean mStartCameraDuringOnResume = false;

    // ------------------------------------------------------------------------

    private class AsyncClassify extends AsyncTask<Bitmap, Void, List<Classifier.Recognition>> {

        @Override
        protected List<Classifier.Recognition> doInBackground(Bitmap... pBitmaps) {
            final Bitmap bitmap = pBitmaps[0];
            final List<Classifier.Recognition> results = mClassifier.processImage(bitmap);
            return results;
        }

        @Override
        protected void onPostExecute(List<Classifier.Recognition> pResults) {
            super.onPostExecute(pResults);

            // Mark as finished so it will be restarted with next image.
            mAsyncClassify = null;

            if (pResults.isEmpty()) {
                return;
            }

            mMyLabelRectsView.clear();
            final StringBuilder sb = new StringBuilder();

            for (int i = 0; i < pResults.size(); i++) {
                final Classifier.Recognition result = pResults.get(i);

                Log.d(TAG, "result " + i
                        + " id=" + result.getId()
                        + " title=" + result.getTitle()
                        + " rect=" + result.getLocation()
                        + " conf=" + result.getConfidence()
                );

                mMyLabelRectsView.add(result.getTitle(), result.getLocation());
                if (sb.length() > 0) {
                    sb.append("\n");
                }
                sb.append(result.getTitle());
            }
            mTextView.setText(sb.toString());
        }
    }

    private AsyncClassify mAsyncClassify = null;

    // ------------------------------------------------------------------------

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.classify_activity);

        mCameraLib = new CameraLib.Builder(this)
                .setListener(this)
                .build();

        mPermissionLib = new PermissionLib.Builder(this)
                .registerRequest(PERMISSION_REQUEST_CODE_CAMERA, new String[]{Manifest.permission.CAMERA}, 0, this)
                .build();

        mMyCheaperImageView = (MyCheaperImageView) findViewById(R.id.image);
        mMyLabelRectsView = (MyLabelRectsView) findViewById(R.id.labels);
        mTextView = (TextView) findViewById(R.id.text);

        mClassifier = new TFImageClassifierHelper(this);

        updateCameraStuff();

        // Request permission to use camera.
        mPermissionLib.request(PERMISSION_REQUEST_CODE_CAMERA);
    }

    @Override
    protected void onResume() {
        super.onResume();

        if (mStartCameraDuringOnResume) {
            mCameraLib.start();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();

        mCameraLib.stop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Route request to PermissionLib to decide what to do.  Calls back to
        // onPermissionGranted or onPermissionDenied.
        mPermissionLib.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    // ------------------------------------------------------------------------

    @Override
    public void onPermissionGranted(int pRequestCode) {
        Log.d(TAG, "onPermissionGranted: " + pRequestCode);

        switch (pRequestCode) {
            case PERMISSION_REQUEST_CODE_CAMERA:
                if (!mStartCameraDuringOnResume) {
                    mStartCameraDuringOnResume = true;
                    mCameraLib.start();
                    updateCameraStuff();
                }
                break;

            default:
                throw new IllegalArgumentException("bad code: " + pRequestCode);
        }
    }

    @Override
    public void onPermissionDenied(int pRequestCode) {
        Log.d(TAG, "onPermissionDenied: " + pRequestCode);

        switch (pRequestCode) {
            case PERMISSION_REQUEST_CODE_CAMERA:
                finish();
                break;

            default:
                throw new IllegalArgumentException("bad code: " + pRequestCode);
        }
    }

    // ------------------------------------------------------------------------

    @Override
    public void onPreview(Bitmap pBitmap, long pTimestampMsecs) {
        mMyCheaperImageView.setBitmap(pBitmap);

        // If not busy, start a new async classify.
        if (mAsyncClassify == null) {
            mAsyncClassify = new AsyncClassify();
            mAsyncClassify.execute(pBitmap);
        }
    }

    @Override
    public void onPicture(Bitmap pBitmap) {
        Log.d(TAG, "onPicture");
    }

    // ------------------------------------------------------------------------

    private void updateCameraStuff() {
        // Switch to the back facing camera.
        if (mCameraLib.getIsFrontFacingCamera() && mCameraLib.canChangeCamera()) {
            mCameraLib.changeCamera();
        }

        final int w = mCameraLib.getPreviewSize().width();
        final int h = mCameraLib.getPreviewSize().height();
        final int rotation = 0;  // cameraLib already handles rotation before delivering preview frames
        final int screenOrientation = 0;
        mClassifier.setImageSize(w, h, rotation, screenOrientation);
    }

}
