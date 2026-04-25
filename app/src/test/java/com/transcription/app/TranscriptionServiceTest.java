package com.transcription.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.transcription.core.TranscriptionEngine;
import com.transcription.core.TranscriptionException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.android.controller.ServiceController;

@RunWith(AndroidJUnit4.class)
public class TranscriptionServiceTest {

    private TranscriptionStore store;
    private File audioDir;
    private ServiceController<TranscriptionService> controller;
    private TranscriptionService service;

    @Before public void setUp() {
        store = new TranscriptionStore(ApplicationProvider.getApplicationContext());
        store.clear();
        store.awaitIdle();
        audioDir = new File(
                ApplicationProvider.<android.content.Context>getApplicationContext()
                        .getFilesDir(), "audio");
        //noinspection ResultOfMethodCallIgnored
        audioDir.mkdirs();

        controller = Robolectric.buildService(TranscriptionService.class).create();
        service    = controller.get();
        service.setStoreForTest(store);
    }

    @After public void tearDown() {
        TranscriptionService.setFactory(null);
        if (controller != null) controller.destroy();
    }

    private TranscriptionStore.Item enqueue(String label, String content) throws IOException {
        File f = new File(audioDir, label + ".bin");
        try (FileOutputStream fos = new FileOutputStream(f)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }
        TranscriptionStore.Item it = TranscriptionStore.Item.pending("local", "auto", label)
                .audioPath("audio/" + f.getName())
                .build();
        store.add(it);
        store.awaitIdle();
        return it;
    }

    @Test public void successfulJob_movesToDoneWithTranscript() throws IOException {
        TranscriptionService.setFactory(ctx -> (TranscriptionEngine) audio -> "hello world");
        TranscriptionStore.Item job = enqueue("a", "fake-audio-bytes");

        service.drainQueue();

        TranscriptionStore.Item end = store.get(job.id);
        assertNotNull(end);
        assertEquals(TranscriptionStore.Status.DONE, end.status);
        assertEquals("hello world", end.transcript);
        assertNull(end.errorMessage);
    }

    @Test public void engineThrowsTranscriptionException_movesToError() throws IOException {
        TranscriptionService.setFactory(ctx -> (TranscriptionEngine) audio -> {
            throw new TranscriptionException("boom");
        });
        TranscriptionStore.Item job = enqueue("a", "fake");

        service.drainQueue();

        TranscriptionStore.Item end = store.get(job.id);
        assertEquals(TranscriptionStore.Status.ERROR, end.status);
        assertTrue(end.errorMessage, end.errorMessage.contains("boom"));
    }

    @Test public void engineThrowsIOException_movesToError() throws IOException {
        TranscriptionService.setFactory(ctx -> (TranscriptionEngine) audio -> {
            throw new IOException("network");
        });
        TranscriptionStore.Item job = enqueue("a", "fake");

        service.drainQueue();

        TranscriptionStore.Item end = store.get(job.id);
        assertEquals(TranscriptionStore.Status.ERROR, end.status);
        assertTrue(end.errorMessage, end.errorMessage.contains("network"));
    }

    @Test public void missingAudioFile_movesToError() {
        TranscriptionService.setFactory(ctx -> (TranscriptionEngine) audio -> "unused");
        TranscriptionStore.Item it = TranscriptionStore.Item
                .pending("local", "auto", "ghost")
                .audioPath("audio/does-not-exist.bin")
                .build();
        store.add(it);
        store.awaitIdle();

        service.drainQueue();

        TranscriptionStore.Item end = store.get(it.id);
        assertEquals(TranscriptionStore.Status.ERROR, end.status);
        assertNotNull(end.errorMessage);
    }

    @Test public void multiplePending_drainsQueue() throws IOException {
        TranscriptionService.setFactory(ctx -> (TranscriptionEngine) audio ->
                "len=" + audio.length);
        TranscriptionStore.Item a = enqueue("a", "1234567890");
        TranscriptionStore.Item b = enqueue("b", "ab");

        service.drainQueue();

        assertEquals(TranscriptionStore.Status.DONE, store.get(a.id).status);
        assertEquals(TranscriptionStore.Status.DONE, store.get(b.id).status);
        assertEquals("len=10", store.get(a.id).transcript);
        assertEquals("len=2",  store.get(b.id).transcript);
    }

    @Test public void resweep_recoversRunningJobBeforeProcessing() throws IOException {
        // Simulate a previous crash mid-run: a RUNNING row already in the store.
        TranscriptionService.setFactory(ctx -> (TranscriptionEngine) audio -> "ok");
        TranscriptionStore.Item job = enqueue("a", "xx");
        store.update(job.id, cur -> cur.toBuilder()
                .status(TranscriptionStore.Status.RUNNING).build());
        store.awaitIdle();

        service.drainQueue();

        // Resweep flips RUNNING->PENDING, then the worker drives it to DONE.
        TranscriptionStore.Item end = store.get(job.id);
        assertEquals(TranscriptionStore.Status.DONE, end.status);
        assertEquals("ok", end.transcript);
    }

    @Test public void enqueue_doesNotCrash() {
        // Just make sure the static enqueue(Context) path is reachable.
        TranscriptionService.enqueue(ApplicationProvider.getApplicationContext());
    }

    @Test public void ensureChannel_isIdempotent() {
        TranscriptionService.ensureChannel(ApplicationProvider.getApplicationContext());
        TranscriptionService.ensureChannel(ApplicationProvider.getApplicationContext());
    }
}
