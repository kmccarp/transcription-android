package com.transcription.app;

import com.transcription.core.OllamaConfig;
import com.transcription.core.OllamaTranscriptionEngine;
import com.transcription.core.TranscriptionEngine;

/**
 * App-level factory for {@link TranscriptionEngine}s. Tests inject their own
 * factory via {@link MainActivity#setEngineFactory}.
 */
public final class Transcribers {

    /** Single-method functional interface — easy to swap for a fake in tests. */
    public interface Factory {
        TranscriptionEngine create(OllamaConfig config);
    }

    /** Default factory: builds a real {@link OllamaTranscriptionEngine}. */
    public static final Factory DEFAULT = OllamaTranscriptionEngine::new;

    private Transcribers() {}
}
