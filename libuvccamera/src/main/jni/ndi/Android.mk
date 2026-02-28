# NDI Wrapper Makefile for Android NDK Build System

LOCAL_PATH := $(call my-dir)

# First, define prebuilt NDI library
include $(CLEAR_VARS)
LOCAL_MODULE := ndi
LOCAL_SRC_FILES := ../../jniLibs/$(TARGET_ARCH_ABI)/libndi.so
include $(PREBUILT_SHARED_LIBRARY)

# Now build the NDI wrapper that links to prebuilt library
include $(CLEAR_VARS)

LOCAL_MODULE := uvc-ndi-wrapper

LOCAL_CFLAGS := -std=c++17 -fPIC -DANDROID_NDK -Wall -Wextra

LOCAL_SRC_FILES := \
	ndi.cpp \
	ndi-sender.cpp \
	color-conversion.cpp

LOCAL_C_INCLUDES := \
	$(LOCAL_PATH)/include

LOCAL_SHARED_LIBRARIES := ndi

LOCAL_LDLIBS := -llog -lc++_shared

LOCAL_LDFLAGS := -Wl,-z,max-page-size=0x4000

include $(BUILD_SHARED_LIBRARY)
