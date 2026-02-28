/*
 * NDI Sender implementation for transmitting video frames via NDI protocol
 * Based on https://github.com/WalkerKnapp/devolay (Apache 2.0)
 */

package com.serenegiant.ndi;

import java.nio.ByteBuffer;

/**
 * NDI Sender class for transmitting UVC camera frames via NDI network protocol.
 * 
 * Usage:
 * <pre>
 *   Ndi.initialize();
 *   NdiSender sender = new NdiSender("My Camera");
 *   
 *   // Send frames as they come from UVC camera
 *   sender.sendVideoNV12(width, height, frameData);
 *   
 *   sender.close();
 *   Ndi.shutdown();
 * </pre>
 */
public class NdiSender {
    private long instancePointer;
    private boolean closed = false;

    /**
     * Create a new NDI sender with the specified source name
     * @param sourceName the name of this NDI source (visible to NDI receivers)
     */
    public NdiSender(String sourceName) {
        this.instancePointer = nSendCreate(sourceName);
        if (this.instancePointer == 0) {
            throw new RuntimeException("Failed to create NDI sender");
        }
    }

    /**
     * Send a video frame in YUYV format (16-bit, 8 bits per channel, no alpha)
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @param data YUYV frame data as byte array
     */
    public void sendVideoYUYV(int width, int height, byte[] data) {
        if (!closed && instancePointer != 0) {
            nSendVideoYUYV(instancePointer, width, height, data);
        }
    }

    /**
     * Send a video frame in NV12 format (12-bit, 8 bits per channel)
     * NV12 is commonly used by 4K capture cards like Cam Link 4K.
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @param data NV12 frame data (Y plane + interleaved UV plane) as byte array
     */
    public void sendVideoNV12(int width, int height, byte[] data) {
        if (!closed && instancePointer != 0) {
            nSendVideoNV12(instancePointer, width, height, data);
        }
    }

    /**
     * Send a video frame in RGBA format (32-bit, 8 bits per channel with alpha)
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @param data RGBA frame data as ByteBuffer
     */
    public void sendVideoRGBA(int width, int height, ByteBuffer data) {
        if (!closed && instancePointer != 0) {
            nSendVideo(instancePointer, width, height, data);
        }
    }

    /**
     * Send a video frame in BGRA format (32-bit, 8 bits per channel with alpha)
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @param data BGRA frame data as ByteBuffer
     */
    public void sendVideoBGRA(int width, int height, ByteBuffer data) {
        if (!closed && instancePointer != 0) {
            nSendVideo(instancePointer, width, height, data);
        }
    }

    /**
     * Send a video frame with generic ByteBuffer data
     * @param width frame width in pixels
     * @param height frame height in pixels
     * @param data frame data as ByteBuffer
     */
    public void sendVideoBuffer(int width, int height, ByteBuffer data) {
        if (!closed && instancePointer != 0) {
            nSendVideo(instancePointer, width, height, data);
        }
    }

    /**
     * Convert YUYV data to RGBA for preview display
     * @param yuyv YUYV frame data as byte array
     * @param rgba output RGBA buffer
     * @param w frame width
     * @param h frame height
     */
    public static void convertYuyvToRgba(byte[] yuyv, ByteBuffer rgba, int w, int h) {
        nConvertYuyvToRgba(yuyv, rgba, w, h);
    }

    /**
     * Convert NV12 data to RGBA for preview display
     * @param nv12 NV12 frame data (Y plane + interleaved UV plane) as byte array
     * @param rgba output RGBA buffer
     * @param w frame width
     * @param h frame height
     */
    public static void convertNV12ToRgba(byte[] nv12, ByteBuffer rgba, int w, int h) {
        nConvertNv12ToRgba(nv12, rgba, w, h);
    }

    /**
     * Close the NDI sender and release resources
     */
    public void close() {
        if (!closed && instancePointer != 0) {
            nSendDestroy(instancePointer);
            instancePointer = 0;
            closed = true;
        }
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            close();
        } finally {
            super.finalize();
        }
    }

    // Native methods
    private static native long nSendCreate(String sourceName);
    private static native void nSendDestroy(long pSend);
    private static native void nSendVideo(long pSend, int width, int height, ByteBuffer buffer);
    private static native void nSendVideoYUYV(long pSend, int w, int h, byte[] data);
    private static native void nSendVideoNV12(long pSend, int w, int h, byte[] data);
    private static native void nConvertYuyvToRgba(byte[] yuyv, ByteBuffer rgba, int w, int h);
    private static native void nConvertNv12ToRgba(byte[] nv12, ByteBuffer rgba, int w, int h);

    // ---------------------------------------
    /**
     * Simple tally state returned by {@link #getTally()}.
     * program = on air, preview = queued but not on air.
     */
    public static class Tally {
        public boolean program;
        public boolean preview;
    }

    /**
     * Query the current tally state from the native sender. Can be polled frequently.
     * @return Tally object or null if sender is closed/not available.
     */
    public Tally getTally() {
        if (closed || instancePointer == 0) return null;
        int mask = nSendGetTally(instancePointer);
        Tally t = new Tally();
        t.program = (mask & 1) != 0;
        t.preview = (mask & 2) != 0;
        return t;
    }

    private static native int nSendGetTally(long pSend);
}
