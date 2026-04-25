package com.transcription.core;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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
 * <p>This class is stateless after construction; you can share one instance
 * across calls. It does not retry — callers wrap retries in their own policy.
 */
public final class OllamaTranscriptionEngine implements TranscriptionEngine {

    private final OllamaConfig config;
    private final HttpClient httpClient;

    /** Builds an engine with a fresh {@link HttpClient}. */
    public OllamaTranscriptionEngine(OllamaConfig config) {
        this(config, HttpClient.newBuilder().build());
    }

    /** Visible for tests: lets callers inject a pre-configured {@link HttpClient}. */
    OllamaTranscriptionEngine(OllamaConfig config, HttpClient httpClient) {
        this.config = Objects.requireNonNull(config, "config");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
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

        String body = buildRequestBody(audio);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(config.generateUrl()))
                .timeout(config.timeout())
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for Ollama", ie);
        }

        return parseResponse(response.statusCode(), response.body());
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
}
