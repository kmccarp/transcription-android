package com.transcription.app;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Persistent, single-process JSON-backed history of transcription jobs.
 *
 * <p>One file at {@code filesDir/transcripts.json}. Every mutation
 * serialises through a single-threaded executor and is written
 * atomically via {@code transcripts.json.tmp} + rename. See ADR 0003.
 */
public final class TranscriptionStore {

    /** Lifecycle state for a single transcription job. */
    public enum Status { PENDING, RUNNING, DONE, ERROR }

    private static final String FILE_NAME    = "transcripts.json";
    private static final String TMP_FILE     = "transcripts.json.tmp";
    private static final int    SCHEMA_V     = 1;

    private final File file;
    private final ExecutorService io =
            Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "transcripts-io");
                t.setDaemon(true);
                return t;
            });
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    public TranscriptionStore(@NonNull Context ctx) {
        this(new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME));
    }

    /** Visible for tests. */
    TranscriptionStore(@NonNull File file) {
        this.file = file;
    }

    /** A single transcription job. Immutable; use {@link #toBuilder()}. */
    public static final class Item {
        public final String id;
        public final long   createdAt;
        public final long   updatedAt;
        public final Status status;
        public final String engine;
        public final String language;
        public final String sourceLabel;
        @Nullable public final String audioPath;
        @Nullable public final String transcript;
        @Nullable public final String errorMessage;

        Item(String id, long createdAt, long updatedAt, Status status,
             String engine, String language, String sourceLabel,
             @Nullable String audioPath, @Nullable String transcript,
             @Nullable String errorMessage) {
            this.id           = id;
            this.createdAt    = createdAt;
            this.updatedAt    = updatedAt;
            this.status       = status;
            this.engine       = engine;
            this.language     = language;
            this.sourceLabel  = sourceLabel;
            this.audioPath    = audioPath;
            this.transcript   = transcript;
            this.errorMessage = errorMessage;
        }

        public Builder toBuilder() {
            return new Builder()
                    .id(id).createdAt(createdAt).updatedAt(updatedAt)
                    .status(status).engine(engine).language(language)
                    .sourceLabel(sourceLabel).audioPath(audioPath)
                    .transcript(transcript).errorMessage(errorMessage);
        }

        public static Builder pending(String engine, String language, String sourceLabel) {
            long now = System.currentTimeMillis();
            return new Builder()
                    .id(generateId())
                    .createdAt(now).updatedAt(now)
                    .status(Status.PENDING)
                    .engine(engine).language(language)
                    .sourceLabel(sourceLabel);
        }

        /** Time-prefixed UUID, lex-sortable by createdAt for short windows. */
        private static String generateId() {
            String hex = String.format(Locale.US, "%013x", System.currentTimeMillis());
            String rand = UUID.randomUUID().toString().replace("-", "").substring(0, 11);
            return hex + "-" + rand;
        }

        public static final class Builder {
            String id;
            long   createdAt;
            long   updatedAt;
            Status status = Status.PENDING;
            String engine = "local";
            String language = "auto";
            String sourceLabel = "";
            @Nullable String audioPath;
            @Nullable String transcript;
            @Nullable String errorMessage;

            public Builder id(String v) { id = v; return this; }
            public Builder createdAt(long v) { createdAt = v; return this; }
            public Builder updatedAt(long v) { updatedAt = v; return this; }
            public Builder status(Status v) { status = v; return this; }
            public Builder engine(String v) { engine = v; return this; }
            public Builder language(String v) { language = v; return this; }
            public Builder sourceLabel(String v) { sourceLabel = v; return this; }
            public Builder audioPath(@Nullable String v) { audioPath = v; return this; }
            public Builder transcript(@Nullable String v) { transcript = v; return this; }
            public Builder errorMessage(@Nullable String v) { errorMessage = v; return this; }

            public Item build() {
                if (id == null || id.isEmpty()) id = generateId();
                if (createdAt == 0) createdAt = System.currentTimeMillis();
                if (updatedAt == 0) updatedAt = createdAt;
                return new Item(id, createdAt, updatedAt, status, engine, language,
                        sourceLabel, audioPath, transcript, errorMessage);
            }
        }
    }

    /** Mutator passed to {@link #update(String, Mutator)}. */
    public interface Mutator {
        Item apply(Item current);
    }

    /** Receives change notifications. Always called on the IO thread. */
    public interface Listener {
        void onChanged(@NonNull List<Item> snapshot);
    }

    public void registerListener(@NonNull Listener l) { listeners.add(l); }
    public void unregisterListener(@NonNull Listener l) { listeners.remove(l); }

    /** Returns a snapshot, newest-first. Blocking read. */
    public synchronized @NonNull List<Item> list() {
        try {
            return readUnlocked();
        } catch (IOException ioe) {
            return new ArrayList<>();
        }
    }

    /** Returns one item by id, or null. Blocking read. */
    public @Nullable Item get(@NonNull String id) {
        for (Item it : list()) {
            if (id.equals(it.id)) return it;
        }
        return null;
    }

    /** Inserts a new item at the head. Returns immediately; write is async. */
    public void add(@NonNull Item item) {
        io.execute(() -> {
            try {
                List<Item> all = readUnlocked();
                all.removeIf(x -> x.id.equals(item.id));
                all.add(item);
                writeUnlocked(all);
                notifyListeners(all);
            } catch (IOException ignore) {
                // Best-effort; the next successful write will reconcile.
            }
        });
    }

    /** Apply {@code mutator} to a single item if present. Async. */
    public void update(@NonNull String id, @NonNull Mutator mutator) {
        io.execute(() -> {
            try {
                List<Item> all = readUnlocked();
                boolean changed = false;
                for (int i = 0; i < all.size(); i++) {
                    if (all.get(i).id.equals(id)) {
                        Item replacement = mutator.apply(all.get(i)).toBuilder()
                                .updatedAt(System.currentTimeMillis())
                                .build();
                        all.set(i, replacement);
                        changed = true;
                        break;
                    }
                }
                if (changed) {
                    writeUnlocked(all);
                    notifyListeners(all);
                }
            } catch (IOException ignore) {
            }
        });
    }

    public void delete(@NonNull String id) {
        io.execute(() -> {
            try {
                List<Item> all = readUnlocked();
                if (all.removeIf(it -> it.id.equals(id))) {
                    writeUnlocked(all);
                    notifyListeners(all);
                }
            } catch (IOException ignore) {
            }
        });
    }

    public void clear() {
        io.execute(() -> {
            try {
                writeUnlocked(new ArrayList<>());
                notifyListeners(Collections.emptyList());
            } catch (IOException ignore) {
            }
        });
    }

    /**
     * Crash recovery: any items left in {@link Status#RUNNING} are flipped
     * back to {@link Status#PENDING} so the service picks them up again on
     * next launch. Called by the service before it starts dequeueing.
     */
    public void resweepRunningToPending() {
        io.execute(() -> {
            try {
                List<Item> all = readUnlocked();
                boolean changed = false;
                long now = System.currentTimeMillis();
                for (int i = 0; i < all.size(); i++) {
                    Item it = all.get(i);
                    if (it.status == Status.RUNNING) {
                        all.set(i, it.toBuilder().status(Status.PENDING)
                                .updatedAt(now).build());
                        changed = true;
                    }
                }
                if (changed) {
                    writeUnlocked(all);
                    notifyListeners(all);
                }
            } catch (IOException ignore) {
            }
        });
    }

    /** For tests: blocks until all enqueued IO has run. */
    void awaitIdle() {
        try {
            io.submit(() -> {}).get();
        } catch (Exception ignore) {
        }
    }

    // ---- IO --------------------------------------------------------------

    private List<Item> readUnlocked() throws IOException {
        if (!file.exists() || file.length() == 0) return new ArrayList<>();
        byte[] bytes = Files.readAllBytes(file.toPath());
        String text = new String(bytes, StandardCharsets.UTF_8);
        try {
            JSONObject root = new JSONObject(text);
            JSONArray arr = root.optJSONArray("items");
            if (arr == null) return new ArrayList<>();
            List<Item> out = new ArrayList<>(arr.length());
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                out.add(itemFromJson(o));
            }
            // Newest-first.
            out.sort(Comparator.<Item>comparingLong(it -> it.createdAt).reversed());
            return out;
        } catch (JSONException je) {
            // Treat a corrupt file as "empty" but back it up so an investigator
            // can recover it later.
            File backup = new File(file.getParentFile(), FILE_NAME + ".corrupt");
            //noinspection ResultOfMethodCallIgnored
            file.renameTo(backup);
            return new ArrayList<>();
        }
    }

    private void writeUnlocked(List<Item> items) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists()) //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        File tmp = new File(parent, TMP_FILE);
        try {
            JSONObject root = new JSONObject();
            root.put("schema", SCHEMA_V);
            JSONArray arr = new JSONArray();
            for (Item it : items) arr.put(itemToJson(it));
            root.put("items", arr);
            byte[] bytes = root.toString(2).getBytes(StandardCharsets.UTF_8);
            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(bytes);
                fos.getFD().sync();
            }
            if (!tmp.renameTo(file)) {
                throw new IOException("Atomic rename of " + tmp + " -> " + file + " failed");
            }
        } catch (JSONException je) {
            throw new IOException("Failed to serialise transcripts.json", je);
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static Item itemFromJson(JSONObject o) throws JSONException {
        return new Item.Builder()
                .id(o.getString("id"))
                .createdAt(o.getLong("createdAt"))
                .updatedAt(o.optLong("updatedAt", o.getLong("createdAt")))
                .status(Status.valueOf(o.optString("status", "PENDING")))
                .engine(o.optString("engine", "local"))
                .language(o.optString("language", "auto"))
                .sourceLabel(o.optString("sourceLabel", ""))
                .audioPath(o.has("audioPath") && !o.isNull("audioPath")
                        ? o.getString("audioPath") : null)
                .transcript(o.has("transcript") && !o.isNull("transcript")
                        ? o.getString("transcript") : null)
                .errorMessage(o.has("errorMessage") && !o.isNull("errorMessage")
                        ? o.getString("errorMessage") : null)
                .build();
    }

    private static JSONObject itemToJson(Item it) throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id",          it.id);
        o.put("createdAt",   it.createdAt);
        o.put("updatedAt",   it.updatedAt);
        o.put("status",      it.status.name());
        o.put("engine",      it.engine);
        o.put("language",    it.language);
        o.put("sourceLabel", it.sourceLabel);
        if (it.audioPath != null)    o.put("audioPath", it.audioPath);
        if (it.transcript != null)   o.put("transcript", it.transcript);
        if (it.errorMessage != null) o.put("errorMessage", it.errorMessage);
        return o;
    }

    private void notifyListeners(List<Item> snapshot) {
        List<Item> copy = Collections.unmodifiableList(new ArrayList<>(snapshot));
        for (Listener l : listeners) l.onChanged(copy);
    }
}
