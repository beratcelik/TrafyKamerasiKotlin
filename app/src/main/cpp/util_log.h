#pragma once
#include <android/log.h>

#define TRAFY_LOG_TAG "Trafy.Vision"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TRAFY_LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TRAFY_LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TRAFY_LOG_TAG, __VA_ARGS__)
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, TRAFY_LOG_TAG, __VA_ARGS__)
