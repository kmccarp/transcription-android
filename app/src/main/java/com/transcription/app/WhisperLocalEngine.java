package com.transcription.app;

import android.content.Context;
import androidx.annotation.NonNull;
import com.transcription.core.TranscriptionEngine;
import com.transcription.core.TranscriptionException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * On-device transcription via whisper.cpp + a Whisper model bundled in the
 * APK assets. The model file is copied (once) into private storage on first
 * use because whisper.cpp loads it via a {@code FILE*}, not from the asset
 * manager's compressed read-only stream.
 *
 * <p>This is the default engine. {@link com.transcription.core.OllamaTranscriptionEngine}
 * remains as a backup path for users who want to point at a remote server.
 */
public final class WhisperLocalEngine implements TranscriptionEngine {

    static final String ASSET_NAME = "whisper-model.bin";
    static final String LOCAL_NAME = "whisper-model.bin";

    private final Context appCtx;
    private final String  language;   // "auto" or ISO-639-1
    private final int     threads;

    /**
     * @param language ISO-639-1 language code or {@code "auto"} for autodetect.
     * @param threads  CPU threads to use; {@code 0} = whisper.cpp default.
     */
    public WhisperLocalEngine(@NonNull Context ctx, @NonNull String language, int threads) {
        this.appCtx   = ctx.getApplicationContext();
        this.language = language;
        this.threads  = threads;
    }

    @Override
    public String transcribe(byte[] audio) throws IOException, TranscriptionException {
        if (audio == null || audio.length == 0) {
            throw new TranscriptionException("Audio payload is empty");
        }
        File model = ensureModel(appCtx);
        long ctx = 0L;
        try {
            // Decode audio bytes -> 16 kHz mono float PCM.
            float[] samples;
            File scratch = appCtx.getCacheDir();
            try (InputStream in = bytesAsStream(audio)) {
                samples = AudioDecoder.decode(in, scratch);
            }
            if (samples.length == 0) {
                throw new TranscriptionException("Decoded audio was empty");
            }

            ctx = WhisperJni.initContextFromPath(model.getAbsolutePath());
            if (ctx == 0L) {
                throw new TranscriptionException(
                        "Whisper model failed to load: " + model.getAbsolutePath());
            }
            return WhisperJni.transcribe(ctx, samples, language, threads).trim();
        } finally {
            if (ctx != 0L) WhisperJni.freeContext(ctx);
        }
    }

    /** Copies the bundled model to filesDir on first use, then reuses it. */
    static File ensureModel(Context appCtx) throws IOException {
        File dest = new File(appCtx.getFilesDir(), LOCAL_NAME);
        if (dest.exists() && dest.length() > 0) return dest;
        try (InputStream in = appCtx.getAssets().open(ASSET_NAME);
             OutputStream out = new FileOutputStream(dest)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } catch (IOException ioe) {
            //noinspection ResultOfMethodCallIgnored
            dest.delete();
            throw ioe;
        }
        return dest;
    }

    private static InputStream bytesAsStream(byte[] bytes) {
        return new java.io.ByteArrayInputStream(bytes);
    }
}
