package com.transcription.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/**
 * Reads audio from an {@link InputStream} into a {@code byte[]} with a hard
 * size cap so a malicious or accidental huge file doesn't OOM the app.
 *
 * <p>Lives in {@code :core} (and not in the Android module) precisely so that
 * the cap-and-stream behaviour is unit-testable on the JVM.
 */
public final class AudioBytes {

    /** Hard upper bound: WhatsApp voice notes are typically &lt;1MB; cap at 25MB. */
    public static final int DEFAULT_MAX_BYTES = 25 * 1024 * 1024;

    private AudioBytes() {}

    public static byte[] readAll(InputStream in) throws IOException {
        return readAll(in, DEFAULT_MAX_BYTES);
    }

    public static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        Objects.requireNonNull(in, "in");
        if (maxBytes <= 0) {
            throw new IllegalArgumentException("maxBytes must be > 0 (was " + maxBytes + ")");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(maxBytes, 64 * 1024));
        byte[] buf = new byte[8 * 1024];
        int total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > maxBytes) {
                throw new IOException(
                        "Audio file exceeds maximum size of " + maxBytes + " bytes");
            }
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }
}
