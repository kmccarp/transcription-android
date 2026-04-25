package com.transcription.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.time.Duration;
import java.util.Base64;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class OllamaTranscriptionEngineTest {

    private MockWebServer server;
    private OllamaTranscriptionEngine engine;

    @Before public void start() throws IOException {
        server = new MockWebServer();
        server.start();
        OllamaConfig cfg = OllamaConfig.builder()
                .baseUrl(server.url("/").toString())
                .model("test-model")
                .prompt("Transcribe please.")
                .timeout(Duration.ofSeconds(5))
                .build();
        engine = new OllamaTranscriptionEngine(cfg);
    }

    @After public void stop() throws IOException {
        server.shutdown();
    }

    // ---- happy path --------------------------------------------------------

    @Test public void successResponse_returnsTrimmedText() throws Exception {
        server.enqueue(new MockResponse().setBody(
                "{\"response\":\"  hello there  \",\"done\":true}"));
        String text = engine.transcribe("audio".getBytes());
        assertEquals("hello there", text);
    }

    @Test public void requestUsesGenerateEndpoint_andPostsExpectedJson() throws Exception {
        server.enqueue(new MockResponse().setBody("{\"response\":\"ok\",\"done\":true}"));
        byte[] audio = new byte[]{1, 2, 3, 4, 5};
        engine.transcribe(audio);

        RecordedRequest rec = server.takeRequest();
        assertEquals("POST", rec.getMethod());
        assertEquals("/api/generate", rec.getPath());
        assertTrue("Content-Type should declare JSON: " + rec.getHeader("Content-Type"),
                rec.getHeader("Content-Type") != null
                        && rec.getHeader("Content-Type").startsWith("application/json"));

        String body = rec.getBody().readUtf8();
        // Verify the model + prompt + stream:false + base64 of audio are all present.
        assertTrue(body, body.contains("\"model\":\"test-model\""));
        assertTrue(body, body.contains("\"prompt\":\"Transcribe please.\""));
        assertTrue(body, body.contains("\"stream\":false"));
        String b64 = Base64.getEncoder().encodeToString(audio);
        assertTrue("body should embed base64 of audio: " + body,
                body.contains("\"images\":[\"" + b64 + "\"]"));
    }

    @Test public void canTranscribeBundledOpusFixture_withMockedServer() throws Exception {
        // End-to-end-ish: the real WhatsApp .opus from the user is read off
        // disk, fed through the engine, and we assert the request body carries
        // the correct base64 + the response is parsed back into our text.
        byte[] sample = Fixtures.read(Fixtures.SAMPLE_OPUS);
        assertTrue("fixture must be non-empty", sample.length > 0);

        String fakeTranscript =
                "Hi, this is the test voice note used in unit tests.";
        server.enqueue(new MockResponse().setBody(
                "{\"model\":\"test-model\",\"created_at\":\"2026-04-25T20:39:00Z\","
                        + "\"response\":\"" + fakeTranscript + "\",\"done\":true}"));

        String text = engine.transcribe(sample);
        assertEquals(fakeTranscript, text);

        RecordedRequest rec = server.takeRequest();
        String body = rec.getBody().readUtf8();
        String b64 = Base64.getEncoder().encodeToString(sample);
        assertTrue("body must include base64 of full opus fixture",
                body.contains("\"" + b64 + "\""));
    }

    @Test public void promptWithSpecialCharacters_isJsonEscapedInRequest() throws Exception {
        OllamaConfig cfg = engine.config().toBuilder()
                .prompt("line1\nline2 with \"quotes\"")
                .build();
        OllamaTranscriptionEngine custom = new OllamaTranscriptionEngine(cfg);
        String body = custom.buildRequestBody(new byte[]{0x42});
        assertTrue(body, body.contains("\"prompt\":\"line1\\nline2 with \\\"quotes\\\"\""));
    }

    // ---- error paths -------------------------------------------------------

    @Test public void emptyAudio_throwsBeforeNetwork() {
        TranscriptionException ex = assertThrows(TranscriptionException.class,
                () -> engine.transcribe(new byte[0]));
        assertEquals("Audio payload is empty", ex.getMessage());
        // Also: no request was ever made.
        assertEquals(0, server.getRequestCount());
    }

    @Test public void nullAudio_throws() {
        assertThrows(NullPointerException.class, () -> engine.transcribe(null));
    }

    @Test public void httpError_includesStatusAndOllamaErrorField() {
        server.enqueue(new MockResponse().setResponseCode(500)
                .setBody("{\"error\":\"model not found\"}"));
        TranscriptionException ex = assertThrows(TranscriptionException.class,
                () -> engine.transcribe(new byte[]{1}));
        assertTrue(ex.getMessage(), ex.getMessage().contains("HTTP 500"));
        assertTrue(ex.getMessage(), ex.getMessage().contains("model not found"));
    }

    @Test public void httpError_withPlainTextBody_isPropagated() {
        server.enqueue(new MockResponse().setResponseCode(502).setBody("bad gateway"));
        TranscriptionException ex = assertThrows(TranscriptionException.class,
                () -> engine.transcribe(new byte[]{1}));
        assertTrue(ex.getMessage(), ex.getMessage().contains("HTTP 502"));
        assertTrue(ex.getMessage(), ex.getMessage().contains("bad gateway"));
    }

    @Test public void httpError_withEmptyBody_doesNotAppendColon() {
        server.enqueue(new MockResponse().setResponseCode(503).setBody(""));
        TranscriptionException ex = assertThrows(TranscriptionException.class,
                () -> engine.transcribe(new byte[]{1}));
        assertEquals("Ollama returned HTTP 503", ex.getMessage());
    }

    @Test public void successCodeButErrorField_isTreatedAsFailure() {
        server.enqueue(new MockResponse().setBody("{\"error\":\"oops\"}"));
        TranscriptionException ex = assertThrows(TranscriptionException.class,
                () -> engine.transcribe(new byte[]{1}));
        assertEquals("Ollama error: oops", ex.getMessage());
    }

    @Test public void successCodeButMissingResponseField_isFailure() {
        server.enqueue(new MockResponse().setBody("{\"done\":true}"));
        TranscriptionException ex = assertThrows(TranscriptionException.class,
                () -> engine.transcribe(new byte[]{1}));
        assertTrue(ex.getMessage(),
                ex.getMessage().startsWith("Ollama response did not contain"));
    }

    // ---- low-level static parser -----------------------------------------

    @Test public void parseResponse_nullBody_treatedAsEmpty() {
        TranscriptionException ex = assertThrows(TranscriptionException.class,
                () -> OllamaTranscriptionEngine.parseResponse(500, null));
        assertEquals("Ollama returned HTTP 500", ex.getMessage());
    }

    @Test public void parseResponse_informationalStatusCode_isFailure() {
        // Cover the `status < 200` branch (separate from `status >= 300`).
        TranscriptionException ex = assertThrows(TranscriptionException.class,
                () -> OllamaTranscriptionEngine.parseResponse(199, "{}"));
        assertTrue(ex.getMessage(), ex.getMessage().contains("HTTP 199"));
    }

    // ---- transport failures ----------------------------------------------

    @Test public void urlOpenerThrowingIOException_propagatesToCaller() {
        OllamaTranscriptionEngine custom = new OllamaTranscriptionEngine(
                engine.config(),
                url -> { throw new IOException("nic on fire"); });
        IOException ex = assertThrows(IOException.class,
                () -> custom.transcribe(new byte[]{1}));
        assertTrue(ex.getMessage(), ex.getMessage().contains("nic on fire"));
    }

    @Test public void networkFailure_toUnreachableHost_propagatesIOException() throws Exception {
        // Point the engine at an unreachable port to exercise the live
        // HttpURLConnection error path.
        OllamaConfig cfg = OllamaConfig.builder()
                .baseUrl("http://127.0.0.1:1") // port 1 — guaranteed-refused on Linux
                .timeout(Duration.ofSeconds(1))
                .build();
        OllamaTranscriptionEngine custom = new OllamaTranscriptionEngine(cfg);
        try {
            custom.transcribe(new byte[]{1});
            fail("expected IOException");
        } catch (IOException expected) {
            // ConnectException / SocketTimeoutException — both fine.
        } catch (TranscriptionException te) {
            fail("expected IOException, got TranscriptionException: " + te);
        }
    }

    // ---- accessors --------------------------------------------------------

    @Test public void config_isExposed() {
        assertSame(engine.config(), engine.config());
        assertEquals("test-model", engine.config().model());
    }

    @Test public void publicConstructor_buildsDefaultClient() {
        // Just exercise the convenience ctor for coverage; we don't make a request.
        OllamaTranscriptionEngine c = new OllamaTranscriptionEngine(
                OllamaConfig.builder().build());
        assertNotNull(c.config());
    }
}
