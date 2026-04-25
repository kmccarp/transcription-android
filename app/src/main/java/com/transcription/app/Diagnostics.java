package com.transcription.app;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import com.transcription.core.OllamaConfig;
import java.io.File;

/** Builds a single text blob describing the app + last-crash state. */
public final class Diagnostics {

    private Diagnostics() {}

    public static String build(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append("WA Transcribe diagnostics\n");
        sb.append("-----\n");

        // Package info
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            sb.append("Version:        ").append(pi.versionName)
                    .append(" (").append(versionCodeOf(pi)).append(")\n");
        } catch (PackageManager.NameNotFoundException nnf) {
            sb.append("Version:        unknown\n");
        }
        sb.append("Package:        ").append(ctx.getPackageName()).append("\n");
        sb.append("Android:        ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n");
        sb.append("Device:         ").append(Build.MANUFACTURER)
                .append(" ").append(Build.MODEL).append("\n");
        sb.append("ABI:            ").append(Build.SUPPORTED_ABIS.length > 0
                ? Build.SUPPORTED_ABIS[0] : "?").append("\n");

        // Settings
        OllamaConfig cfg = Prefs.load(ctx);
        sb.append("Ollama URL:     ").append(cfg.baseUrl()).append("\n");
        sb.append("Model:          ").append(cfg.model()).append("\n");
        sb.append("Prompt:         ").append(cfg.prompt()).append("\n");

        // Crash log
        File crash = CrashLog.file(ctx);
        sb.append("\nLast crash file: ").append(crash.getAbsolutePath()).append("\n");
        sb.append("Exists:          ").append(crash.exists())
                .append(" (").append(crash.length()).append(" bytes)\n");
        if (crash.exists() && crash.length() > 0) {
            sb.append("\n=== last-crash.log ===\n");
            String text = CrashLog.consume(ctx);
            sb.append(text != null ? text : "(failed to read)");
        } else {
            sb.append("\nNo persisted crash. If the app appears to be \"crashing\" but this\n");
            sb.append("file is empty, the OS likely killed the process via ANR / OOM /\n");
            sb.append("signal, which the JVM handler can't catch. Check the system\n");
            sb.append("\"App info\" → \"Force stop history\" or share this file along\n");
            sb.append("with steps to reproduce.\n");
        }
        return sb.toString();
    }

    @SuppressWarnings("deprecation")
    private static long versionCodeOf(PackageInfo pi) {
        if (Build.VERSION.SDK_INT >= 28) return pi.getLongVersionCode();
        return pi.versionCode;
    }
}
