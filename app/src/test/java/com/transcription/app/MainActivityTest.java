package com.transcription.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.transcription.core.OllamaConfig;
import com.transcription.core.TranscriptionEngine;
import com.transcription.core.TranscriptionException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;

@RunWith(AndroidJUnit4.class)
public class MainActivityTest {

    private static final byte[] FAKE_AUDIO = "fake-opus-bytes".getBytes();

    private Uri audioUri;

    @Before public void registerFakeContentProvider() {
        audioUri = Uri.parse("content://test/audio.opus");
        ShadowContentResolver.registerInputStream(audioUri,
                new ByteArrayInputStream(FAKE_AUDIO));
    }

    @After public void resetEngine() {
        MainActivity.setEngineFactory(null);
    }

    private Intent shareIntent() {
        return new Intent(Intent.ACTION_SEND)
                .setType("audio/ogg")
                .putExtra(Intent.EXTRA_STREAM, audioUri);
    }

    /** Drives all queued background + main-thread work to completion. */
    private static void drainAll() {
        ShadowLooper.idleMainLooper();
        // Robolectric runs the SingleThreadExecutor on a real thread; give it
        // a tick by flushing the main looper repeatedly.
        for (int i = 0; i < 20; i++) {
            ShadowLooper.idleMainLooper();
            try { Thread.sleep(20); } catch (InterruptedException ignored) {}
        }
        ShadowLooper.idleMainLooper();
    }

    // -------- happy path ---------------------------------------------------

    @Test public void share_audio_runsEngineAndShowsResult() {
        AtomicReference<byte[]> seen = new AtomicReference<>();
        MainActivity.setEngineFactory(cfg -> (TranscriptionEngine) audio -> {
            seen.set(audio);
            return "hello world";
        });

        ActivityController<MainActivity> ctrl =
                Robolectric.buildActivity(MainActivity.class, shareIntent()).setup();
        drainAll();
        MainActivity activity = ctrl.get();

        TextView result = activity.findViewById(R.id.resultText);
        TextView status = activity.findViewById(R.id.statusText);
        ProgressBar progress = activity.findViewById(R.id.progress);
        Button copy = activity.findViewById(R.id.copyButton);
        Button share = activity.findViewById(R.id.shareButton);

        assertEquals("hello world", result.getText().toString());
        assertEquals(activity.getString(R.string.status_done), status.getText().toString());
        assertEquals(View.GONE, progress.getVisibility());
        assertTrue(copy.isEnabled());
        assertTrue(share.isEnabled());
        assertEquals(FAKE_AUDIO.length, seen.get().length);
    }

    // -------- error paths --------------------------------------------------

    @Test public void share_engineThrowsTranscriptionException_showsErrorMessage() {
        MainActivity.setEngineFactory(cfg -> (TranscriptionEngine) audio -> {
            throw new TranscriptionException("model not loaded");
        });

        ActivityController<MainActivity> ctrl =
                Robolectric.buildActivity(MainActivity.class, shareIntent()).setup();
        drainAll();
        MainActivity activity = ctrl.get();

        String status = ((TextView) activity.findViewById(R.id.statusText))
                .getText().toString();
        assertTrue(status, status.contains("model not loaded"));
        assertFalse(((Button) activity.findViewById(R.id.copyButton)).isEnabled());
    }

    @Test public void share_engineThrowsIOException_showsErrorMessage() {
        MainActivity.setEngineFactory(cfg -> (TranscriptionEngine) audio -> {
            throw new IOException("connection refused");
        });

        ActivityController<MainActivity> ctrl =
                Robolectric.buildActivity(MainActivity.class, shareIntent()).setup();
        drainAll();
        MainActivity activity = ctrl.get();

        String status = ((TextView) activity.findViewById(R.id.statusText))
                .getText().toString();
        assertTrue(status, status.contains("connection refused"));
    }

