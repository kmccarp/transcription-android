package com.transcription.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import android.content.Intent;
import android.net.Uri;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class ShareIntentsTest {

    private static final Uri AUDIO = Uri.parse(
            "content://com.whatsapp.provider.media/item/PTT-20260425-WA0059.opus");

    @Test public void nullIntent_returnsNull() {
        assertNull(ShareIntents.extractAudioUri(null));
    }

    @Test public void wrongAction_returnsNull() {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.setType("audio/ogg");
        i.putExtra(Intent.EXTRA_STREAM, AUDIO);
        assertNull(ShareIntents.extractAudioUri(i));
    }

    @Test public void send_audioOgg_returnsUri() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("audio/ogg");
        i.putExtra(Intent.EXTRA_STREAM, AUDIO);
        assertEquals(AUDIO, ShareIntents.extractAudioUri(i));
    }

    @Test public void send_audioWildcard_returnsUri() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("audio/mp3");
        i.putExtra(Intent.EXTRA_STREAM, AUDIO);
        assertEquals(AUDIO, ShareIntents.extractAudioUri(i));
    }

    @Test public void send_applicationOgg_alsoAccepted() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("application/ogg");
        i.putExtra(Intent.EXTRA_STREAM, AUDIO);
        assertEquals(AUDIO, ShareIntents.extractAudioUri(i));
    }

    @Test public void send_nonAudioType_returnsNull() {
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("image/png");
        i.putExtra(Intent.EXTRA_STREAM, AUDIO);
        assertNull(ShareIntents.extractAudioUri(i));
    }

    @Test public void send_nullType_isAccepted() {
        // Some senders omit the MIME type. We trust the EXTRA_STREAM in that
        // case and rely on the audio decoder to surface a real format error.
        Intent i = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, AUDIO);
        assertEquals(AUDIO, ShareIntents.extractAudioUri(i));
    }

    @Test public void view_audioOgg_returnsData() {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(AUDIO, "audio/ogg");
        assertEquals(AUDIO, ShareIntents.extractAudioUri(i));
    }

    @Test public void send_withoutExtra_returnsNull() {
        Intent i = new Intent(Intent.ACTION_SEND).setType("audio/ogg");
        assertNull(ShareIntents.extractAudioUri(i));
    }
}
