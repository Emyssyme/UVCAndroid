package com.herohan.uvcapp.fragment;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.widget.ArrayAdapter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.herohan.uvcapp.InternalCameraInfo;
import com.herohan.uvcapp.R;

import java.util.List;

/**
 * Shows a list of all available internal device cameras (wide, telephoto, ultra-wide, front…).
 * The user picks one and the result is delivered via {@link OnCameraSelectedListener}.
 */
public class InternalCameraListDialogFragment extends DialogFragment {

    private final List<InternalCameraInfo> mCameras;
    private OnCameraSelectedListener mListener;

    public InternalCameraListDialogFragment(@NonNull List<InternalCameraInfo> cameras) {
        mCameras = cameras;
    }

    public void setOnCameraSelectedListener(OnCameraSelectedListener listener) {
        mListener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();

        // Build a simple string array for the AlertDialog list
        String[] names = new String[mCameras.size()];
        for (int i = 0; i < mCameras.size(); i++) {
            names[i] = mCameras.get(i).displayName;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(R.string.internal_camera_list_title);

        if (names.length == 0) {
            builder.setMessage(R.string.internal_camera_list_empty);
            builder.setPositiveButton(android.R.string.ok, (d, w) -> dismiss());
        } else {
            builder.setItems(names, (dialog, which) -> {
                if (mListener != null) {
                    mListener.onCameraSelected(mCameras.get(which));
                }
                dismiss();
            });
        }

        builder.setNegativeButton(android.R.string.cancel, (d, w) -> dismiss());
        return builder.create();
    }

    public interface OnCameraSelectedListener {
        void onCameraSelected(InternalCameraInfo cameraInfo);
    }
}
