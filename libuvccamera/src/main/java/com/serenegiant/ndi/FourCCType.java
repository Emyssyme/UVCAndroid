/*
 * FourCC type enumeration for NDI video formats
 * Based on https://github.com/WalkerKnapp/devolay (Apache 2.0)
 */

package com.serenegiant.ndi;

/**
 * Enumeration of supported color formats (FourCC types) for NDI transmission
 */
public enum FourCCType {
    /**
     * UYVY - 16-bit format (8 bits per channel, no alpha)
     */
    UYVY(0x59565955),
    
    /**
     * RGBA - 32-bit format (8 bits per channel with alpha)
     */
    RGBA(0x41424752),
    
    /**
     * BGRA - 32-bit format (8 bits per channel with alpha)
     */
    BGRA(0x41524742),
    
    /**
     * NV12 - 12-bit format commonly used by 4K capture cards
     */
    NV12(0x3231564E),
    
    /**
     * YUYV - 16-bit format (8 bits per channel, no alpha)
     */
    YUYV(0x56595559);

    public final int id;

    FourCCType(int id) {
        this.id = id;
    }

    /**
     * Get FourCC type by its ID
     * @param id the FourCC ID
     * @return the corresponding FourCCType
     * @throws IllegalArgumentException if ID is not supported
     */
    public static FourCCType fromId(int id) {
        for (FourCCType type : FourCCType.values()) {
            if (type.id == id) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unsupported FourCC type: " + id);
    }
}
