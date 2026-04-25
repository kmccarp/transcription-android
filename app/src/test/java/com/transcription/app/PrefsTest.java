package com.transcription.app;

import static org.junit.Assert.assertEquals;

import android.content.SharedPreferences;
import androidx.preference.PreferenceManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.transcription.core.OllamaConfig;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class PrefsTest {

    @After
    public void clear() {
        PreferenceManager.getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext())
                .edit().clear().commit();
    }

    @Test public void emptyPrefs_returnDefaults() {
        OllamaConfig cfg = Prefs.load(ApplicationProvider.getApplicationContext());
        assertEquals(OllamaConfig.DEFAULT_BASE_URL, cfg.baseUrl());
        assertEquals(OllamaConfig.DEFAULT_MODEL,    cfg.model());
        assertEquals(OllamaConfig.DEFAULT_PROMPT,   cfg.prompt());
    }

    @Test public void setValues_areReadBack() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext());
        sp.edit()
                .putString(Prefs.KEY_BASE_URL, "http://my-host:11434")
                .putString(Prefs.KEY_MODEL,    "custom/whisper")
                .putString(Prefs.KEY_PROMPT,   "transcribe carefully")
                .commit();

        OllamaConfig cfg = Prefs.load(ApplicationProvider.getApplicationContext());
        assertEquals("http://my-host:11434", cfg.baseUrl());
        assertEquals("custom/whisper",       cfg.model());
        assertEquals("transcribe carefully", cfg.prompt());
    }

    @Test public void blankBaseUrl_fallsBackToDefault() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext());
        sp.edit().putString(Prefs.KEY_BASE_URL, "   ").commit();
        OllamaConfig cfg = Prefs.load(ApplicationProvider.getApplicationContext());
        assertEquals(OllamaConfig.DEFAULT_BASE_URL, cfg.baseUrl());
    }

    @Test public void baseUrlIsTrimmed() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext());
        sp.edit().putString(Prefs.KEY_BASE_URL, "  http://host:1234  ").commit();
        OllamaConfig cfg = Prefs.load(ApplicationProvider.getApplicationContext());
        assertEquals("http://host:1234", cfg.baseUrl());
    }

    @Test public void blankModel_fallsBackToDefault() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext());
        sp.edit().putString(Prefs.KEY_MODEL, "").commit();
        OllamaConfig cfg = Prefs.load(ApplicationProvider.getApplicationContext());
        assertEquals(OllamaConfig.DEFAULT_MODEL, cfg.model());
    }

    @Test public void emptyPrompt_isAcceptedAsExplicitOverride() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(
                ApplicationProvider.getApplicationContext());
        sp.edit().putString(Prefs.KEY_PROMPT, "").commit();
        OllamaConfig cfg = Prefs.load(ApplicationProvider.getApplicationContext());
        assertEquals("", cfg.prompt());
    }
}
