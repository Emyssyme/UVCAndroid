/*
 * Utility class for forwarding UVC camera frames to NDI sender
 */

package com.serenegiant.ndi;

import android.util.Log;

import com.serenegiant.usb.IFrameCallback;

import java.nio.ByteBuffer;

/**
 * Helper class that bridges UVC camera frame callbacks to NDI transmission.
 * 
 * Usage:
 * <pre>
 *   Ndi.initialize();
 *   NdiSender sender = new NdiSender("My Camera");
 *   UvcNdiFrameForwarder forwarder = new UvcNdiFrameForwarder(sender, "nv12");
 *   
 *   uvcCamera.setFrameCallback(forwarder, UVCCamera.PIXEL_FORMAT_NV12);
 * </pre>
 */
public class UvcNdiFrameForwarder implements IFrameCallback {
    private static final String TAG = "UvcNdiForwarder";

    private final NdiSender ndiSender;
    private final String frameFormat;
    private final INdiFrameSender callback;
    private int width;
    private int height;
    private long frameCount = 0;
    private boolean loggedStreamInfoOnce = false;

    /**
     * Create a frame forwarder from UVC to NDI
     * @param ndiSender the NDI sender instance
     * @param frameFormat the frame format (e.g., "nv12", "yuyv")
     */
    public UvcNdiFrameForwarder(NdiSender ndiSender, String frameFormat) {
        this(ndiSender, frameFormat, null);
    }

    /**
     * Create a frame forwarder from UVC to NDI with callback
     * @param ndiSender the NDI sender instance
     * @param frameFormat the frame format (e.g., "nv12", "yuyv")
     * @param callback optional callback for handling frame events
     */
    public UvcNdiFrameForwarder(NdiSender ndiSender, String frameFormat, INdiFrameSender callback) {
        this.ndiSender = ndiSender;
        this.frameFormat = frameFormat != null ? frameFormat.toLowerCase() : "nv12";
        this.callback = callback;
    }

    /**
     * Set the frame dimensions (width and height)
     * Should be called before frames start arriving
     * @param width frame width in pixels
     * @param height frame height in pixels
     */
    public void setFrameDimensions(int width, int height) {
        this.width = width;
        this.height = height;
    }

    /**
     * Called when a new frame is available from the UVC camera
     * @param frame the raw frame data
     */
    @Override
    public void onFrame(ByteBuffer frame) {
        try {
            if (ndiSender == null) {
                Log.w(TAG, "NDI sender is null, dropping frame");
                return;
            }

            frameCount++;

            // Convert frame to byte array if needed
            byte[] frameData = null;
            if (frame != null && frame.remaining() > 0) {
                frameData = new byte[frame.remaining()];
                int pos = frame.position();
                frame.get(frameData);
                frame.position(pos); // Restore position for potential reuse
            }

            // Forward to callback if available
            if (callback != null && frameData != null) {
                callback.onNdiFrameAvailable(
                    ByteBuffer.wrap(frameData),
                    frameFormat,
                    width,
                    height,
                    System.currentTimeMillis() * 1000
                );
            }

            // Send via NDI based on format
            if (frameData != null) {
                final int expectedBytes = expectedFrameBytes(frameFormat, width, height);
                if (!loggedStreamInfoOnce) {
                    Log.i(TAG, "NDI forward start: format=" + frameFormat
                            + " size=" + width + "x" + height
                            + " expectedBytes=" + expectedBytes
                            + " actualBytes=" + frameData.length);
                    loggedStreamInfoOnce = true;
                }
                if (expectedBytes > 0 && frameData.length != expectedBytes) {
                    Log.w(TAG, "NDI payload size mismatch: format=" + frameFormat
                            + " size=" + width + "x" + height
                            + " expectedBytes=" + expectedBytes
                            + " actualBytes=" + frameData.length);
                }
                switch (frameFormat) {
                    case "nv12":
                    case "nv21":
                        ndiSender.sendVideoNV12(width, height, frameData);
                        break;
                    case "yuyv":
                    case "yuv422":
                        ndiSender.sendVideoYUYV(width, height, frameData);
                        break;
                    default:
                        Log.w(TAG, "Unsupported frame format: " + frameFormat);
                        if (callback != null) {
                            callback.onNdiError("Unsupported frame format: " + frameFormat, null);
                        }
                        break;
                }
            }

            if (frameCount % 100 == 0) {
                Log.d(TAG, "Forwarded " + frameCount + " frames to NDI");
            }

        } catch (Exception e) {
            Log.e(TAG, "Error forwarding frame to NDI", e);
            if (callback != null) {
                callback.onNdiError("Error forwarding frame to NDI: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Get the total number of frames forwarded
     * @return frame count
     */
    public long getFrameCount() {
        return frameCount;
    }

    /**
     * Reset the frame counter
     */
    public void resetFrameCount() {
        frameCount = 0;
    }

    private static int expectedFrameBytes(String format, int width, int height) {
        if (width <= 0 || height <= 0) return -1;
        switch (format) {
            case "nv12":
            case "nv21":
                return (width * height * 3) / 2;
            case "yuyv":
            case "yuv422":
                return width * height * 2;
            default:
                return -1;
        }
    }
}
