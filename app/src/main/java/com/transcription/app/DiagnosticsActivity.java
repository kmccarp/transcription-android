package com.transcription.app;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

/** Stand-alone screen for the diagnostic blob; copy / share buttons. */
public class DiagnosticsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_diagnostics);
        setTitle(R.string.action_diagnostics);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        TextView body = findViewById(R.id.diagnosticsText);
        Button   copy = findViewById(R.id.diagnosticsCopy);
        Button   share = findViewById(R.id.diagnosticsShare);

        String text = Diagnostics.build(this);
        body.setText(text);

        copy.setOnClickListener(v -> {
            if (TextUtils.isEmpty(body.getText())) return;
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("WA Transcribe diagnostics",
                    body.getText()));
            Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show();
        });
        share.setOnClickListener(v -> {
            Intent send = new Intent(Intent.ACTION_SEND)
                    .setType("text/plain")
                    .putExtra(Intent.EXTRA_TEXT, body.getText().toString());
            startActivity(Intent.createChooser(send, getString(R.string.action_share)));
        });
    }
}
