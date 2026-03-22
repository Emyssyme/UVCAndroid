package com.herohan.uvcapp.fragment;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.herohan.uvcapp.R;

import java.util.List;

/**
 * Lets the user pick a preview resolution for the currently-open internal camera.
 * Resolutions are passed in sorted largest-area-first; the currently-active size
 * is pre-selected with a radio button.
 */
public class InternalCameraResolutionDialogFragment extends DialogFragment {

    private final List<Size>  mSizes;
    private final Size        mCurrentSize;
    private OnResolutionSelectedListener mListener;

    public InternalCameraResolutionDialogFragment(
            @NonNull List<Size> sizes, @Nullable Size currentSize) {
        mSizes       = sizes;
        mCurrentSize = currentSize;
    }

    public void setOnResolutionSelectedListener(OnResolutionSelectedListener listener) {
        mListener = listener;
    }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        Context ctx = requireContext();

        String[] labels   = new String[mSizes.size()];
        int      checkedIndex = -1;

        for (int i = 0; i < mSizes.size(); i++) {
            Size s = mSizes.get(i);
            labels[i] = s.getWidth() + " × " + s.getHeight();
            if (mCurrentSize != null
                    && s.getWidth()  == mCurrentSize.getWidth()
                    && s.getHeight() == mCurrentSize.getHeight()) {
                checkedIndex = i;
            }
        }

        final int[] selectedIndex = {checkedIndex};

        AlertDialog.Builder builder = new AlertDialog.Builder(ctx);
        builder.setTitle(R.string.internal_camera_resolution_title);

        if (labels.length == 0) {
            builder.setMessage(R.string.internal_camera_resolution_empty);
            builder.setPositiveButton(android.R.string.ok, (d, w) -> dismiss());
        } else {
            builder.setSingleChoiceItems(labels, checkedIndex,
                    (dialog, which) -> selectedIndex[0] = which);
            builder.setPositiveButton(R.string.video_format_ok_button, (dialog, which) -> {
                if (selectedIndex[0] >= 0 && mListener != null) {
                    mListener.onResolutionSelected(mSizes.get(selectedIndex[0]));
                }
                dismiss();
            });
        }

        builder.setNegativeButton(R.string.video_format_cancel_button, (d, w) -> dismiss());
        return builder.create();
    }

    public interface OnResolutionSelectedListener {
        void onResolutionSelected(Size size);
    }
}
