package com.transcription.core;

import java.io.IOException;

/**
 * Strategy for turning a chunk of audio bytes into text.
 *
 * <p>Implementations must be safe to call from a background thread but are not
 * required to be thread-safe across multiple concurrent invocations.
 */
public interface TranscriptionEngine {

    /**
     * Transcribes the given audio.
     *
     * @param audio  raw bytes of an audio file (e.g. an Opus/Ogg blob shared
     *               from WhatsApp). The implementation is responsible for any
     *               server-side decoding.
     * @return       the recognised text (never {@code null}; may be empty).
     * @throws IOException                  on network/IO failure.
     * @throws TranscriptionException       if the engine returned a structured
     *                                      error or an unparseable response.
     */
    String transcribe(byte[] audio) throws IOException, TranscriptionException;
}
