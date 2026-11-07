#include <jni.h>
#include <string>
#include <algorithm>
#include <cmath>
#include <android/log.h>

#define LOG_TAG "AudioProcessorGT6T"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT void JNICALL
Java_com_example_wifiaudiospeaker_MainActivity_nativeProcessAudio(JNIEnv* env, jclass clazz,
                                                                  jshortArray input, jshortArray output,
                                                                  jint samples, jfloat volume) {
    jshort* in = env->GetShortArrayElements(input, nullptr);
    jshort* out = env->GetShortArrayElements(output, nullptr);

    if (in && out && samples > 0) {
        // Float convert with soft limiting
        const float pre = std::clamp(volume, 0.0f, 1.0f) * 0.7071f; // include −3 dB safety
        const float thr = 0.9f;

        for (int i = 0; i < samples; ++i) {
            float x = (float) in[i] / 32768.0f;
            x *= pre;
            float ax = std::fabs(x);
            if (ax > thr) {
                float sign = (x >= 0.0f) ? 1.0f : -1.0f;
                x = sign * (thr + std::tanh((ax - thr) / (1.0f - thr)) * (1.0f - thr));
            }
            x = std::max(-1.0f, std::min(1.0f, x));
            out[i] = (jshort) lrintf(x * 32767.0f);

            // 🚨 REMOVED: memcpy was HERE inside the loop - this was the bug!
        }
        LOGI("Soft-limited audio processing done");
    }

    env->ReleaseShortArrayElements(input, in, JNI_ABORT);
    env->ReleaseShortArrayElements(output, out, 0);
}

} // extern "C"