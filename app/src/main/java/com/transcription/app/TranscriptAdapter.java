package com.transcription.app;

import android.text.format.DateUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

/** RecyclerView adapter for {@link TranscriptionStore.Item} rows. */
public final class TranscriptAdapter
        extends RecyclerView.Adapter<TranscriptAdapter.VH> {

    /** Tap-on-row callback. */
    public interface OnRowClick {
        void onClick(@NonNull TranscriptionStore.Item item);
    }

    private final List<TranscriptionStore.Item> items = new ArrayList<>();
    private final OnRowClick onClick;

    public TranscriptAdapter(@NonNull OnRowClick onClick) {
        this.onClick = onClick;
        setHasStableIds(true);
    }

    public void replaceAll(@NonNull List<TranscriptionStore.Item> snapshot) {
        items.clear();
        items.addAll(snapshot);
        notifyDataSetChanged();
    }

    @Override public long getItemId(int pos) {
        return items.get(pos).id.hashCode();
    }

    @NonNull @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.list_item_transcript, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        TranscriptionStore.Item it = items.get(pos);
        h.label.setText(it.sourceLabel.isEmpty() ? "(audio)" : it.sourceLabel);
        h.timestamp.setText(DateUtils.getRelativeTimeSpanString(it.createdAt));
        h.status.setText(statusBadge(it));
        switch (it.status) {
            case DONE:
                h.snippet.setText(it.transcript == null ? "" : it.transcript);
                break;
            case ERROR:
                h.snippet.setText(it.errorMessage == null ? "(error)" : it.errorMessage);
                break;
            case RUNNING:
                h.snippet.setText(R.string.status_transcribing);
                break;
            case PENDING:
            default:
                h.snippet.setText(R.string.status_pending);
                break;
        }
        h.itemView.setOnClickListener(v -> onClick.onClick(it));
    }

    private static String statusBadge(TranscriptionStore.Item it) {
        switch (it.status) {
            case DONE:    return "✓";
            case ERROR:   return "⚠";
            case RUNNING: return "…";
            case PENDING:
            default:      return "•";
        }
    }

    @Override public int getItemCount() { return items.size(); }

    static final class VH extends RecyclerView.ViewHolder {
        final TextView label, status, timestamp, snippet;
        VH(@NonNull View v) {
            super(v);
            label     = v.findViewById(R.id.itemLabel);
            status    = v.findViewById(R.id.itemStatus);
            timestamp = v.findViewById(R.id.itemTimestamp);
            snippet   = v.findViewById(R.id.itemSnippet);
        }
    }
}
