package com.concreterose.lib;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.support.annotation.NonNull;
import android.support.annotation.StringRes;
import android.support.v4.app.ActivityCompat;
import android.util.Log;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * Request permissions with rationale if needed.
 */
public final class PermissionLib {
    private final static String TAG = PermissionLib.class.getSimpleName();

    // ------------------------------------------------------------------------

    /**
     * Request permission callback.
     */
    @SuppressWarnings("UnusedParameters")
    public interface PermissionListener {

        /**
         * Callback when permission request succeeds.
         *
         * @param pRequestCode (int) Permission request code.
         */
        void onPermissionGranted(int pRequestCode);

        /**
         * Callback when permission is denied.
         *
         * @param pRequestCode
         */
        void onPermissionDenied(int pRequestCode);
    }

    // ------------------------------------------------------------------------

    private static class PermissionRequest {
        private String[] mPermissions;
        private int mRationaleStringResId;
        private PermissionListener mListener;
    }

    // ------------------------------------------------------------------------

    /**
     * Create a PermissionLib object.
     */
    public final static class Builder {
        private final Activity mActivity;
        private final Map<Integer, PermissionRequest> mRequests = new TreeMap<>();

        /**
         * Create a builder to build a PermissionLib.
         *
         * @param pActivity (Activity) Activity.
         */
        public Builder(@NonNull Activity pActivity) {
            mActivity = pActivity;
        }

        /**
         * Register permission request with callback.
         *
         * @param pRequestCode (int) Unique request code.
         * @param pPermissions (String[]) Permissions.
         * @param pRationaleStringResId (int) String resource explaining why permission is needed (0 for none).
         * @param pListener (PermissionListener) Callback.
         * @return (Builder) Self, for chaining.
         */
        public final Builder registerRequest(int pRequestCode, @NonNull String[] pPermissions, @StringRes int pRationaleStringResId, @NonNull PermissionListener pListener) {
            final PermissionRequest request = new PermissionRequest();
            request.mPermissions = new String[pPermissions.length];
            System.arraycopy(pPermissions, 0, request.mPermissions, 0, pPermissions.length);
            request.mRationaleStringResId = pRationaleStringResId;
            request.mListener = pListener;

            mRequests.put(pRequestCode, request);

            return this;
        }

        /**
         * Build the PermissionLib.
         *
         * @return (PermissionLib) PermissionLib.
         */
        public final PermissionLib build() {
            final PermissionLib permissionLib = new PermissionLib(this);
            return permissionLib;
        }
    }

    // ------------------------------------------------------------------------

    private final Activity mActivity;
    private final Map<Integer, PermissionRequest> mRequests;

    private PermissionLib(Builder pBuilder) {
        mActivity = pBuilder.mActivity;
        mRequests = Collections.unmodifiableMap(pBuilder.mRequests);
    }

    /**
     * Make a registered permission request.
     *
     * @param pRequestCode
     */
    public void request(final int pRequestCode) {
        final PermissionRequest request = mRequests.get(pRequestCode);
        if (request == null) {
            throw new IllegalArgumentException("request not registered");
        }

        // If already granted, invoke callback and return.
        if (checkAllGranted(request.mPermissions)) {
            Log.d(TAG, "request: already granted");
            request.mListener.onPermissionGranted(pRequestCode);
            return;
        }

        // Show rationale?
        if (request.mRationaleStringResId != 0 && shouldShowRationale(request.mPermissions)) {
            Log.d(TAG, "request: showing rationale");
            new AlertDialog.Builder(mActivity)
                    .setMessage(request.mRationaleStringResId)
                    .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Log.d(TAG, "request: rationale accepted");
                            ActivityCompat.requestPermissions(mActivity, request.mPermissions, pRequestCode);
                        }
                    })
                    .setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int which) {
                            Log.d(TAG, "request: rationale cancel");
                            request.mListener.onPermissionDenied(pRequestCode);
                        }
                    })
                    .setOnCancelListener(new DialogInterface.OnCancelListener() {
                        @Override
                        public void onCancel(DialogInterface dialog) {
                            Log.d(TAG, "request: rationale cancel");
                            request.mListener.onPermissionDenied(pRequestCode);
                        }
                    })
                    .show();
            return;
        }

        // Otherwise request permission now.
        Log.d(TAG, "request: requesting permissions");
        ActivityCompat.requestPermissions(mActivity, request.mPermissions, pRequestCode);
    }

    public void onRequestPermissionsResult(int pRequestCode, String[] pPermissions, int[] pGrantResults) {
        final PermissionRequest request = mRequests.get(pRequestCode);
        if (request == null) {
            return;  // not our request
        }

        if (checkAllGranted(request.mPermissions)) {
            Log.d(TAG, "onRequestPermissionsResult: granted");
            request.mListener.onPermissionGranted(pRequestCode);
        } else {
            Log.d(TAG, "onRequestPermissionsResult: denied");
            request.mListener.onPermissionDenied(pRequestCode);
        }
    }

    // ------------------------------------------------------------------------

    private boolean checkAllGranted(String[] pPermissions) {
        for (String permission : pPermissions) {
            if (ActivityCompat.checkSelfPermission(mActivity, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean shouldShowRationale(String[] pPermissions) {
        for (String permission : pPermissions) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(mActivity, permission)) {
                return true;
            }
        }
        return false;
    }
}
