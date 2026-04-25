package com.transcription.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.fail;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import org.junit.Test;

public class JsonTest {

    // ---- escape ------------------------------------------------------------

    @Test public void escape_noSpecials_returnsInputUnchanged() {
        assertEquals("hello world", Json.escape("hello world"));
    }

    @Test public void escape_handlesAllStandardEscapes() {
        // \" \\ \b \f \n \r \t  — every documented escape, all in one input.
        assertEquals("a\\\"b\\\\c\\bd\\fe\\nf\\rg\\th",
                Json.escape("a\"b\\c\bd\fe\nf\rg\th"));
    }

    @Test public void escape_handlesControlChars_belowSpace() {
        // Control chars below 0x20 must be hex-encoded.
        assertEquals("\\u0001", Json.escape(""));
        assertEquals("\\u001f", Json.escape(""));
    }

    @Test public void escape_emptyString_returnsEmpty() {
        assertEquals("", Json.escape(""));
    }

    @Test public void escape_unicode_passesThroughUnescaped() {
        assertEquals("héllo 🎙️", Json.escape("héllo 🎙️"));
    }

    // ---- extractString -----------------------------------------------------

    @Test public void extractString_simpleField() {
        assertEquals("hi", Json.extractString("{\"response\":\"hi\"}", "response"));
    }

    @Test public void extractString_withWhitespaceAroundColon() {
        assertEquals("hi", Json.extractString("{\"response\"  :  \"hi\"}", "response"));
    }

    @Test public void extractString_skipsEarlierFalseMatch() {
        // First "response" is a value, not a key.
        String json = "{\"x\":\"response\",\"response\":\"actual\"}";
        assertEquals("actual", Json.extractString(json, "response"));
    }

    @Test public void extractString_returnsNullWhenAbsent() {
        assertNull(Json.extractString("{\"other\":\"x\"}", "response"));
    }

    @Test public void extractString_nonStringValue_returnsNull() {
        // "done":true — value is a boolean, we expect null (we only handle strings).
        assertNull(Json.extractString("{\"done\":true}", "done"));
    }

    @Test public void extractString_decodesAllEscapes() {
        // Covers all 8 char escapes plus a 4-hex BMP code point.
        String json = "{\"r\":\"a\\\"b\\\\c\\/d\\be\\ff\\ng\\rh\\ti\\u00e9\"}";
        String expected = "a\"b\\c/d\be\ff\ng\rh\tié";
        assertEquals(expected, Json.extractString(json, "r"));
    }

    @Test public void extractString_truncatedEscape_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Json.extractString("{\"r\":\"abc\\", "r"));
        assertEquals("Truncated escape at end of JSON", ex.getMessage());
    }

    @Test public void extractString_truncatedUnicodeEscape_throws() {
        // Non-hex chars in the four positions after the backslash-u (the
        // closing quote and brace land in the would-be hex digits) — caught
        // via NumberFormatException re-throw.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Json.extractString("{\"r\":\"\\u00\"}", "r"));
        assertEquals("Truncated \\u escape", ex.getMessage());
    }

    @Test public void extractString_truncatedUnicodeEscape_atEndOfInput_throws() {
        // Fewer than 4 chars total following the backslash-u: caught by the
        // explicit length check before parseInt.
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Json.extractString("{\"r\":\"\\u00", "r"));
        assertEquals("Truncated \\u escape", ex.getMessage());
    }

    @Test public void extractString_unknownEscape_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Json.extractString("{\"r\":\"\\x\"}", "r"));
        assertEquals("Unknown escape \\x", ex.getMessage());
    }

    @Test public void extractString_unterminatedString_throws() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> Json.extractString("{\"r\":\"unterminated", "r"));
        assertEquals("Unterminated string for field \"r\"", ex.getMessage());
    }

    @Test public void extractString_emptyValue_returnsEmpty() {
        assertEquals("", Json.extractString("{\"response\":\"\"}", "response"));
    }

    @Test public void extractString_keyAtEndWithoutColon_returnsNull() {
        assertNull(Json.extractString("{\"response\"", "response"));
    }

    @Test public void extractString_keyWithoutValueQuote_returnsNull() {
        // `:1` instead of `:"..."` — first match has no string value, no later match.
        assertNull(Json.extractString("{\"response\":1}", "response"));
    }

    // ---- private constructor ----------------------------------------------

    @Test public void privateConstructor_isInvocableForCoverage() throws Exception {
        Constructor<Json> c = Json.class.getDeclaredConstructor();
        c.setAccessible(true);
        try {
            c.newInstance();
        } catch (InvocationTargetException e) {
            fail("Json() should not throw: " + e.getCause());
        }
    }
}
