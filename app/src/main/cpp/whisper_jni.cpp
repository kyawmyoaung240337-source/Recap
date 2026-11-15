#include <jni.h>
#include <string>
#include "whisper.h"

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_kgrecap_WhisperBridge_nativeInit(JNIEnv *env, jobject, jstring path) {
    const char *p = env->GetStringUTFChars(path, nullptr);
    whisper_context_params cp = whisper_context_default_params();
    cp.use_gpu = false;
    whisper_context *ctx = whisper_init_from_file_with_params(p, cp);
    env->ReleaseStringUTFChars(path, p);
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT void JNICALL
Java_com_kgrecap_WhisperBridge_nativeFree(JNIEnv *, jobject, jlong ptr) {
    if (ptr != 0) whisper_free(reinterpret_cast<whisper_context *>(ptr));
}

JNIEXPORT jstring JNICALL
Java_com_kgrecap_WhisperBridge_nativeTranscribe(JNIEnv *env, jobject, jlong ptr,
                                                jfloatArray audio, jstring lang, jint threads) {
    auto *ctx = reinterpret_cast<whisper_context *>(ptr);
    if (ctx == nullptr) return env->NewStringUTF("");
    jsize n = env->GetArrayLength(audio);
    jfloat *data = env->GetFloatArrayElements(audio, nullptr);
    const char *l = env->GetStringUTFChars(lang, nullptr);

    whisper_full_params wp = whisper_full_default_params(WHISPER_SAMPLING_GREEDY);
    wp.print_realtime = false;
    wp.print_progress = false;
    wp.print_timestamps = false;
    wp.print_special = false;
    wp.translate = false;
    wp.no_context = true;
    wp.n_threads = threads;
    wp.language = l;
    wp.detect_language = (std::string(l) == "auto");

    std::string out;
    if (whisper_full(ctx, wp, data, n) == 0) {
        int segs = whisper_full_n_segments(ctx);
        for (int i = 0; i < segs; i++) out += whisper_full_get_segment_text(ctx, i);
    }
    env->ReleaseStringUTFChars(lang, l);
    env->ReleaseFloatArrayElements(audio, data, JNI_ABORT);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT jstring JNICALL
Java_com_kgrecap_WhisperBridge_nativeSystemInfo(JNIEnv *env, jobject) {
    return env->NewStringUTF(whisper_print_system_info());
}

}
