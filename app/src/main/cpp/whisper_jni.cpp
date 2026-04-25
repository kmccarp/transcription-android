// JNI bridge from com.transcription.app.WhisperJni to whisper.cpp.
//
// Three responsibilities only:
//   1. open a ggml model file and return an opaque context handle
//   2. transcribe a buffer of 16 kHz mono float PCM samples to UTF-8 text
//   3. release the context
//
// All audio decoding (Opus -> PCM, resampling) happens in Java/MediaCodec
// before we get here. Keep this file dumb.
#include <jni.h>
#include <android/log.h>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>

#include "whisper.h"

#define LOG_TAG "Transcription/JNI"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_transcription_app_WhisperJni_initContextFromPath(
        JNIEnv* env, jclass /*clazz*/, jstring jpath) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    if (!path) return 0L;

    whisper_context_params cparams = whisper_context_default_params();
    cparams.use_gpu = false;       // Android CPU only for now.
    cparams.flash_attn = false;

    whisper_context* ctx = whisper_init_from_file_with_params(path, cparams);
    env->ReleaseStringUTFChars(jpath, path);

    if (!ctx) {
        LOGE("whisper_init_from_file_with_params failed for %s", path);
        return 0L;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_transcription_app_WhisperJni_freeContext(
        JNIEnv* /*env*/, jclass /*clazz*/, jlong ctxHandle) {
    auto* ctx = reinterpret_cast<whisper_context*>(ctxHandle);
    if (ctx) whisper_free(ctx);
}

JNIEXPORT jstring JNICALL
Java_com_transcription_app_WhisperJni_transcribe(
        JNIEnv* env, jclass /*clazz*/,
        jlong ctxHandle,
        jfloatArray jsamples,
        jstring jlang,
        jint threads) {
    auto* ctx = reinterpret_cast<whisper_context*>(ctxHandle);
    if (!ctx) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"),
                      "Whisper context is null");
        return nullptr;
    }

    jsize n = env->GetArrayLength(jsamples);
    if (n <= 0) {
        return env->NewStringUTF("");
    }
    std::vector<float> samples(n);
    env->GetFloatArrayRegion(jsamples, 0, n, samples.data());

    whisper_full_params params = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    params.print_progress    = false;
    params.print_realtime    = false;
    params.print_special     = false;
    params.print_timestamps  = false;
    params.translate         = false;
    params.no_context        = true;
    params.single_segment    = false;
    params.suppress_blank    = true;
    params.suppress_nst      = true;
    params.n_threads         = threads > 0 ? threads : 4;

    std::string lang;
    if (jlang) {
        const char* l = env->GetStringUTFChars(jlang, nullptr);
        if (l) {
            lang = l;
            env->ReleaseStringUTFChars(jlang, l);
        }
    }
    if (!lang.empty() && lang != "auto") {
        params.language = lang.c_str();
    } else {
        // Auto-detect: leave language NULL.
        params.language = nullptr;
        params.detect_language = true;
    }

    LOGI("Running whisper_full on %d samples, threads=%d, lang=%s",
         n, params.n_threads, lang.empty() ? "auto" : lang.c_str());

    int rc = whisper_full(ctx, params, samples.data(), n);
    if (rc != 0) {
        char msg[128];
        std::snprintf(msg, sizeof(msg), "whisper_full failed: %d", rc);
        env->ThrowNew(env->FindClass("java/io/IOException"), msg);
        return nullptr;
    }

    std::string out;
    int segments = whisper_full_n_segments(ctx);
    out.reserve(segments * 64);
    for (int i = 0; i < segments; ++i) {
        const char* text = whisper_full_get_segment_text(ctx, i);
        if (text) out += text;
    }
    return env->NewStringUTF(out.c_str());
}

}  // extern "C"
