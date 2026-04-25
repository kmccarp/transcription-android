package com.transcription.app;

import androidx.annotation.NonNull;

/**
 * Thin Java wrapper around the {@code transcription_jni} native library.
 *
 * <p>All methods are package-private and exposed only via {@link WhisperLocalEngine}.
 * The native binary is built by CMake from {@code app/src/main/cpp/CMakeLists.txt}
 * and links against pinned whisper.cpp.
 */
final class WhisperJni {

    static {
        System.loadLibrary("transcription_jni");
    }

    private WhisperJni() {}

    /** Loads a ggml model from a filesystem path. Returns 0 on failure. */
    static native long initContextFromPath(@NonNull String modelPath);

    /** Frees a context previously returned from {@link #initContextFromPath}. */
    static native void freeContext(long ctx);

    /**
     * Transcribes 16 kHz mono float PCM samples (range -1..1) to UTF-8 text.
     *
     * @param ctx       a non-zero handle from {@link #initContextFromPath}.
     * @param samples   PCM samples.
     * @param language  ISO-639-1 code or {@code "auto"} for autodetect.
     * @param threads   number of CPU threads to use (0 = library default).
     * @throws java.io.IOException if whisper_full returns non-zero.
     */
    static native @NonNull String transcribe(long ctx,
                                             @NonNull float[] samples,
                                             @NonNull String language,
                                             int threads) throws java.io.IOException;
}
