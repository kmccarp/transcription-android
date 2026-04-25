package com.transcription.app;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.transcription.core.OllamaConfig;

/**
 * Reads user-configurable settings (engine choice, language, threads, and
 * the Ollama backup config) out of the default {@link SharedPreferences}.
 *
 * <p>Kept in its own class so it's straightforward to test with Robolectric
 * and easy to mock for the activities.
 */
public final class Prefs {

    public static final String KEY_ENGINE   = "engine";          // "local" | "ollama"
    public static final String KEY_LANGUAGE = "language";        // ISO-639-1 or "auto"
    public static final String KEY_THREADS  = "threads";         // integer string

    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_MODEL    = "model";
    public static final String KEY_PROMPT   = "prompt";

    public static final String DEFAULT_LANGUAGE = "auto";
    public static final int    DEFAULT_THREADS  = 4;

    private Prefs() {}

    private static SharedPreferences sp(Context ctx) {
        return PreferenceManager.getDefaultSharedPreferences(ctx.getApplicationContext());
    }

    /** "local" by default. Either "local" (on-device whisper) or "ollama". */
    public static String engineKind(Context ctx) {
        String v = sp(ctx).getString(KEY_ENGINE, "local");
        return ("ollama".equals(v)) ? "ollama" : "local";
    }

    public static String language(Context ctx) {
        String v = sp(ctx).getString(KEY_LANGUAGE, DEFAULT_LANGUAGE);
        return (v == null || v.isBlank()) ? DEFAULT_LANGUAGE : v.trim();
    }

    public static int threads(Context ctx) {
        String raw = sp(ctx).getString(KEY_THREADS, Integer.toString(DEFAULT_THREADS));
        if (raw == null) return DEFAULT_THREADS;
        try {
            int n = Integer.parseInt(raw.trim());
            return (n <= 0 || n > 32) ? DEFAULT_THREADS : n;
        } catch (NumberFormatException nfe) {
            return DEFAULT_THREADS;
        }
    }

    public static OllamaConfig loadOllama(Context ctx) {
        SharedPreferences s = sp(ctx);
        OllamaConfig.Builder b = OllamaConfig.builder();
        String baseUrl = s.getString(KEY_BASE_URL, null);
        if (baseUrl != null && !baseUrl.isBlank()) b.baseUrl(baseUrl.trim());
        String model = s.getString(KEY_MODEL, null);
        if (model != null && !model.isBlank()) b.model(model.trim());
        String prompt = s.getString(KEY_PROMPT, null);
        if (prompt != null) b.prompt(prompt);
        return b.build();
    }

    /** Backwards-compatible alias used by Diagnostics.build. */
    public static OllamaConfig load(Context ctx) {
        return loadOllama(ctx);
    }
}
