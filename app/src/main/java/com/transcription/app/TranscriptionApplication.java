package com.transcription.app;

import android.app.Application;

/** Installs the crash logger before any Activity gets to run. */
public class TranscriptionApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CrashLog.install(this);
    }
}
