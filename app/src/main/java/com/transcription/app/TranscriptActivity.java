package com.transcription.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import java.util.List;

/** Detail screen for a single {@link TranscriptionStore.Item}. */
public class TranscriptActivity extends AppCompatActivity {

    public static final String EXTRA_ID = "transcript_id";

    private TranscriptionStore store;
    private Handler            ui;
    private String             id;

    private TextView    statusView;
    private TextView    metaView;
    private TextView    bodyView;
    private ProgressBar progress;
    private Button      copyBtn;
    private Button      shareBtn;
    private Button      retryBtn;
    private Button      deleteBtn;

    private final TranscriptionStore.Listener listener = snapshot ->
            ui.post(() -> render(findById(snapshot, id)));

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_transcript);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        statusView = findViewById(R.id.transcriptStatus);
        metaView   = findViewById(R.id.transcriptMeta);
        bodyView   = findViewById(R.id.transcriptText);
        progress   = findViewById(R.id.transcriptProgress);
        copyBtn    = findViewById(R.id.transcriptCopy);
        shareBtn   = findViewById(R.id.transcriptShare);
        retryBtn   = findViewById(R.id.transcriptRetry);
        deleteBtn  = findViewById(R.id.transcriptDelete);

        store = new TranscriptionStore(this);
        ui    = new Handler(Looper.getMainLooper());
        id    = getIntent() != null ? getIntent().getStringExtra(EXTRA_ID) : null;
        if (id == null) {
            finish();
            return;
        }

        copyBtn.setOnClickListener(v -> copy());
        shareBtn.setOnClickListener(v -> share());
        retryBtn.setOnClickListener(v -> retry());
        deleteBtn.setOnClickListener(v -> confirmDelete());
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.registerListener(listener);
        render(store.get(id));
    }

    @Override
    protected void onPause() {
        store.unregisterListener(listener);
        super.onPause();
    }

    private void render(@Nullable TranscriptionStore.Item item) {
        if (item == null) {
            statusView.setText(R.string.history_delete);
            bodyView.setText("");
            metaView.setText("");
            progress.setVisibility(View.GONE);
            disableButtons();
            return;
        }
        switch (item.status) {
            case PENDING:
                statusView.setText(R.string.status_pending);
                progress.setVisibility(View.VISIBLE);
                bodyView.setText("");
                disableTextButtons();
                retryBtn.setEnabled(false);
                deleteBtn.setEnabled(true);
                break;
            case RUNNING:
                statusView.setText(R.string.status_transcribing);
                progress.setVisibility(View.VISIBLE);
                bodyView.setText("");
                disableTextButtons();
                retryBtn.setEnabled(false);
                deleteBtn.setEnabled(true);
                break;
            case DONE:
                statusView.setText(R.string.status_done);
                progress.setVisibility(View.GONE);
                bodyView.setText(item.transcript == null ? "" : item.transcript);
                boolean hasText = !TextUtils.isEmpty(item.transcript);
                copyBtn.setEnabled(hasText);
                shareBtn.setEnabled(hasText);
                retryBtn.setEnabled(item.audioPath != null);
                deleteBtn.setEnabled(true);
                break;
            case ERROR:
            default:
                statusView.setText(R.string.notification_failed_title);
                progress.setVisibility(View.GONE);
                bodyView.setText(item.errorMessage == null ? "" : item.errorMessage);
                disableTextButtons();
                retryBtn.setEnabled(item.audioPath != null);
                deleteBtn.setEnabled(true);
                break;
        }
        StringBuilder meta = new StringBuilder();
        meta.append(item.sourceLabel).append(" · ")
                .append(DateUtils.getRelativeDateTimeString(this,
                        item.createdAt, DateUtils.MINUTE_IN_MILLIS,
                        DateUtils.WEEK_IN_MILLIS, 0))
                .append(" · ")
                .append(item.engine);
        if (!"auto".equals(item.language)) meta.append(" · ").append(item.language);
        metaView.setText(meta);
    }

    private void copy() {
        CharSequence text = bodyView.getText();
        if (TextUtils.isEmpty(text)) return;
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText(getString(R.string.copy_label), text));
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
    }

    private void share() {
        CharSequence text = bodyView.getText();
        if (TextUtils.isEmpty(text)) return;
        Intent send = new Intent(Intent.ACTION_SEND)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, text.toString());
        startActivity(Intent.createChooser(send, getString(R.string.action_share)));
    }

    private void retry() {
        store.update(id, cur -> cur.toBuilder()
                .status(TranscriptionStore.Status.PENDING)
                .errorMessage(null)
                .transcript(null)
                .build());
        TranscriptionService.enqueue(this);
        Toast.makeText(this, R.string.status_pending, Toast.LENGTH_SHORT).show();
    }

    private void confirmDelete() {
        new AlertDialog.Builder(this)
                .setMessage(R.string.history_clear_confirm)
                .setPositiveButton(R.string.history_delete, (d, w) -> {
                    store.delete(id);
                    finish();
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void disableTextButtons() {
        copyBtn.setEnabled(false);
        shareBtn.setEnabled(false);
    }

    private void disableButtons() {
        disableTextButtons();
        retryBtn.setEnabled(false);
        deleteBtn.setEnabled(false);
    }

    @Nullable
    private static TranscriptionStore.Item findById(
            @NonNull List<TranscriptionStore.Item> snapshot, @NonNull String id) {
        for (TranscriptionStore.Item it : snapshot) {
            if (id.equals(it.id)) return it;
        }
        return null;
    }
}
