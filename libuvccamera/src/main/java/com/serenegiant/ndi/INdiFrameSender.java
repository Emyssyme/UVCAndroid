/*
 * Interface for integrating NDI sending with UVC camera frames
 */

package com.serenegiant.ndi;

import java.nio.ByteBuffer;

/**
 * Callback interface for sending UVC camera frames via NDI.
 * Implement this interface to receive UVC frames and forward them to NDI.
 */
public interface INdiFrameSender {
    /**
     * Called when a new frame is available from the UVC camera.
     * Implementations should:
     * 1. Determine the frame format
     * 2. Send it via NdiSender.sendVideo*() methods
     * 3. Handle any conversion if needed
     *
     * @param frame the raw UVC frame data
     * @param frameFormat the pixel format (e.g., "nv12", "yuyv")
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @param presentationTimeUs frame timestamp in microseconds
     */
    void onNdiFrameAvailable(ByteBuffer frame, String frameFormat, int width, int height, long presentationTimeUs);

    /**
     * Called when an error occurs in NDI transmission
     * @param errorMessage description of the error
     * @param throwable optional exception
     */
    void onNdiError(String errorMessage, Throwable throwable);

    /**
     * Called when NDI gets connected/disconnected
     * @param connected true if NDI is connected, false if disconnected
     */
    void onNdiConnectionChanged(boolean connected);
}
