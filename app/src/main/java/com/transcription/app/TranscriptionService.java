package com.transcription.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.core.app.NotificationCompat;
import com.transcription.core.TranscriptionEngine;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Foreground service that drains all {@link TranscriptionStore.Status#PENDING}
 * jobs from the store. See ADR 0003.
 *
 * <p>Workflow:
 * <ol>
 *   <li>Start with {@link #enqueue(Context)} — it requests a pending sweep.</li>
 *   <li>Service goes foreground (notification channel
 *       {@link #CHANNEL_ID}) and resweeps any stale {@code RUNNING} jobs.</li>
 *   <li>For each pending job: read the audio file, run the engine, write
 *       the result back through {@link TranscriptionStore}.</li>
 *   <li>When the queue is empty, stop the foreground state and
 *       {@code stopSelf()}.</li>
 * </ol>
 */
public class TranscriptionService extends Service {

    public static final String CHANNEL_ID            = "transcription";
    public static final int    NOTIF_ID_ONGOING      = 1;
    public static final int    NOTIF_ID_RESULT_BASE  = 1000;

    /** Tests override the engine factory; null uses {@link Transcribers#DEFAULT}. */
    private static volatile Transcribers.Factory factoryOverride;

    @VisibleForTesting
    public static void setFactory(@Nullable Transcribers.Factory f) {
        factoryOverride = f;
    }

    private TranscriptionStore store;
    private ExecutorService    worker;
    private final AtomicBoolean stopping = new AtomicBoolean(false);

    /** Posts an intent that causes a queue sweep. */
    public static void enqueue(@NonNull Context ctx) {
        Intent i = new Intent(ctx.getApplicationContext(), TranscriptionService.class);
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.getApplicationContext().startForegroundService(i);
        } else {
            ctx.getApplicationContext().startService(i);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        store  = new TranscriptionStore(this);
        worker = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "transcription-worker");
            t.setDaemon(false);
            return t;
        });
        ensureChannel(this);
    }

    @Override
    public int onStartCommand(@Nullable Intent intent, int flags, int startId) {
        startForegroundCompat(buildOngoingNotification(getString(R.string.notification_title)));
        worker.execute(this::drainQueue);
        return START_NOT_STICKY;
    }

    @VisibleForTesting
    void drainQueue() {
        store.resweepRunningToPending();
        store.awaitIdle();

        while (!stopping.get()) {
            TranscriptionStore.Item next = pickOldestPending();
            if (next == null) break;
            processOne(next);
        }

        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignore) {
            stopForeground(true);
        }
        stopSelf();
    }

    /** Visible for tests: lets a test inject a store into a service-less instance. */
    @VisibleForTesting
    void setStoreForTest(TranscriptionStore replacement) {
        this.store = replacement;
    }

    private TranscriptionStore.Item pickOldestPending() {
        List<TranscriptionStore.Item> all = store.list();
        TranscriptionStore.Item oldest = null;
        for (TranscriptionStore.Item it : all) {
            if (it.status == TranscriptionStore.Status.PENDING) {
                if (oldest == null || it.createdAt < oldest.createdAt) oldest = it;
            }
        }
        return oldest;
    }

    private void processOne(TranscriptionStore.Item job) {
        updateOngoing(job);
        store.update(job.id, cur -> cur.toBuilder()
                .status(TranscriptionStore.Status.RUNNING).build());
        store.awaitIdle();

        try {
            if (job.audioPath == null) {
                throw new IOException("Job " + job.id + " has no audioPath");
            }
            File audioFile = new File(getFilesDir(), job.audioPath);
            byte[] audio = Files.readAllBytes(audioFile.toPath());
            TranscriptionEngine engine = activeFactory().create(this);
            String text = engine.transcribe(audio).trim();

            store.update(job.id, cur -> cur.toBuilder()
                    .status(TranscriptionStore.Status.DONE)
                    .transcript(text)
                    .errorMessage(null)
                    .build());
            store.awaitIdle();
            postResultNotification(job, text, /*success=*/true);
        } catch (Throwable t) {
            String msg = t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
            store.update(job.id, cur -> cur.toBuilder()
                    .status(TranscriptionStore.Status.ERROR)
                    .errorMessage(msg)
                    .build());
            store.awaitIdle();
            postResultNotification(job, msg, /*success=*/false);
        }
    }

    private Transcribers.Factory activeFactory() {
        return factoryOverride != null ? factoryOverride : Transcribers.DEFAULT;
    }

    @Override
    public void onDestroy() {
        stopping.set(true);
        if (worker != null) worker.shutdown();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ---- Notifications ---------------------------------------------------

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ID,
                ctx.getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription(ctx.getString(R.string.notification_channel_desc));
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private Notification buildOngoingNotification(String title) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(title)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setContentIntent(openMainActivityIntent())
                .build();
    }

    private void updateOngoing(TranscriptionStore.Item job) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        String title = getString(R.string.notification_title);
        if (!job.sourceLabel.isEmpty()) title += " · " + truncate(job.sourceLabel, 32);
        try {
            nm.notify(NOTIF_ID_ONGOING, buildOngoingNotification(title));
        } catch (SecurityException ignore) {
            // POST_NOTIFICATIONS denied; foreground status is independent.
        }
    }

    private void postResultNotification(TranscriptionStore.Item job,
                                        String body, boolean success) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        String title = getString(success
                ? R.string.notification_done_title
                : R.string.notification_failed_title);
        Notification n = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(success
                        ? android.R.drawable.stat_sys_download_done
                        : android.R.drawable.stat_notify_error)
                .setContentTitle(title)
                .setContentText(truncate(body, 80))
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(openMainActivityIntent())
                .build();
        try {
            nm.notify(NOTIF_ID_RESULT_BASE + Math.abs(job.id.hashCode() % 100_000), n);
        } catch (SecurityException ignore) {
        }
    }

    private PendingIntent openMainActivityIntent() {
        Intent i = new Intent(this, MainActivity.class)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        return PendingIntent.getActivity(this, 0, i, flags);
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    private void startForegroundCompat(Notification n) {
        if (Build.VERSION.SDK_INT >= 30) {
            startForeground(NOTIF_ID_ONGOING, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID_ONGOING, n);
        }
    }
}
