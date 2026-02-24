/*
 * NDI Frame Cleaner interface - handles cleanup of video frames
 * Based on https://github.com/WalkerKnapp/devolay (Apache 2.0)
 */

package com.serenegiant.ndi;

/**
 * Interface for handling NDI frame cleanup and buffer deallocation
 */
public interface NdiFrameCleaner {
    /**
     * Free a video frame buffer
     * @param frame the video frame to free
     */
    void freeVideo(NdiVideoFrame frame);
}
