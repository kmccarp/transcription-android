package com.transcription.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import com.transcription.core.AudioBytes;
import com.transcription.core.OllamaConfig;
import com.transcription.core.TranscriptionEngine;
import com.transcription.core.TranscriptionException;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Single-activity host. Handles two entry points:
 *
 * <ul>
 *   <li>Cold launch (LAUNCHER) — shows instructions.</li>
 *   <li>{@link Intent#ACTION_SEND} of audio — reads the stream, runs it
 *       through the configured {@link TranscriptionEngine}, and shows the
 *       resulting text.</li>
 * </ul>
 *
 * <p>Threading: I/O and transcription run on a single-threaded background
 * executor; UI updates are posted to the main thread. There is no rotation
 * persistence — the Activity re-runs on configuration change. That's
 * intentional: a transcription is fast, and replaying it costs no Ollama
 * compute on cache hit.
 */
public class MainActivity extends AppCompatActivity {

    private TextView statusText;
    private TextView resultText;
    private ProgressBar progress;
    private Button copyButton;
    private Button shareButton;

    private java.util.concurrent.Executor executor;
    private Handler mainHandler;

    /** Hook for tests. Defaults to {@link Transcribers#DEFAULT}. */
    private static Transcribers.Factory engineFactory = Transcribers.DEFAULT;

    /** Hook for tests. Defaults to a fresh single-thread {@link ExecutorService}. */
    private static java.util.concurrent.Executor testExecutor;

    @VisibleForTesting
    public static void setEngineFactory(Transcribers.Factory factory) {
        engineFactory = (factory != null) ? factory : Transcribers.DEFAULT;
    }

    /**
     * Tests inject a synchronous {@link java.util.concurrent.Executor} (e.g.
     * {@code Runnable::run}) so the transcription work happens inline and
     * Robolectric can drain only the main looper to observe results.
     */
    @VisibleForTesting
    public static void setExecutor(java.util.concurrent.Executor executor) {
        testExecutor = executor;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        statusText  = findViewById(R.id.statusText);
        resultText  = findViewById(R.id.resultText);
        progress    = findViewById(R.id.progress);
        copyButton  = findViewById(R.id.copyButton);
        shareButton = findViewById(R.id.shareButton);

        copyButton.setOnClickListener(v -> copyToClipboard());
        shareButton.setOnClickListener(v -> shareTranscript());

        executor    = (testExecutor != null) ? testExecutor
                                              : Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        // If the previous run died, surface the trace inline so it can be
        // screenshotted off the device. Crash text takes priority over the
        // share-intent flow because if it crashed once, it'll likely crash
        // again on the same input — show what happened first.
        String crash = CrashLog.consume(this);
        if (crash != null) {
            statusText.setText(R.string.previous_crash);
            resultText.setText(crash);
            copyButton.setEnabled(true);
            shareButton.setEnabled(true);
            return;
        }

        Uri audio = ShareIntents.extractAudioUri(getIntent());
        if (audio != null) {
            startTranscription(audio);
        } else if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
            statusText.setText(R.string.error_no_audio);
        }
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        if (executor instanceof ExecutorService) {
            ((ExecutorService) executor).shutdownNow();
        }
        super.onDestroy();
    }

    // ----- core flow -------------------------------------------------------

    @VisibleForTesting
    void startTranscription(Uri uri) {
        progress.setVisibility(View.VISIBLE);
        statusText.setText(R.string.status_reading);
        resultText.setText("");
        copyButton.setEnabled(false);
        shareButton.setEnabled(false);

        OllamaConfig config = Prefs.load(this);
        TranscriptionEngine engine = engineFactory.create(config);

        executor.execute(() -> {
            try {
                runTranscriptionPipeline(uri, engine);
            } catch (RuntimeException unexpected) {
                // Catchall: anything we didn't anticipate becomes a visible
                // status string instead of a silent process-killing crash.
                postError(getString(R.string.error_transcription,
                        unexpected.getClass().getSimpleName()
                                + (unexpected.getMessage() != null
                                        ? ": " + unexpected.getMessage() : "")));
            }
        });
    }

    private void runTranscriptionPipeline(Uri uri, TranscriptionEngine engine) {
        byte[] audio;
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) {
                postError(getString(R.string.error_unreadable, "stream is null"));
                return;
            }
            audio = AudioBytes.readAll(in);
        } catch (IOException ioe) {
            postError(getString(R.string.error_unreadable,
                    ioe.getMessage() != null ? ioe.getMessage() : "I/O error"));
            return;
        } catch (SecurityException | UnsupportedOperationException e) {
            // SecurityException: provider permission denied.
            // UnsupportedOperationException: a content provider that
            // claims to back the URI but can't actually stream it
            // (also what Robolectric throws for unregistered URIs).
            postError(getString(R.string.error_unreadable,
                    e.getMessage() != null ? e.getMessage() : "no provider for " + uri));
            return;
        }

        mainHandler.post(() -> statusText.setText(R.string.status_transcribing));
        try {
            String text = engine.transcribe(audio);
            postSuccess(text);
        } catch (IOException ioe) {
            postError(getString(R.string.error_transcription,
                    ioe.getMessage() != null ? ioe.getMessage() : "network error"));
        } catch (TranscriptionException te) {
            postError(getString(R.string.error_transcription, te.getMessage()));
        }
    }

    private void postSuccess(String text) {
        mainHandler.post(() -> {
            progress.setVisibility(View.GONE);
            statusText.setText(R.string.status_done);
            resultText.setText(text);
            boolean hasContent = !TextUtils.isEmpty(text);
            copyButton.setEnabled(hasContent);
            shareButton.setEnabled(hasContent);
        });
    }

    private void postError(String message) {
        mainHandler.post(() -> {
            progress.setVisibility(View.GONE);
            statusText.setText(message);
            resultText.setText("");
            copyButton.setEnabled(false);
            shareButton.setEnabled(false);
        });
    }

    // ----- buttons --------------------------------------------------------

    private void copyToClipboard() {
        CharSequence text = resultText.getText();
        if (TextUtils.isEmpty(text)) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.copy_label), text));
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
    }

    private void shareTranscript() {
        CharSequence text = resultText.getText();
        if (TextUtils.isEmpty(text)) return;
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_TEXT, text.toString());
        startActivity(Intent.createChooser(send, getString(R.string.action_share)));
    }
}
