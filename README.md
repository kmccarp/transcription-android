# transcription-android

A tiny Android app that turns WhatsApp voice notes (and any other shared
audio) into text. **The model runs on the phone** — no internet
required, no audio ever leaves the device. A foreground service does
the work, so transcription survives backgrounding the app, and a
RecyclerView history keeps every past transcript a tap away.

> Long-press a WhatsApp voice note → **Share** → **WA Transcribe** → the
> row appears as **Queued**, flips to **Transcribing**, and lands on
> **Done** with the text you can copy / share / retry / delete.

The default backend is [whisper.cpp](https://github.com/ggerganov/whisper.cpp)
with a quantized "tiny" model bundled into the APK. An optional Ollama
backend is also wired up for users who'd rather hand the audio off to a
server they already run.

## Status

Early. The project ships with:

- A pure-Java transcription engine (`:core`) with ~100% line coverage.
- A minimal Android share-target UI (`:app`).
- A GitHub Actions workflow that builds, tests, and uploads a debug APK.

There is no signing config and no Play Store presence. Side-load the debug
APK from CI artifacts.

## Architecture in one paragraph

`:core` is a plain JVM library with zero runtime dependencies. It exposes
`TranscriptionEngine` (a single-method interface) and one implementation,
`OllamaTranscriptionEngine`, which posts base64 audio to
`POST /api/generate` and parses the `response` field out of the reply.
`:app` is a one-Activity Android shell that pulls a `Uri` out of an
`ACTION_SEND` intent, reads the bytes via `ContentResolver`, hands them to
the engine on a background thread, and shows the result. That's it.

For the *why*, see [`docs/adr/0001-design.md`](docs/adr/0001-design.md).

## Running locally

You need:

- JDK 17+ (we test on JDK 17 in CI; JDK 21 also works).
- Android SDK with platform 34 (only required to build the APK or run
  `:app` tests; pure `:core` work just needs the JDK).
- An Android device or emulator running API 24 (Android 7.0) or newer.
- An Ollama server somewhere your phone can reach, with an audio-capable
  model installed (we recommend `dimavz/whisper-tiny` to start).

### 1. Install Ollama and pull a model

```bash
# On the machine that will host transcription
ollama pull dimavz/whisper-tiny
ollama serve   # listens on :11434
```

If you'll run the app on the **emulator**, the default base URL
`http://10.0.2.2:11434` is the emulator's loopback to your host. If you
run on a **physical phone**, change the base URL in **Settings** to your
host's LAN IP (e.g. `http://192.168.1.50:11434`).

### 2. Build and run

```bash
./gradlew :core:test -PskipApp           # core unit tests, no SDK required
./gradlew ciAll                          # everything CI runs
./gradlew ciAssemble                     # just the debug APK
adb install app/build/outputs/apk/debug/app-debug.apk
```

Then in WhatsApp: long-press a voice note → **Share** → **WA Transcribe**.

### 3. Run from your IDE

Open the project in Android Studio (Hedgehog or newer). Run the `app`
configuration. The first build will take a few minutes while AGP, the
SDK platform, and dependencies download.

## CI tasks

> **Anything CI does is a Gradle task you can run locally.** No "magic"
> shell logic inside the workflow file. If a contributor wants to
> reproduce a CI failure, they should be able to do so without reading
> any YAML.

| Task                | What it does                                      |
|---------------------|---------------------------------------------------|
| `./gradlew ciCheck` | All tests + lint + JaCoCo coverage verification   |
| `./gradlew ciCheckCore` | `:core` only — works without the Android SDK   |
| `./gradlew ciAssemble` | Builds `app-debug.apk`                          |
| `./gradlew ciAll`   | `ciCheck` + `ciAssemble`                          |

The GitHub workflow at [`.github/workflows/build.yml`](.github/workflows/build.yml)
runs exactly these. If you find yourself reaching for `run: ` inside
that file with anything more than `./gradlew <task>`, push that logic
into a Gradle task instead.

## Project layout

```
.
├── core/                            # pure Java, zero deps
│   └── src/
│       ├── main/java/com/transcription/core/
│       │   ├── TranscriptionEngine.java
│       │   ├── OllamaTranscriptionEngine.java
│       │   ├── OllamaConfig.java
│       │   ├── AudioBytes.java
│       │   ├── TranscriptionException.java
│       │   └── Json.java
│       └── test/...                 # JUnit + MockWebServer
├── app/                             # Android module
│   ├── src/main/...
│   └── src/test/...                 # Robolectric
├── .github/workflows/build.yml
├── docs/adr/0001-design.md
├── CLAUDE.md
└── README.md
```

## Picking a model

Ollama doesn't ship a first-party Whisper model — audio piggybacks on the
`images` array of `/api/generate`. Community models that work with this
contract:

| Model                       | Size | Notes                                           |
|-----------------------------|-----:|-------------------------------------------------|
| `dimavz/whisper-tiny`       | ~80MB | Recommended default. Fast, multilingual.        |
| `karanchopda333/whisper`    | ~150MB | More accurate; slower on a CPU-only host.      |
| any other model that accepts audio in the `images` field | — | Set the model name in **Settings**. |

If Ollama eventually ships first-party audio support, the app should keep
working — point it at a different model name.

## Contributing

1. Fork, branch off `main`.
2. Make your change. Keep `:core` Android-free; if you find yourself
   reaching for an `android.*` import, the code probably belongs in `:app`.
3. Add tests. We aim for **100% line coverage on `:core`** and have a
   floor of 95% line / 90% branch enforced by JaCoCo. The audio fixture
   at `core/src/test/resources/fixtures/sample-whatsapp-ptt.opus` is a real
   WhatsApp PTT file — use it to round-trip your changes.
4. Run `./gradlew ciAll` (or `./gradlew ciCheckCore -PskipApp` if you
   don't have the SDK) and make sure it's green.
5. Open a PR. CI will run the same commands.

If you're using an AI assistant (Claude Code, etc.), see
[`CLAUDE.md`](CLAUDE.md) for the conventions it should follow.

### Common gotchas

- **`./gradlew :core:test` failing with "could not resolve plugin
  com.android.application"?** Add `-PskipApp` (or install the Android SDK
  and create `local.properties` with `sdk.dir=…`).
- **`ConnectException` when hitting Ollama from the emulator?** Make sure
  Ollama is bound to all interfaces (`OLLAMA_HOST=0.0.0.0:11434 ollama
  serve`), and use `http://10.0.2.2:11434` from inside the emulator.
- **Cleartext traffic blocked on a real device?** Add your Ollama host's
  domain to `app/src/main/res/xml/network_security_config.xml`, or front
  Ollama with HTTPS.

## License

Apache 2.0 — see `LICENSE` (TBD; pick something before publishing).
