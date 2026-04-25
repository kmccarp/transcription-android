package com.transcription.core;

/** Thrown when an engine returned a response we couldn't turn into text. */
public class TranscriptionException extends Exception {

    private static final long serialVersionUID = 1L;

    public TranscriptionException(String message) {
        super(message);
    }

    public TranscriptionException(String message, Throwable cause) {
        super(message, cause);
    }
}
