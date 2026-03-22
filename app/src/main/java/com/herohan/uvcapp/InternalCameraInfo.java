package com.herohan.uvcapp;

import android.hardware.camera2.CameraCharacteristics;

/**
 * Represents a single physical or logical camera on the device.
 * Used when browsing / selecting internal cameras via Camera2 API.
 */
public class InternalCameraInfo {

    public static final int TYPE_BACK_WIDE        = 0;
    public static final int TYPE_BACK_ULTRA_WIDE  = 1;
    public static final int TYPE_BACK_TELEPHOTO   = 2;
    public static final int TYPE_FRONT            = 3;
    public static final int TYPE_EXTERNAL         = 4;
    public static final int TYPE_UNKNOWN          = 5;

    public final String cameraId;
    public final int    cameraType;
    public final int    lensFacing;        // CameraCharacteristics.LENS_FACING_*
    public final float  focalLength;       // mm, first reported value
    public final int    sensorOrientation; // degrees: 0, 90, 180, 270
    public final String displayName;

    public InternalCameraInfo(String cameraId, int cameraType, int lensFacing,
                              float focalLength, int sensorOrientation) {
        this.cameraId          = cameraId;
        this.cameraType        = cameraType;
        this.lensFacing        = lensFacing;
        this.focalLength       = focalLength;
        this.sensorOrientation = sensorOrientation;
        this.displayName       = buildDisplayName(cameraType, focalLength);
    }

    private static String buildDisplayName(int type, float focalLength) {
        String base;
        switch (type) {
            case TYPE_BACK_WIDE:       base = "Back Wide";       break;
            case TYPE_BACK_ULTRA_WIDE: base = "Back Ultra-Wide"; break;
            case TYPE_BACK_TELEPHOTO:  base = "Back Telephoto";  break;
            case TYPE_FRONT:           base = "Front";           break;
            case TYPE_EXTERNAL:        base = "External";        break;
            default:                   base = "Camera";          break;
        }
        if (focalLength > 0f) {
            return base + String.format(" (%.1f mm)", focalLength);
        }
        return base;
    }

    @Override
    public String toString() {
        return displayName;
    }
}
