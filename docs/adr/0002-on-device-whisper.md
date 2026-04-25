# ADR 0002 — Pivot to on-device Whisper.cpp

| Status   | Date       | Authors |
|----------|------------|---------|
| Accepted | 2026-04-25 | rev. by @kmccarp |

## Context

ADR 0001 chose Ollama as the transcription backend. After getting an
APK in the user's hands the architecture broke down on real devices:

1. Ollama is a desktop server. There is no Ollama Android port — what
   the app was doing was *talking* to Ollama running on a separate
   machine. The user's actual requirement was "self-contained on the
   phone", which an HTTP client to a separate server doesn't satisfy.
2. The first install also crashed with
   `NoClassDefFoundError: java.net.http.HttpClient`. JDK 11's
   `java.net.http` package is not part of the Android runtime; we
   discovered this only because the unit tests pass on the JVM. Fixed
   by switching to `HttpURLConnection` (commit `e8e79e3`), but the
   underlying architectural problem remained.

The user clarified: the model **must** run on the phone, and the app
**must** transcribe in airplane mode.

## Decision

Replace the network-bound transcription engine with an on-device
implementation:

- Vendor [whisper.cpp](https://github.com/ggerganov/whisper.cpp) via
  CMake `FetchContent` at NDK build time.
- Ship a quantized Whisper model (default
  `ggml-tiny-q5_1.bin`, ~31 MB) inside the APK assets, downloaded by
  the `:app:downloadWhisperModel` Gradle task and pinnable by SHA-256.
- Decode the shared audio with Android's `MediaExtractor` +
  `MediaCodec` — the platform already supports Ogg/Opus, MP3, M4A,
  AAC, and FLAC, so we ride that decoder for free and resample to
  16 kHz mono with a small linear-interpolation kernel.
- Hand the float PCM samples to a tiny C++ JNI shim that calls
  `whisper_full` and returns UTF-8 text.

Ollama remains as an optional backend for users who already self-host
it on their LAN: a `ListPreference` in Settings switches between
`local` (default) and `ollama`. The whole `:core` HTTP path is
preserved.

## Why these choices

- **Whisper.cpp over TensorFlow Lite Whisper.** TFLite Whisper exists
  but is more limited (only `tiny` ships well, the runtime is heavier,
  the operator coverage lags upstream). Whisper.cpp has tighter
  Android docs, an actively maintained NDK example, GGUF support, and
  a much smaller native footprint.
- **Quantized tiny over full-precision.** Q5_1 tiny is ~31 MB vs
  ~75 MB. Accuracy loss on conversational audio is in the noise; the
  size win is real and APK size is the main user-visible cost.
- **Bundle in APK over first-run download.** First-run download means
  "doesn't work in airplane mode immediately after install" which
  sabotages the whole point. APK size is a one-time cost, model load
  happens from local storage thereafter.
- **MediaCodec for decoding.** Android already has Opus/AAC/MP3
  decoders mandated by CDD; reimplementing them in our APK doubles
  the binary and adds attack surface for no benefit.

## Consequences

- The APK ships native code (`arm64-v8a` and `x86_64` only — drop the
  legacy 32-bit ABIs to keep the APK size manageable).
- `:core` stays Android-free and continues to be unit-tested on the
  JVM. The Whisper engine lives in `:app` because it depends on
  `MediaExtractor`/`MediaCodec` and `System.loadLibrary`.
- Robolectric can't run the native library, so `:app` tests inject
  fake engines via `MainActivity.setEngineFactory`. The
  `WhisperLocalEngine` itself is exercised manually on a device and
  by an instrumented test if/when we add one.
- Build time on a fresh CI runner grows by ~3–5 minutes for the
  Whisper compile (FetchContent + CMake). Subsequent runs benefit
  from Gradle's build cache.

## Alternatives considered

- **Termux + Ollama on-device.** Technically works (Ollama can be
  built for Android via Termux). User has to install another app, set
  up a chroot, run a server. Rejected: defeats the "share, get a
  transcript" UX.
- **Faster-whisper / CTranslate2.** Better than vanilla whisper.cpp on
  desktop, but the Android story is much rougher. Not worth the
  porting effort for the transcript quality difference.
- **Streaming partial transcripts.** Nice but premature. WhatsApp PTTs
  are usually <30 s, transcribed in well under a second on a modern
  arm64 phone with the tiny model. We'll revisit if users start
  sharing 10-minute meeting recordings.

## Updates to coding rules

- `:core` may not import `java.net.http.*`. Use `HttpURLConnection`.
- Anything that imports `android.media.*`, JNI, or asset loading
  belongs in `:app`. The `:core/:app` line is now hard-enforced.
