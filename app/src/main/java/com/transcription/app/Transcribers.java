package com.transcription.app;

import android.content.Context;
import com.transcription.core.OllamaTranscriptionEngine;
import com.transcription.core.TranscriptionEngine;

/**
 * App-level factory for {@link TranscriptionEngine}s. Tests inject their own
 * factory via {@link MainActivity#setEngineFactory}.
 *
 * <p>Two real implementations exist:
 *
 * <ul>
 *   <li>{@link WhisperLocalEngine} — on-device whisper.cpp + bundled model.
 *       This is the default; the app works fully offline.</li>
 *   <li>{@link OllamaTranscriptionEngine} — calls a self-hosted Ollama server.
 *       Optional, opt-in via Settings for users who already run Ollama on
 *       their LAN.</li>
 * </ul>
 */
public final class Transcribers {

    /** Single-method functional interface — easy to swap for a fake in tests. */
    public interface Factory {
        TranscriptionEngine create(Context ctx);
    }

    /** Default: on-device whisper.cpp. */
    public static final Factory DEFAULT = ctx -> {
        if ("ollama".equals(Prefs.engineKind(ctx))) {
            return new OllamaTranscriptionEngine(Prefs.loadOllama(ctx));
        }
        return new WhisperLocalEngine(ctx, Prefs.language(ctx), Prefs.threads(ctx));
    };

    private Transcribers() {}
}
