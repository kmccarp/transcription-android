package com.transcription.app;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import com.transcription.core.OllamaConfig;

/**
 * Reads user-configurable Ollama settings out of the default
 * {@link SharedPreferences} and turns them into an {@link OllamaConfig}.
 *
 * <p>Kept in its own class so it's straightforward to test with Robolectric
 * and easy to mock for the activities.
 */
public final class Prefs {

    public static final String KEY_BASE_URL = "base_url";
    public static final String KEY_MODEL    = "model";
    public static final String KEY_PROMPT   = "prompt";

    private Prefs() {}

    public static OllamaConfig load(Context ctx) {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                ctx.getApplicationContext());
        OllamaConfig.Builder b = OllamaConfig.builder();
        String baseUrl = sp.getString(KEY_BASE_URL, null);
        if (baseUrl != null && !baseUrl.isBlank()) {
            b.baseUrl(baseUrl.trim());
        }
        String model = sp.getString(KEY_MODEL, null);
        if (model != null && !model.isBlank()) {
            b.model(model.trim());
        }
        String prompt = sp.getString(KEY_PROMPT, null);
        if (prompt != null) {
            b.prompt(prompt);
        }
        return b.build();
    }
}
