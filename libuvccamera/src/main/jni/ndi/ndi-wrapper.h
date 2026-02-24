#ifndef NDI_WRAPPER_H
#define NDI_WRAPPER_H

#include <Processing.NDI.Lib.h>
#include <jni.h>
#include <vector>
#include <string>
#include <android/log.h>

#define LOG_TAG "UVCNdiWrapper"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

// Helper functions
jstring stringToJString(JNIEnv* env, const std::string& str);
jboolean boolToJBoolean(bool value);

#endif //NDI_WRAPPER_H
