package com.transcription.core;

import java.io.IOException;
import java.io.InputStream;

/** Test-only helpers for loading bundled fixtures. */
final class Fixtures {

    /** Real WhatsApp push-to-talk Opus voice note bundled as a test resource. */
    static final String SAMPLE_OPUS = "/fixtures/sample-whatsapp-ptt.opus";

    private Fixtures() {}

    static byte[] read(String resourcePath) {
        try (InputStream in = Fixtures.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IllegalStateException("Missing test resource: " + resourcePath);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read " + resourcePath, e);
        }
    }
}
