package com.transcription.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class TranscriptionExceptionTest {

    @Test public void messageOnly() {
        TranscriptionException e = new TranscriptionException("boom");
        assertEquals("boom", e.getMessage());
        assertNull(e.getCause());
    }

    @Test public void messageAndCause() {
        Throwable cause = new RuntimeException("nested");
        TranscriptionException e = new TranscriptionException("boom", cause);
        assertEquals("boom", e.getMessage());
        assertSame(cause, e.getCause());
    }
}
