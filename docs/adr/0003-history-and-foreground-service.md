# ADR 0003 — Persistent transcript history and foreground transcription

| Status   | Date       | Authors  |
|----------|------------|----------|
| Proposed | 2026-04-25 | @kmccarp |

## Context

After ADR 0002 we have a working on-device transcription pipeline, but
the first end-to-end test on a real phone exposed four UX-level
problems:

1. The status string still reads "Transcribing via Ollama…" even when
   the local Whisper engine is doing the work — leftover string from
   the pre-pivot UI.
2. Transcription "never finished" once the user backgrounded the app.
   The work runs on a single-threaded `ExecutorService` owned by
   `MainActivity`. As soon as the user navigates away, Android can —
   and on this device, did — kill the process. There's nothing to come
   back to: the result vanishes with the activity.
3. Tapping the **Diagnostics** menu item rewrites `statusText` and
   `resultText` in place. When the user came back from a casual peek
   at diagnostics, the in-progress / completed transcript was already
   overwritten.
4. Closing and reopening the app shows the idle screen — there's no
   record that anything was ever transcribed. There is nowhere to
   look up an earlier transcript.

The user's bottom-line ask: "we need a history of transcriptions so we
can access previous ones, and so it can transcribe even when it's in
the background".

## Decision (proposed)

Treat each share as a durable **TranscriptionJob** that survives
process death. The activity becomes a thin viewer; a foreground
service does the work. The diagnostics page moves out of `MainActivity`.

### Data model

A single JSON file at `filesDir/transcripts.json`, written atomically
(write `transcripts.json.tmp`, fsync, rename). One array of records:

```json
{ "schema": 1,
  "items": [
    {
      "id":         "01J9PXC3R7Z4S6Q3K1NMRGK1FB",  // ULID-ish, lex-sortable
      "createdAt":  1714085210000,
      "updatedAt":  1714085218000,
      "status":     "PENDING|RUNNING|DONE|ERROR",
      "engine":     "local|ollama",
      "language":   "auto",
      "sourceLabel":"PTT-20260425-WA0059.opus",
      "audioPath":  "audio/01J9PXC3R7Z4S6Q3K1NMRGK1FB.bin",
      "transcript": "...",
      "errorMessage": null
    }
  ]
}
```

`audioPath` is relative to `filesDir` and points at a copy of the
shared bytes that we keep until the entry is deleted, so a "Retry"
action is possible after a failure or model swap.

A new class **`TranscriptionStore`** owns the file:

- `add(job)` / `update(id, mutator)` / `delete(id)` / `clear()`
- `list()` returns a snapshot, ordered newest-first
- `observe(listener)` notifies registered listeners on the main
  thread when the file changes
- All writes go through a single-thread executor; the file format
  carries a `schema` integer so a future migration is straightforward
- No Room, no SQLite — the data is small (≤ tens of entries) and
  we already have a JSON helper in `:core`

### Foreground service

A new **`TranscriptionService`** (a plain `android.app.Service` with
`startForegroundService` semantics):

- Receives `Intent` carrying the job id (the audio bytes are already
  on disk via `TranscriptionStore`).
- Calls `startForeground(notifId, ongoing notification)` with
  service type `dataSync` (API 34 requires a typed foreground service).
- Picks the job up from the store, transitions `PENDING → RUNNING`,
  decodes audio and runs the engine. On completion: `DONE` (with
  transcript + final notification "Transcript ready" linking back to
  the activity) or `ERROR`.
- Drains an in-memory queue: if a second share lands while one is
  running, it joins the queue and the service stays foreground until
  the queue is empty.
- Stops itself with `stopForeground(STOP_FOREGROUND_REMOVE)` and
  `stopSelf()` after the last job.

Notification channel `transcription`, importance `LOW` (silent),
declared in `TranscriptionApplication.onCreate()`.

`POST_NOTIFICATIONS` permission requested at runtime on API 33+,
falling back to "no-notification mode" if the user denies — the
service still runs; it just won't show progress.

### Activity changes

`MainActivity`:
- Layout becomes a toolbar + `RecyclerView` (history) + an empty
  state view, instead of a single text card.
- On `ACTION_SEND`: copy the audio to `audioPath`, insert a
  `PENDING` row in the store, `startForegroundService(intent)`. The
  list updates immediately because the activity observes the store.
