package com.transcription.app;

import android.content.Context;
import android.util.Log;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Persists uncaught exception stack traces to a file in the app's private
 * cache so they survive the process kill and can be shown to the user on the
 * next launch. Keeps things simple: append-only, plain text, capped to 64 KB.
 *
 * <p>Off-device debugging is the goal — when {@code adb} isn't reachable,
 * this is what shows you what blew up.
 */
public final class CrashLog {

    private static final String TAG = "Transcription/Crash";
    private static final String FILENAME = "last-crash.log";
    private static final long   MAX_BYTES = 64 * 1024;

    private CrashLog() {}

    /** Returns the file path; the file may or may not exist. */
    public static File file(Context ctx) {
        return new File(ctx.getCacheDir(), FILENAME);
    }

    /** Hooks the JVM-default handler. Idempotent; safe to call from {@code onCreate}. */
    public static void install(Context ctx) {
        Context appCtx = ctx.getApplicationContext();
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            try {
                write(appCtx, t, e);
            } catch (Throwable swallow) {
                Log.e(TAG, "Failed to persist crash", swallow);
            }
            if (prev != null) prev.uncaughtException(t, e);
        });
    }

    /** Reads any persisted crash, deletes it, returns the text (or {@code null}). */
    public static String consume(Context ctx) {
        File f = file(ctx);
        if (!f.exists() || f.length() == 0) return null;
        try {
            byte[] bytes = Files.readAllBytes(f.toPath());
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException ioe) {
            Log.e(TAG, "Failed to read crash log", ioe);
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
    }

    private static void write(Context appCtx, Thread t, Throwable e) throws IOException {
        File f = file(appCtx);
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        pw.print("=== Uncaught crash @ ");
        pw.print(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date()));
        pw.print(" on thread ");
        pw.println(t.getName());
        e.printStackTrace(pw);
        pw.flush();

        byte[] payload = sw.toString().getBytes(StandardCharsets.UTF_8);
        long existing = f.exists() ? f.length() : 0;
        if (existing + payload.length > MAX_BYTES) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        Files.write(f.toPath(), payload,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                StandardOpenOption.APPEND);

        // Mirror to an externally-browsable location. No permission needed
        // for getExternalCacheDir(). Reachable via any file manager at
        // Android/data/com.transcription.app/cache/last-crash.log
        File ext = new File(appCtx.getExternalCacheDir(), FILENAME);
        try {
            Files.write(ext.toPath(), payload,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND);
        } catch (Throwable ignore) {
            // External storage may be unavailable; the in-cache copy is
            // still authoritative.
        }

        // And to logcat for adb/Logcat Reader users.
        Log.e(TAG, "Uncaught on " + t.getName(), e);
    }
}
