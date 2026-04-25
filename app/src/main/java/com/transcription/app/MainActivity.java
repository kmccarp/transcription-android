package com.transcription.app;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.transcription.core.AudioBytes;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/**
 * Home screen — shows the {@link TranscriptionStore} as a {@link RecyclerView}
 * of past and in-flight transcripts. On {@link Intent#ACTION_SEND} of audio,
 * stores the bytes and asks {@link TranscriptionService} to transcribe in
 * the background.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_POST_NOTIFICATIONS = 0xC011;

    private TranscriptionStore store;
    private TranscriptAdapter   adapter;
    private RecyclerView        list;
    private TextView            empty;
    private Handler             ui;

    private final TranscriptionStore.Listener listener = snapshot -> {
        // Listener fires off the IO thread; jump to main.
        ui.post(() -> render(snapshot));
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        setTitle(R.string.history_title);

        list      = findViewById(R.id.historyList);
        empty     = findViewById(R.id.emptyState);
        adapter   = new TranscriptAdapter(this::openTranscript);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        store     = new TranscriptionStore(this);
        ui        = new Handler(Looper.getMainLooper());
        TranscriptionService.ensureChannel(this);

        // Surface a previous fatal crash if any (from CrashLog) by piggy-backing
        // on the same flow: store an ERROR-status entry. Only do this on cold
        // launch (no share intent), so we don't shadow an inbound share.
        if (savedInstanceState == null) {
            String crash = CrashLog.consume(this);
            if (crash != null) {
                store.add(TranscriptionStore.Item.pending("local", "auto",
                        "crash-" + System.currentTimeMillis())
                        .status(TranscriptionStore.Status.ERROR)
                        .errorMessage(crash)
                        .build());
            }
        }

        // Handle a share intent — but only on the very first onCreate of this
        // task, so navigating back to the activity doesn't re-run the share.
        if (savedInstanceState == null) {
            Uri audio = ShareIntents.extractAudioUri(getIntent());
            if (audio != null) {
                handleSharedAudio(audio);
            } else if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
                Toast.makeText(this, R.string.error_no_audio, Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.registerListener(listener);
        // Snapshot now so the list is correct without waiting for the next event.
        List<TranscriptionStore.Item> initial = store.list();
        render(initial);
    }

    @Override
    protected void onPause() {
        store.unregisterListener(listener);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        Uri audio = ShareIntents.extractAudioUri(intent);
        if (audio != null) {
            handleSharedAudio(audio);
        } else if (Intent.ACTION_SEND.equals(intent.getAction())) {
            Toast.makeText(this, R.string.error_no_audio, Toast.LENGTH_SHORT).show();
        }
    }

    private void render(@NonNull List<TranscriptionStore.Item> snapshot) {
        adapter.replaceAll(snapshot);
        boolean hasItems = !snapshot.isEmpty();
        list.setVisibility(hasItems ? View.VISIBLE : View.GONE);
        empty.setVisibility(hasItems ? View.GONE : View.VISIBLE);
    }

    private void openTranscript(@NonNull TranscriptionStore.Item item) {
        startActivity(new Intent(this, TranscriptActivity.class)
                .putExtra(TranscriptActivity.EXTRA_ID, item.id));
    }

    @Override
    public boolean onCreateOptionsMenu(@NonNull Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        }
        if (id == R.id.action_diagnostics) {
            startActivity(new Intent(this, DiagnosticsActivity.class));
            return true;
        }
        if (id == R.id.action_clear_history) {
            new AlertDialog.Builder(this)
                    .setMessage(R.string.history_clear_confirm)
                    .setPositiveButton(R.string.history_clear, (d, w) -> store.clear())
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ---- share intent → store → service ---------------------------------

    @VisibleForTesting
    void handleSharedAudio(@NonNull Uri uri) {
        ensurePostNotificationsPermission();
        try {
            File audioFile = stashAudio(uri);
            String label = labelForUri(uri);
            TranscriptionStore.Item item = TranscriptionStore.Item
                    .pending(Prefs.engineKind(this), Prefs.language(this), label)
                    .audioPath("audio/" + audioFile.getName())
                    .build();
            store.add(item);
            TranscriptionService.enqueue(this);
            Toast.makeText(this, R.string.status_pending, Toast.LENGTH_SHORT).show();
        } catch (IOException ioe) {
            Toast.makeText(this,
                    getString(R.string.error_unreadable,
                            ioe.getMessage() != null ? ioe.getMessage() : "I/O"),
                    Toast.LENGTH_LONG).show();
        } catch (SecurityException | UnsupportedOperationException e) {
            Toast.makeText(this,
                    getString(R.string.error_unreadable,
                            e.getMessage() != null ? e.getMessage() : "no provider"),
                    Toast.LENGTH_LONG).show();
        }
    }

    /** Copies the shared bytes into our private files dir. */
    private File stashAudio(@NonNull Uri uri) throws IOException {
        File dir = new File(getFilesDir(), "audio");
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Couldn't create audio dir at " + dir);
        }
        File dest = new File(dir, UUID.randomUUID().toString() + ".bin");
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(dest)) {
            if (in == null) throw new IOException("Provider returned null stream");
            byte[] bytes = AudioBytes.readAll(in);
            out.write(bytes);
        }
        return dest;
    }

    private static String labelForUri(@NonNull Uri uri) {
        String last = uri.getLastPathSegment();
        if (last == null || last.isEmpty()) return "audio";
        // Strip any trailing "/" or query bits; keep the last 64 chars.
        if (last.length() > 64) last = "…" + last.substring(last.length() - 63);
        return last;
    }

    private void ensurePostNotificationsPermission() {
        if (Build.VERSION.SDK_INT < 33) return;
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) return;
        requestPermissions(
                new String[]{android.Manifest.permission.POST_NOTIFICATIONS},
                REQ_POST_NOTIFICATIONS);
    }
}
