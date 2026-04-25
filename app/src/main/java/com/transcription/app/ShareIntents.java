package com.transcription.app;

import android.content.Intent;
import android.net.Uri;
import android.os.Build;

/**
 * Pulls the audio {@link Uri} out of an {@link Intent#ACTION_SEND} payload.
 *
 * <p>Pure-function helper that makes share-intent parsing straightforward to
 * unit-test without standing up an Activity.
 */
public final class ShareIntents {

    private ShareIntents() {}

    /**
     * Returns the audio URI from a {@code SEND} intent, or {@code null} if
     * the intent is not a share, has no payload, or the payload isn't an
     * audio MIME type.
     */
    public static Uri extractAudioUri(Intent intent) {
        if (intent == null) return null;
        String action = intent.getAction();
        if (!Intent.ACTION_SEND.equals(action) && !Intent.ACTION_VIEW.equals(action)) {
            return null;
        }
        String type = intent.getType();
        if (type != null && !type.startsWith("audio/") && !"application/ogg".equals(type)) {
            // Be liberal with type matching: WhatsApp uses audio/ogg; some
            // file managers report application/ogg.
            return null;
        }
        Uri uri;
        if (Intent.ACTION_VIEW.equals(action)) {
            uri = intent.getData();
        } else if (Build.VERSION.SDK_INT >= 33) {
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri.class);
        } else {
            //noinspection deprecation
            uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        }
        return uri;
    }
}
