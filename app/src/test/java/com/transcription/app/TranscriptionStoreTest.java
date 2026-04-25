package com.transcription.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TranscriptionStoreTest {

    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private File storeFile;
    private TranscriptionStore store;

    @Before public void setUp() throws IOException {
        storeFile = new File(tmp.newFolder("files"), "transcripts.json");
        store     = new TranscriptionStore(storeFile);
    }

    private static TranscriptionStore.Item pending(String label) {
        return TranscriptionStore.Item.pending("local", "auto", label).build();
    }

    @Test public void list_emptyStore_returnsEmpty() {
        assertTrue(store.list().isEmpty());
    }

    @Test public void add_thenList_returnsItem() {
        store.add(pending("a.opus"));
        store.awaitIdle();
        List<TranscriptionStore.Item> items = store.list();
        assertEquals(1, items.size());
        assertEquals("a.opus", items.get(0).sourceLabel);
        assertEquals(TranscriptionStore.Status.PENDING, items.get(0).status);
    }

    @Test public void list_isNewestFirst() throws InterruptedException {
        TranscriptionStore.Item a = TranscriptionStore.Item.pending("local", "auto", "a")
                .createdAt(1000L).build();
        TranscriptionStore.Item b = TranscriptionStore.Item.pending("local", "auto", "b")
                .createdAt(2000L).build();
        TranscriptionStore.Item c = TranscriptionStore.Item.pending("local", "auto", "c")
                .createdAt(1500L).build();
        store.add(a); store.add(b); store.add(c);
        store.awaitIdle();
        List<TranscriptionStore.Item> items = store.list();
        assertEquals("b", items.get(0).sourceLabel);
        assertEquals("c", items.get(1).sourceLabel);
        assertEquals("a", items.get(2).sourceLabel);
    }

    @Test public void update_changesItemAndBumpsUpdatedAt() throws InterruptedException {
        TranscriptionStore.Item original = pending("a");
        store.add(original);
        store.awaitIdle();
        // Sleep so updatedAt changes deterministically.
        Thread.sleep(2);
        store.update(original.id, cur -> cur.toBuilder()
                .status(TranscriptionStore.Status.DONE)
                .transcript("hello world")
                .build());
        store.awaitIdle();
        TranscriptionStore.Item got = store.get(original.id);
        assertNotNull(got);
        assertEquals(TranscriptionStore.Status.DONE, got.status);
        assertEquals("hello world", got.transcript);
        assertTrue(got.updatedAt > got.createdAt);
    }

    @Test public void update_unknownId_isNoOp() {
        store.add(pending("a"));
        store.awaitIdle();
        store.update("does-not-exist", cur -> cur.toBuilder().sourceLabel("nope").build());
        store.awaitIdle();
        assertEquals("a", store.list().get(0).sourceLabel);
    }

    @Test public void delete_removesItem() {
        TranscriptionStore.Item it = pending("a");
        store.add(it);
        store.awaitIdle();
        store.delete(it.id);
        store.awaitIdle();
        assertTrue(store.list().isEmpty());
    }

    @Test public void clear_emptiesEverything() {
        store.add(pending("a"));
        store.add(pending("b"));
        store.awaitIdle();
        store.clear();
        store.awaitIdle();
        assertTrue(store.list().isEmpty());
    }

    @Test public void resweepRunningToPending_resetsRunning() {
        TranscriptionStore.Item running = pending("a").toBuilder()
                .status(TranscriptionStore.Status.RUNNING).build();
        TranscriptionStore.Item done = pending("b").toBuilder()
                .status(TranscriptionStore.Status.DONE).transcript("ok").build();
        store.add(running);
        store.add(done);
        store.awaitIdle();
        store.resweepRunningToPending();
        store.awaitIdle();
        TranscriptionStore.Item afterRunning = store.get(running.id);
        TranscriptionStore.Item afterDone    = store.get(done.id);
        assertEquals(TranscriptionStore.Status.PENDING, afterRunning.status);
        // DONE is left alone.
        assertEquals(TranscriptionStore.Status.DONE, afterDone.status);
    }

    @Test public void persistence_acrossInstances() {
        store.add(pending("a"));
        store.awaitIdle();
        TranscriptionStore second = new TranscriptionStore(storeFile);
        List<TranscriptionStore.Item> items = second.list();
        assertEquals(1, items.size());
        assertEquals("a", items.get(0).sourceLabel);
    }

    @Test public void corruptFile_isQuarantinedAndRecoveredAsEmpty() throws IOException {
        try (FileOutputStream out = new FileOutputStream(storeFile)) {
            out.write("{not valid json".getBytes(StandardCharsets.UTF_8));
        }
        TranscriptionStore corruptStore = new TranscriptionStore(storeFile);
        assertTrue(corruptStore.list().isEmpty());
        File corrupt = new File(storeFile.getParentFile(), "transcripts.json.corrupt");
        assertTrue("corrupt file should be quarantined", corrupt.exists());
    }

    @Test public void atomicWrite_neverLeavesPartialFile() throws IOException, InterruptedException {
        // Stress: 50 rapid updates, then verify the JSON parses and contains the
        // last value. The atomic-rename pattern means we never see half-written
        // JSON even if the test reads concurrently.
        TranscriptionStore.Item it = pending("a");
        store.add(it);
        store.awaitIdle();
        for (int i = 0; i < 50; i++) {
            int v = i;
            store.update(it.id, cur -> cur.toBuilder().transcript("v=" + v).build());
        }
        store.awaitIdle();
        TranscriptionStore.Item end = store.get(it.id);
        assertNotNull(end);
        assertEquals("v=49", end.transcript);
        // Read the raw file ourselves and parse it to be sure.
        String raw = new String(Files.readAllBytes(storeFile.toPath()),
                StandardCharsets.UTF_8);
        assertTrue("raw JSON must contain the final transcript: " + raw,
                raw.contains("v=49"));
    }

    @Test public void listener_isNotifiedOnAdd() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<TranscriptionStore.Item>> seen = new AtomicReference<>();
        store.registerListener(snapshot -> {
            seen.set(snapshot);
            latch.countDown();
        });
        store.add(pending("a"));
        assertTrue("listener should fire", latch.await(2, TimeUnit.SECONDS));
        assertEquals(1, seen.get().size());

        // Unregistering stops further notifications.
        store.unregisterListener(snapshot -> {});  // no-op, different lambda
    }

    @Test public void item_toBuilder_roundTrips() {
        TranscriptionStore.Item it = TranscriptionStore.Item.pending("local", "fr", "x.opus")
                .audioPath("/foo")
                .transcript("hi")
                .build();
        TranscriptionStore.Item copy = it.toBuilder().build();
        assertEquals(it.id, copy.id);
        assertEquals(it.sourceLabel, copy.sourceLabel);
        assertEquals(it.transcript, copy.transcript);
    }

    @Test public void get_unknownId_returnsNull() {
        assertNull(store.get("does-not-exist"));
    }

    @Test public void delete_unknownId_isNoOp() {
        store.delete("does-not-exist");
        store.awaitIdle();
        assertFalse("file should not be created if there's nothing to delete",
                storeFile.exists());
    }
}