    @Test public void share_unreadableUri_showsReadError() {
        // No registered InputStream for this URI → openInputStream returns null.
        Uri broken = Uri.parse("content://nope/whatever");
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType("audio/ogg")
                .putExtra(Intent.EXTRA_STREAM, broken);

        MainActivity.setEngineFactory(cfg -> (TranscriptionEngine) audio -> "unused");
        ActivityController<MainActivity> ctrl =
                Robolectric.buildActivity(MainActivity.class, intent).setup();
        drainAll();
        MainActivity activity = ctrl.get();

        String status = ((TextView) activity.findViewById(R.id.statusText))
                .getText().toString();
        assertTrue("expected 'Couldn\\'t read' in status, got: " + status,
                status.startsWith("Couldn"));
    }

    @Test public void share_streamThrowsIOException_isReported() {
        Uri uri = Uri.parse("content://test/broken-stream.opus");
        ShadowContentResolver.registerInputStream(uri, new InputStream() {
            @Override public int read() throws IOException {
                throw new IOException("disk on fire");
            }
        });
        Intent intent = new Intent(Intent.ACTION_SEND)
                .setType("audio/ogg")
                .putExtra(Intent.EXTRA_STREAM, uri);

        MainActivity.setEngineFactory(cfg -> (TranscriptionEngine) audio -> "nope");
        ActivityController<MainActivity> ctrl =
                Robolectric.buildActivity(MainActivity.class, intent).setup();
        drainAll();
        MainActivity activity = ctrl.get();

        String status = ((TextView) activity.findViewById(R.id.statusText))
                .getText().toString();
        assertTrue(status, status.contains("disk on fire"));
    }

    // -------- launcher path ----------------------------------------------

    @Test public void coldLaunch_withoutShare_showsInstructions() {
        ActivityController<MainActivity> ctrl =
                Robolectric.buildActivity(MainActivity.class).setup();
        drainAll();
        MainActivity activity = ctrl.get();
        TextView status = activity.findViewById(R.id.statusText);
        assertEquals(activity.getString(R.string.instructions_idle),
                status.getText().toString());
        assertEquals(View.GONE,
                ((ProgressBar) activity.findViewById(R.id.progress)).getVisibility());
    }

    @Test public void send_withoutAudioPayload_showsNoAudioError() {
        Intent intent = new Intent(Intent.ACTION_SEND).setType("text/plain");
        ActivityController<MainActivity> ctrl =
                Robolectric.buildActivity(MainActivity.class, intent).setup();
        drainAll();
        MainActivity activity = ctrl.get();
        TextView status = activity.findViewById(R.id.statusText);
        assertEquals(activity.getString(R.string.error_no_audio),
                status.getText().toString());
    }

    // -------- buttons ----------------------------------------------------

    @Test public void copyButton_putsTranscriptOnClipboard() {
        MainActivity.setEngineFactory(cfg -> (TranscriptionEngine) a -> "text to copy");
        ActivityController<MainActivity> ctrl =
                Robolectric.buildActivity(MainActivity.class, shareIntent()).setup();
        drainAll();
        MainActivity activity = ctrl.get();
        ((Button) activity.findViewById(R.id.copyButton)).performClick();

        android.content.ClipboardManager cm = (android.content.ClipboardManager)
                activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
        assertEquals("text to copy",
                cm.getPrimaryClip().getItemAt(0).getText().toString());
    }

    @Test public void shareButton_launchesChooser() {
        MainActivity.setEngineFactory(cfg -> (TranscriptionEngine) a -> "shareable text");
        ActivityController<MainActivity> ctrl =
                Robolectric.buildActivity(MainActivity.class, shareIntent()).setup();
        drainAll();
        MainActivity activity = ctrl.get();
        ((Button) activity.findViewById(R.id.shareButton)).performClick();

        Intent next = Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_CHOOSER, next.getAction());
        Intent inner = next.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals("shareable text", inner.getStringExtra(Intent.EXTRA_TEXT));
    }

    // -------- factory accessor -------------------------------------------

    @Test public void setEngineFactory_nullResetsToDefault() {
        MainActivity.setEngineFactory(null);
        // Default factory builds a real engine — just check that the type
        // is what we expect by exercising the public Transcribers contract.
        TranscriptionEngine engine = Transcribers.DEFAULT.create(
                OllamaConfig.builder().build());
        assertTrue(engine.getClass().getSimpleName(),
                engine.getClass().getSimpleName().contains("OllamaTranscription"));
    }
}
