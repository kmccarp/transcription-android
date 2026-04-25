package com.transcription.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import org.junit.Test;

public class OllamaConfigTest {

    @Test public void defaults_areSane() {
        OllamaConfig c = OllamaConfig.builder().build();
        assertEquals("http://10.0.2.2:11434", c.baseUrl());
        assertEquals("dimavz/whisper-tiny",   c.model());
        assertEquals("Transcribe the audio.", c.prompt());
        assertEquals(Duration.ofMinutes(2),   c.timeout());
        assertEquals("http://10.0.2.2:11434/api/generate", c.generateUrl());
    }

    @Test public void builder_overridesAreApplied() {
        OllamaConfig c = OllamaConfig.builder()
                .baseUrl("http://example.com:99")
                .model("custom")
                .prompt("p")
                .timeout(Duration.ofSeconds(7))
                .build();
        assertEquals("http://example.com:99",         c.baseUrl());
        assertEquals("custom",                        c.model());
        assertEquals("p",                             c.prompt());
        assertEquals(Duration.ofSeconds(7),           c.timeout());
        assertEquals("http://example.com:99/api/generate", c.generateUrl());
    }

    @Test public void builder_stripsTrailingSlash() {
        OllamaConfig c = OllamaConfig.builder().baseUrl("http://x.test/").build();
        assertEquals("http://x.test", c.baseUrl());
        assertEquals("http://x.test/api/generate", c.generateUrl());
    }

    @Test public void toBuilder_roundTrips() {
        OllamaConfig original = OllamaConfig.builder()
                .baseUrl("http://x.test")
                .model("m")
                .prompt("p")
                .timeout(Duration.ofSeconds(5))
                .build();
        OllamaConfig copy = original.toBuilder().build();
        assertEquals(original, copy);
        assertEquals(original.hashCode(), copy.hashCode());
    }

    @Test public void equals_andHashCode_areConsistent() {
        OllamaConfig a = OllamaConfig.builder().build();
        OllamaConfig b = OllamaConfig.builder().build();
        assertEquals(a, a);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, "not a config");
        assertNotEquals(a, null);
    }

    @Test public void equals_returnsFalseWhenAnySingleFieldDiffers() {
        OllamaConfig base = OllamaConfig.builder().build();
        // Each variation differs in exactly one field — covers every short-
        // circuit branch of the conjunction in OllamaConfig.equals.
        assertNotEquals(base, base.toBuilder().baseUrl("http://other").build());
        assertNotEquals(base, base.toBuilder().model("other").build());
        assertNotEquals(base, base.toBuilder().prompt("other").build());
        assertNotEquals(base, base.toBuilder().timeout(Duration.ofSeconds(99)).build());
    }

    @Test public void toString_containsKeyFields() {
        String s = OllamaConfig.builder().build().toString();
        assertTrue(s, s.contains("baseUrl="));
        assertTrue(s, s.contains("model="));
        assertTrue(s, s.contains("timeout="));
    }

    @Test public void blankBaseUrl_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> OllamaConfig.builder().baseUrl(" ").build());
    }

    @Test public void nullBaseUrl_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> OllamaConfig.builder().baseUrl(null).build());
    }

    @Test public void nonHttpScheme_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> OllamaConfig.builder().baseUrl("ftp://x").build());
        assertNotNull(ex.getMessage());
    }

    @Test public void httpsBaseUrl_isAccepted() {
        OllamaConfig c = OllamaConfig.builder().baseUrl("https://x").build();
        assertEquals("https://x", c.baseUrl());
    }

    @Test public void blankModel_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> OllamaConfig.builder().model(" ").build());
    }

    @Test public void nullModel_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> OllamaConfig.builder().model(null).build());
    }

    @Test public void nullPrompt_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> OllamaConfig.builder().prompt(null).build());
    }

    @Test public void emptyPrompt_isAccepted() {
        OllamaConfig c = OllamaConfig.builder().prompt("").build();
        assertEquals("", c.prompt());
    }

    @Test public void zeroTimeout_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> OllamaConfig.builder().timeout(Duration.ZERO).build());
    }

    @Test public void negativeTimeout_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> OllamaConfig.builder().timeout(Duration.ofSeconds(-1)).build());
    }

    @Test public void nullTimeout_throws() {
        assertThrows(IllegalArgumentException.class,
                () -> OllamaConfig.builder().timeout(null).build());
    }
}
