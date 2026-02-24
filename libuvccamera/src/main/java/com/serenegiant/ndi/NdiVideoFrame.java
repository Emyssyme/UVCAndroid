/*
 * NDI Video Frame wrapper for JNI communication
 * Based on https://github.com/WalkerKnapp/devolay (Apache 2.0)
 */

package com.serenegiant.ndi;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents an NDI video frame with metadata like resolution and color format.
 * This class wraps a native C++ NDI video frame structure.
 */
public class NdiVideoFrame implements AutoCloseable {

    final long instancePointer;

    AtomicReference<NdiFrameCleaner> allocatedBufferSource = new AtomicReference<>();

    /**
     * Create a new NDI video frame with default settings
     */
    public NdiVideoFrame() {
        this.instancePointer = createNewVideoFrameDefaultSettings();
    }

    /**
     * Get the x resolution (width) of the frame
     * @return frame width in pixels
     */
    public int getXResolution() {
        return getXRes(instancePointer);
    }

    /**
     * Get the y resolution (height) of the frame
     * @return frame height in pixels
     */
    public int getYResolution() {
        return getYRes(instancePointer);
    }

    /**
     * Get the raw frame data as ByteBuffer
     * @return the video frame data
     */
    public ByteBuffer getData() {
        return getData(instancePointer);
    }

    /**
     * Get the color format (FourCC type) of this frame
     * @return the FourCCType
     * @throws UnsupportedOperationException if format is not supported
     */
    public FourCCType getFourCCType() throws UnsupportedOperationException {
        try {
            return FourCCType.fromId(getFourCCTypeId());
        } catch (IllegalArgumentException e) {
            throw new UnsupportedOperationException(
                    "Unsupported FourCC type with id: " + getFourCCTypeId());
        }
    }

    /**
     * Get the FourCC type ID
     * @return the FourCC type ID as integer
     */
    public int getFourCCTypeId() {
        return getFourCCType(instancePointer);
    }

    /**
     * Get the frame rate numerator
     * @return frame rate numerator
     */
    public int getFrameRateN() {
        return getFrameRateN(instancePointer);
    }

    /**
     * Get the frame rate denominator
     * @return frame rate denominator
     */
    public int getFrameRateD() {
        return getFrameRateD(instancePointer);
    }

    /**
     * Free the allocated buffer
     */
    public void freeBuffer() {
        if (allocatedBufferSource.get() != null) {
            allocatedBufferSource.getAndSet(null).freeVideo(this);
        }
    }

    @Override
    public void close() {
        freeBuffer();
        destroyVideoFrame(instancePointer);
    }

    // Native methods
    private static native long createNewVideoFrameDefaultSettings();
    private static native void destroyVideoFrame(long pointer);
    private static native int getXRes(long pointer);
    private static native int getYRes(long pointer);
    private static native int getFourCCType(long pointer);
    private static native int getFrameRateN(long pointer);
    private static native int getFrameRateD(long pointer);
    private static native ByteBuffer getData(long pointer);
}