- Tapping a row opens **`TranscriptActivity`** which shows the full
  transcript with **Copy** / **Share** / **Retry** / **Delete**.
- A "Clear history" overflow item.

`DiagnosticsActivity` (new):
- Hosts the existing `Diagnostics.build(this)` text in its own screen
  with **Copy** and **Share** buttons. No more clobbering the home
  view.

`SettingsActivity`: unchanged.

### Threading

- Disk I/O for `TranscriptionStore` runs on a single-thread executor
  inside the store class (writes serialised, reads cheap).
- Engine work runs on a single-thread executor inside
  `TranscriptionService` so two simultaneous shares queue rather
  than fighting for CPU.
- UI observes the store via a `BroadcastReceiver` (or
  `LocalBroadcastManager` deprecated → use `Context.registerReceiver`
  with a private action) emitted by the store. The activity unregisters
  in `onPause`.

### Persistence vs. process death

- Crash mid-transcription: the row stays `RUNNING` in the file. On
  next service start we resweep `RUNNING` to `PENDING` (best-effort
  retry). The user can also explicitly **Retry** from the row UI.
- Crash mid-write of `transcripts.json`: rename-after-fsync semantics
  mean the previous good copy stays in place.

### Permissions / manifest

- Add `<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>`
  (API 33+).
- Add `<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>`.
- Add `<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>`
  (API 34+).
- `<service android:name=".TranscriptionService"
            android:foregroundServiceType="dataSync"
            android:exported="false"/>`.
- Drop `INTERNET` from the *common* permission set; keep it only when
  the optional Ollama backend is enabled. In practice we keep it
  declared but the manifest comment makes it clear it's for the
  optional remote engine only.

### Tests

Each piece is unit-testable on the JVM via Robolectric:

- `TranscriptionStoreTest`: round-trip add/update/list/delete; corrupt
  file recovery; concurrent writes.
- `TranscriptionServiceTest`: with the engine factory injected, drive
  through `Robolectric.buildService(...)` and assert state
  transitions on the store.
- `MainActivityTest`: simplified — just observes the store and
  renders. Most existing tests get replaced by `TranscriptActivityTest`.

We will *not* add instrumented tests in this ADR — the manual
end-to-end share path remains the integration test of record.

### What we are NOT doing in this iteration

- No streaming partial transcripts. Whisper still runs the whole clip
  before any text appears in the row.
- No Room or SQLite. The JSON store is sufficient for the volume.
- No multi-select, no search, no export-as-CSV — the row affordances
  (Copy / Share / Delete) cover the bases.
- No replay-from-audio inside the app — `audioPath` exists for Retry
  only; we don't ship a player.
- No model picker UI — the existing `engine`/`language`/`threads`
  preferences cover what a phone user actually wants to change.

## Consequences

Pros:

- Transcription survives backgrounding, screen-off, and short
  process kills.
- A history view turns a one-shot tool into something the user can
  actually use as a record.
- Diagnostics no longer trample the foreground content.
- The crash-log path from ADR 0001 still works for fatal errors;
  per-job errors are now also visible via the row's `errorMessage`.

Cons / costs:

- Significant `MainActivity` rewrite. Robolectric tests largely
  replaced.
- New permission prompt on Android 13+ for notifications. We treat a
  denial as "no progress notification, keep transcribing"; the
  service stays alive because foreground service status is granted
  separately.
- More disk I/O. Negligible at the expected volume (≤ a few KB per
  transcript + the audio copy).

## Alternatives considered

- **Keep work in `MainActivity` + `WorkManager` for backgrounding.**
  WorkManager schedules things; it doesn't do foreground work with
  immediate user feedback. Wrong fit.
- **Coroutines / Kotlin.** The codebase is Java by policy
  (CLAUDE.md). Not for this PR.
- **Room database.** Right answer if we ever grow to thousands of
  transcripts or add search; today it's overkill.
- **Skip persistence, only fix the foreground-service issue.**
  Rejected: history is the user's primary stated requirement.

## Implementation order (if accepted)

1. `TranscriptionStore` + tests.
2. `TranscriptionService` + tests.
3. New `MainActivity` layout + `TranscriptActivity` + adapter.
4. Move Diagnostics into `DiagnosticsActivity`.
5. Wire share intent → store → service → notification.
6. Strings, manifest, permissions, runtime prompt.
7. Update existing tests / add new ones.
8. README + CLAUDE.md updates.

Each step is its own commit so CI catches any regression early.
