package com.transcription.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;

/**
 * {@link TranscriptionEngine} that calls a local <a href="https://ollama.com">Ollama</a>
 * server's {@code /api/generate} endpoint.
 *
 * <p>The audio is base64-encoded and passed in the {@code images} array, which
 * is the same channel Ollama uses for any binary input to a multimodal model.
 * For Whisper-style audio models published on Ollama (e.g.
 * {@code dimavz/whisper-tiny}, {@code karanchopda333/whisper}) this is the
 * documented way to ship audio bytes; the model handles ffmpeg-style decoding
 * of Opus/Ogg server-side.
 *
 * <p>HTTP I/O uses {@link HttpURLConnection} so the same code runs on both
 * the JVM (for unit tests) and on Android. The newer {@code java.net.http}
 * stack would be cleaner but Android does not ship it.
 *
 * <p>This class is stateless after construction; you can share one instance
 * across calls. It does not retry — callers wrap retries in their own policy.
 */
public final class OllamaTranscriptionEngine implements TranscriptionEngine {

    /** Read up to this many bytes from a single HTTP response. */
    private static final int MAX_RESPONSE_BYTES = 4 * 1024 * 1024;

    private final OllamaConfig config;
    private final UrlOpener urlOpener;

    /** Builds an engine that talks to a real network. */
    public OllamaTranscriptionEngine(OllamaConfig config) {
        this(config, url -> (HttpURLConnection) new URL(url).openConnection());
    }

    /** Visible for tests: lets callers inject a fake URL opener. */
    OllamaTranscriptionEngine(OllamaConfig config, UrlOpener urlOpener) {
        this.config = Objects.requireNonNull(config, "config");
        this.urlOpener = Objects.requireNonNull(urlOpener, "urlOpener");
    }

    public OllamaConfig config() {
        return config;
    }

    @Override
    public String transcribe(byte[] audio) throws IOException, TranscriptionException {
        Objects.requireNonNull(audio, "audio");
        if (audio.length == 0) {
            throw new TranscriptionException("Audio payload is empty");
        }

        byte[] body = buildRequestBody(audio).getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = urlOpener.open(config.generateUrl());
        try {
            int timeoutMs = (int) Math.min(Integer.MAX_VALUE, config.timeout().toMillis());
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("Content-Length", Integer.toString(body.length));
            conn.setDoOutput(true);
            conn.setUseCaches(false);

            try (OutputStream out = conn.getOutputStream()) {
                out.write(body);
            }

            int status = conn.getResponseCode();
            String responseBody = readResponseBody(conn, status);
            return parseResponse(status, responseBody);
        } finally {
            conn.disconnect();
        }
    }

    /** Builds the {@code /api/generate} request body. Package-visible for tests. */
    String buildRequestBody(byte[] audio) {
        String b64 = Base64.getEncoder().encodeToString(audio);
        return "{"
                + "\"model\":\""   + Json.escape(config.model())  + "\","
                + "\"prompt\":\""  + Json.escape(config.prompt()) + "\","
                + "\"stream\":false,"
                + "\"images\":[\"" + b64 + "\"]"
                + "}";
    }

    /** Parses an Ollama response. Package-visible for tests. */
    static String parseResponse(int status, String body) throws TranscriptionException {
        if (body == null) body = "";

        if (status < 200 || status >= 300) {
            String err = Json.extractString(body, "error");
            throw new TranscriptionException(
                    "Ollama returned HTTP " + status
                            + (err != null ? ": " + err : (body.isEmpty() ? "" : ": " + body)));
        }

        // Some Ollama errors come back with HTTP 200 + an "error" field.
        String err = Json.extractString(body, "error");
        if (err != null) {
            throw new TranscriptionException("Ollama error: " + err);
        }

        String response = Json.extractString(body, "response");
        if (response == null) {
            throw new TranscriptionException(
                    "Ollama response did not contain a \"response\" field: " + body);
        }
        return response.trim();
    }

    private static String readResponseBody(HttpURLConnection conn, int status) {
        // For 4xx/5xx, getInputStream() throws — read getErrorStream() instead.
        InputStream stream;
        try {
            stream = (status >= 200 && status < 400)
                    ? conn.getInputStream()
                    : conn.getErrorStream();
        } catch (IOException ioe) {
            stream = conn.getErrorStream();
        }
        if (stream == null) return "";
        try (InputStream in = stream) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8 * 1024];
            int total = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                total += n;
                if (total > MAX_RESPONSE_BYTES) {
                    // Truncate; we'll still try to parse what we have.
                    out.write(buf, 0, n);
                    break;
                }
                out.write(buf, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        } catch (IOException ioe) {
            return "";
        }
    }

    /** Test seam for opening a URL. */
    interface UrlOpener {
        HttpURLConnection open(String url) throws IOException;
    }
}
