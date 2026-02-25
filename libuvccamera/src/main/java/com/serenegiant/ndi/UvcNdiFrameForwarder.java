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
    private final String cameraFormat;      // format of incoming frames from UVC
    private String ndiFormat;                // format to send to NDI
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
    /**
     * Create a forwarder specifying the camera's pixel format.
     * The initial NDI output format will be the same; you can change it
     * later with {@link #setNdiFormat(String)}.
     * @param ndiSender the NDI sender instance
     * @param cameraFormat one of "nv12","nv21","yuyv","yuv422"
     */
    public UvcNdiFrameForwarder(NdiSender ndiSender, String cameraFormat) {
        this(ndiSender, cameraFormat, null);
    }

    /**
     * Create a frame forwarder from UVC to NDI with callback
     * @param ndiSender the NDI sender instance
     * @param frameFormat the frame format (e.g., "nv12", "yuyv")
     * @param callback optional callback for handling frame events
     */
    public UvcNdiFrameForwarder(NdiSender ndiSender, String cameraFormat, INdiFrameSender callback) {
        this.ndiSender = ndiSender;
        this.cameraFormat = cameraFormat != null ? cameraFormat.toLowerCase() : "nv12";
        // start with NDI output same as camera input
        this.ndiFormat = this.cameraFormat;
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

            // Forward to callback if available (pass camera format)
            if (callback != null && frameData != null) {
                callback.onNdiFrameAvailable(
                    ByteBuffer.wrap(frameData),
                    cameraFormat,
                    width,
                    height,
                    System.currentTimeMillis() * 1000
                );
            }

            // Send via NDI depending on requested output format
            if (frameData != null) {
                final int expectedBytes = expectedFrameBytes(ndiFormat, width, height);
                int actualBytes = frameData.length;
                ByteBuffer rgbaBuf = null;
                switch (ndiFormat) {
                    case "nv12":
                    case "nv21":
                        // assume cameraFormat matches these, just forward
                        ndiSender.sendVideoNV12(width, height, frameData);
                        break;
                    case "yuyv":
                    case "yuv422":
                        ndiSender.sendVideoYUYV(width, height, frameData);
                        break;
                    case "rgba":
                    case "bgra": {
                        // convert camera data (nv12/yuyv) to RGBA/BGRA
                        rgbaBuf = ensureRgbaBuffer(width, height);
                        if ("nv12".equals(cameraFormat) || "nv21".equals(cameraFormat)) {
                            convertToRgba(cameraFormat, frameData, rgbaBuf, width, height);
                        } else {
                            // treat as YUYV
                            convertToRgba("yuyv", frameData, rgbaBuf, width, height);
                        }
                        if (ndiFormat.equals("bgra")) {
                            convertToBgra(cameraFormat, frameData, rgbaBuf, width, height);
                        }
                        actualBytes = rgbaBuf.capacity();
                        ndiSender.sendVideoRGBA(width, height, rgbaBuf);
                        break;
                    }
                    default:
                        Log.w(TAG, "Unsupported NDI output format: " + ndiFormat);
                        if (callback != null) {
                            callback.onNdiError("Unsupported NDI format: " + ndiFormat, null);
                        }
                        break;
                }
                if (!loggedStreamInfoOnce) {
                    Log.i(TAG, "NDI forward start: ndiFormat=" + ndiFormat
                            + " camera=" + cameraFormat
                            + " size=" + width + "x" + height
                            + " expectedBytes=" + expectedBytes
                            + " actualBytes=" + actualBytes);
                    loggedStreamInfoOnce = true;
                }
                if (expectedBytes > 0 && actualBytes != expectedBytes) {
                    Log.w(TAG, "NDI payload size mismatch: ndiFormat=" + ndiFormat
                            + " camera=" + cameraFormat
                            + " size=" + width + "x" + height
                            + " expectedBytes=" + expectedBytes
                            + " actualBytes=" + actualBytes);
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

    // helper for RGBA conversions
    // reusable direct buffer for RGBA conversion (avoids GC churn)
    private ByteBuffer rgbaBuffer = null;

    /**
     * convert src (nv12 or yuyv) into \"rgba\" stored in direct buffer
     * returns direct buffer ready for sending.
     */
    private ByteBuffer ensureRgbaBuffer(int w, int h) {
        final int required = w * h * 4;
        if (rgbaBuffer == null || rgbaBuffer.capacity() < required) {
            rgbaBuffer = ByteBuffer.allocateDirect(required);
        }
        rgbaBuffer.clear();
        return rgbaBuffer;
    }

    private void convertToRgba(String fmt, byte[] src, ByteBuffer dst, int w, int h) {
        // dst must be direct
        if (!dst.isDirect()) {
            throw new IllegalArgumentException("dst buffer must be direct");
        }
        if ("nv12".equals(fmt) || "nv21".equals(fmt)) {
            NdiSender.convertNV12ToRgba(src, dst, w, h);
        } else {
            NdiSender.convertYuyvToRgba(src, dst, w, h);
        }
    }

    private void convertToBgra(String fmt, byte[] src, ByteBuffer dst, int w, int h) {
        convertToRgba(fmt, src, dst, w, h);
        // swap R<->B in-place
        dst.rewind();
        while (dst.remaining() >= 4) {
            byte r = dst.get();
            byte g = dst.get();
            byte b = dst.get();
            byte a = dst.get();
            dst.position(dst.position() - 4);
            dst.put(b).put(g).put(r).put(a);
        }
        dst.rewind();
    }

    /**
     * change the format that will be transmitted over NDI
     * @param format one of "nv12","nv21","yuyv","yuv422","rgba","bgra"
     */
    public void setNdiFormat(final String format) {
        ndiFormat = (format != null) ? format.toLowerCase() : cameraFormat;
    }

    /**
     * query current NDI output format
     */
    public String getNdiFormat() {
        return ndiFormat;
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
            case "rgba":
            case "bgra":
                return width * height * 4;
            default:
                return -1;
        }
    }
}
