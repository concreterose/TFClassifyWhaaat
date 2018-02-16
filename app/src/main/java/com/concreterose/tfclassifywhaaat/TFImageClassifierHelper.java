package com.concreterose.tfclassifywhaaat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.os.SystemClock;
import android.util.Log;

import org.tensorflow.demo.Classifier;
import org.tensorflow.demo.TensorFlowImageClassifier;
import org.tensorflow.demo.env.ImageUtils;

import java.util.List;

/**
 * Load and use an image classifier.  Based on https://github.com/tensorflow/tensorflow/blob/r1.5/tensorflow/examples/android/src/org/tensorflow/demo/ClassifierActivity.java
 */

public class TFImageClassifierHelper {
    private final static String TAG = TFImageClassifierHelper.class.getSimpleName();

    // These are the settings for the original v1 Inception model. If you want to
    // use a model that's been produced from the TensorFlow for Poets codelab,
    // you'll need to set IMAGE_SIZE = 299, IMAGE_MEAN = 128, IMAGE_STD = 128,
    // INPUT_NAME = "Mul", and OUTPUT_NAME = "final_result".
    // You'll also need to update the MODEL_FILE and LABEL_FILE paths to point to
    // the ones you produced.
    //
    // To use v3 Inception model, strip the DecodeJpeg Op from your retrained
    // model first:
    //
    // python strip_unused.py \
    // --input_graph=<retrained-pb-file> \
    // --output_graph=<your-stripped-pb-file> \
    // --input_node_names="Mul" \
    // --output_node_names="final_result" \
    // --input_binary=true
    private static final int INPUT_SIZE = 224;
    private static final int IMAGE_MEAN = 117;
    private static final float IMAGE_STD = 1;
    private static final String INPUT_NAME = "input";
    private static final String OUTPUT_NAME = "output";

    private static final String MODEL_FILE = "file:///android_asset/tensorflow_inception_graph.pb";
    private static final String LABEL_FILE = "file:///android_asset/imagenet_comp_graph_label_strings.txt";

    private static final boolean MAINTAIN_ASPECT = true;

    private final Classifier mClassifier;
    private Matrix mFrameToCropTransform;
    private Matrix mCropToFrameTransform;

    private final Bitmap mCroppedBitmap;
    private final Canvas mCanvas;

    public TFImageClassifierHelper(Context pContext) {
        mClassifier =
                TensorFlowImageClassifier.create(
                        pContext.getResources().getAssets(),
                        MODEL_FILE,
                        LABEL_FILE,
                        INPUT_SIZE,
                        IMAGE_MEAN,
                        IMAGE_STD,
                        INPUT_NAME,
                        OUTPUT_NAME);

        mCroppedBitmap = Bitmap.createBitmap(INPUT_SIZE, INPUT_SIZE, Bitmap.Config.ARGB_8888);
        mCanvas = new Canvas(mCroppedBitmap);
    }

    public TFImageClassifierHelper setImageSize(int pWidth, int pHeight, int pRotation, int pScreenOrientation) {
        Log.d(TAG, "setImageSize " + pWidth + "x" + pHeight);

        final int sensorOrientation = pRotation - pScreenOrientation;

        mFrameToCropTransform = ImageUtils.getTransformationMatrix(
                pWidth, pHeight,
                INPUT_SIZE, INPUT_SIZE,
                sensorOrientation, MAINTAIN_ASPECT);

        mCropToFrameTransform = new Matrix();
        mFrameToCropTransform.invert(mCropToFrameTransform);

        return this;
    }

    public List<Classifier.Recognition> processImage(Bitmap pBitmap) {
        final long startTime = SystemClock.uptimeMillis();
        mCanvas.drawBitmap(pBitmap, mFrameToCropTransform, null);
        final List<Classifier.Recognition> results = mClassifier.recognizeImage(mCroppedBitmap);
        final long deltaTime = SystemClock.uptimeMillis() - startTime;
        Log.d(TAG, "processImage: " + results.size() + " results in " + deltaTime + " msecs");
        return results;
    }
}
