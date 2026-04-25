package com.transcription.app;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.io.File;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class CrashLogTest {

    private Context ctx;

    @Before public void setup() {
        ctx = ApplicationProvider.getApplicationContext();
        File f = CrashLog.file(ctx);
        if (f.exists()) //noinspection ResultOfMethodCallIgnored
            f.delete();
    }

    @Test public void consume_whenNoCrashFile_returnsNull() {
        assertNull(CrashLog.consume(ctx));
    }

    @Test public void install_persistsUncaughtExceptionAndConsumeReadsIt() throws Exception {
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        try {
            CrashLog.install(ctx);
            Thread.UncaughtExceptionHandler installed =
                    Thread.getDefaultUncaughtExceptionHandler();
            assertNotNull(installed);

            RuntimeException boom = new IllegalStateException("test crash");
            installed.uncaughtException(Thread.currentThread(), boom);

            String text = CrashLog.consume(ctx);
            assertNotNull(text);
            assertTrue(text, text.contains("IllegalStateException"));
            assertTrue(text, text.contains("test crash"));
            // consume() removes the file:
            assertFalse(CrashLog.file(ctx).exists());
            // and it now returns null:
            assertNull(CrashLog.consume(ctx));
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(prev);
        }
    }

    @Test public void install_isIdempotent_andDelegatesToPreviousHandler() {
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        try {
            int[] called = {0};
            Thread.setDefaultUncaughtExceptionHandler((t, e) -> called[0]++);
            CrashLog.install(ctx);

            Thread.getDefaultUncaughtExceptionHandler()
                    .uncaughtException(Thread.currentThread(),
                            new RuntimeException("nested"));
            assertEquals(1, called[0]);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(prev);
        }
    }
}
