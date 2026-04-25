package com.transcription.core;

import java.time.Duration;
import java.util.Objects;

/**
 * Immutable configuration for {@link OllamaTranscriptionEngine}.
 *
 * <p>Use {@link Builder} to construct. All fields have sensible defaults that
 * match a stock Ollama install on {@code localhost:11434} running a Whisper
 * variant such as {@code dimavz/whisper-tiny}.
 */
public final class OllamaConfig {

    public static final String DEFAULT_BASE_URL = "http://10.0.2.2:11434";
    public static final String DEFAULT_MODEL    = "dimavz/whisper-tiny";
    public static final String DEFAULT_PROMPT   = "Transcribe the audio.";
    public static final Duration DEFAULT_TIMEOUT = Duration.ofMinutes(2);

    private final String baseUrl;
    private final String model;
    private final String prompt;
    private final Duration timeout;

    private OllamaConfig(Builder b) {
        this.baseUrl = stripTrailingSlash(b.baseUrl);
        this.model   = b.model;
        this.prompt  = b.prompt;
        this.timeout = b.timeout;
    }

    public String baseUrl()   { return baseUrl; }
    public String model()     { return model; }
    public String prompt()    { return prompt; }
    public Duration timeout() { return timeout; }

    /** {@code <baseUrl>/api/generate} — full URL of the generate endpoint. */
    public String generateUrl() {
        return baseUrl + "/api/generate";
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .baseUrl(baseUrl)
                .model(model)
                .prompt(prompt)
                .timeout(timeout);
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof OllamaConfig)) return false;
        OllamaConfig that = (OllamaConfig) o;
        return baseUrl.equals(that.baseUrl)
                && model.equals(that.model)
                && prompt.equals(that.prompt)
                && timeout.equals(that.timeout);
    }

    @Override
    public int hashCode() {
        return Objects.hash(baseUrl, model, prompt, timeout);
    }

    @Override
    public String toString() {
        return "OllamaConfig{baseUrl=" + baseUrl
                + ", model=" + model
                + ", prompt='" + prompt + '\''
                + ", timeout=" + timeout + '}';
    }

    /** Mutable builder. Validation happens in {@link #build()}. */
    public static final class Builder {
        private String baseUrl = DEFAULT_BASE_URL;
        private String model   = DEFAULT_MODEL;
        private String prompt  = DEFAULT_PROMPT;
        private Duration timeout = DEFAULT_TIMEOUT;

        public Builder baseUrl(String v) { this.baseUrl = v; return this; }
        public Builder model(String v)   { this.model = v;   return this; }
        public Builder prompt(String v)  { this.prompt = v;  return this; }
        public Builder timeout(Duration v) { this.timeout = v; return this; }

        public OllamaConfig build() {
            if (baseUrl == null || baseUrl.isBlank()) {
                throw new IllegalArgumentException("baseUrl must not be blank");
            }
            if (!(baseUrl.startsWith("http://") || baseUrl.startsWith("https://"))) {
                throw new IllegalArgumentException(
                        "baseUrl must start with http:// or https:// (got " + baseUrl + ")");
            }
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("model must not be blank");
            }
            if (prompt == null) {
                throw new IllegalArgumentException("prompt must not be null");
            }
            if (timeout == null || timeout.isZero() || timeout.isNegative()) {
                throw new IllegalArgumentException("timeout must be positive");
            }
            return new OllamaConfig(this);
        }
    }
}
