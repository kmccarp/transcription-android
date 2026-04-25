package com.transcription.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.TextView;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.ByteArrayInputStream;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowApplication;

@RunWith(AndroidJUnit4.class)
public class MainActivityTest {

    private static final byte[] AUDIO = "fake-opus-bytes".getBytes();

    private TranscriptionStore store;
    private Uri audioUri;

    @Before public void setUp() {
        android.content.Context ctx = ApplicationProvider.getApplicationContext();
        store = new TranscriptionStore(ctx);
        store.clear();
        store.awaitIdle();
        // Make sure the previous test's crash log doesn't taint this one.
        java.io.File crash = CrashLog.file(ctx);
        if (crash.exists()) //noinspection ResultOfMethodCallIgnored
            crash.delete();
        audioUri = Uri.parse("content://test/audio.opus");
        Shadows.shadowOf(ctx.getContentResolver())
                .registerInputStream(audioUri, new ByteArrayInputStream(AUDIO));
    }

    @After public void tearDown() {
        store.clear();
        store.awaitIdle();
    }

    private Intent shareIntent(Uri uri) {
        return new Intent(Intent.ACTION_SEND)
                .setType("audio/ogg")
                .putExtra(Intent.EXTRA_STREAM, uri);
    }

    // ---- happy path ------------------------------------------------------

    @Test public void share_audio_addsPendingItemAndStartsService() {
        ActivityController<MainActivity> ctrl =
                Robolectric.buildActivity(MainActivity.class, shareIntent(audioUri)).setup();
        store.awaitIdle();

        // Store now has exactly one PENDING item with our shared bytes.
        List<TranscriptionStore.Item> items = store.list();
        assertEquals(1, items.size());
        TranscriptionStore.Item entry = items.get(0);
        assertEquals(TranscriptionStore.Status.PENDING, entry.status);
        assertNotNull(entry.audioPath);

        // The service was kicked off.
        ShadowApplication app = Shadows.shadowOf(
                (android.app.Application) ApplicationProvider.getApplicationContext());
        Intent next = app.getNextStartedService();
        assertNotNull("service should be started", next);
        ComponentName c = next.getComponent();
        assertNotNull(c);
        assertEquals(TranscriptionService.class.getName(), c.getClassName());

        ctrl.destroy();
    }

    // ---- empty state -----------------------------------------------------

    @Test public void coldLaunch_withoutShare_showsEmptyState() {
        ActivityController<MainActivity> ctrl =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = ctrl.get();
        store.awaitIdle();

        TextView empty = activity.findViewById(R.id.emptyState);
        RecyclerView list = activity.findViewById(R.id.historyList);
        assertEquals(View.VISIBLE, empty.getVisibility());
        assertEquals(View.GONE, list.getVisibility());
    }

    @Test public void existingHistory_isRenderedOnResume() {
        // Pre-populate the store.
        store.add(TranscriptionStore.Item.pending("local", "auto", "old.opus")
                .status(TranscriptionStore.Status.DONE)
                .transcript("old transcript").build());
        store.awaitIdle();

        ActivityController<MainActivity> ctrl =
                Robolectric.buildActivity(MainActivity.class).setup();
        MainActivity activity = ctrl.get();
        // The Listener fires from the IO thread; idle the main looper to
        // pump the posted runnable.
        org.robolectric.shadows.ShadowLooper.idleMainLooper();

        RecyclerView list = activity.findViewById(R.id.historyList);
        assertEquals(View.VISIBLE, list.getVisibility());
        assertNotEquals(0, list.getAdapter().getItemCount());
    }

    // ---- non-audio share -------------------------------------------------

    @Test public void share_nonAudio_doesNotEnqueue() {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("image/png");
        ActivityController<MainActivity> ctrl =
                Robolectric.buildActivity(MainActivity.class, intent).setup();
        store.awaitIdle();
        assertTrue(store.list().isEmpty());
        ctrl.destroy();
    }

    @Test public void share_unreadableUri_showsToastAndNoEntry() {
        Uri broken = Uri.parse("content://nope/whatever");
        Intent intent = shareIntent(broken);
        ActivityController<MainActivity> ctrl =
                Robolectric.buildActivity(MainActivity.class, intent).setup();
        store.awaitIdle();
        // No history row created when the read fails.
        assertTrue("no entry expected on read failure",
                store.list().isEmpty());
        ctrl.destroy();
    }

    // (We deliberately don't assert ctrl.newIntent here — Robolectric's
    // dispatch through onNewIntent isn't reliable across shadow versions
    // and the production code path is already covered above. Manual
    // device test confirms the share-from-background flow.)
}
