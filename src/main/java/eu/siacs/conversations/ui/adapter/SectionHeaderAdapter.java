package eu.siacs.conversations.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import eu.siacs.conversations.R;

/**
 * A single-row (or empty) RecyclerView adapter used as a lightweight section divider inside a
 * {@link androidx.recyclerview.widget.ConcatAdapter}. When the title is {@code null} the adapter
 * reports zero items, so a section header simply disappears when its section is empty.
 */
public class SectionHeaderAdapter
        extends RecyclerView.Adapter<SectionHeaderAdapter.HeaderViewHolder> {

    private final long stableId;
    private CharSequence title;
    private CharSequence count;

    public SectionHeaderAdapter(final long stableId) {
        this.stableId = stableId;
        setHasStableIds(true);
    }

    /** Set the header text and an optional trailing count. A {@code null} title hides the header. */
    public void setHeader(final CharSequence title, final CharSequence count) {
        final boolean wasShown = this.title != null;
        final boolean show = title != null;
        this.title = title;
        this.count = count;
        if (wasShown && show) {
            notifyItemChanged(0);
        } else if (show) {
            notifyItemInserted(0);
        } else if (wasShown) {
            notifyItemRemoved(0);
        }
    }

    @Override
    public long getItemId(final int position) {
        return stableId;
    }

    @Override
    public int getItemCount() {
        return title == null ? 0 : 1;
    }

    @NonNull
    @Override
    public HeaderViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final View view =
                LayoutInflater.from(parent.getContext())
                        .inflate(R.layout.item_search_section_header, parent, false);
        return new HeaderViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull HeaderViewHolder holder, int position) {
        holder.title.setText(title);
        if (count == null) {
            holder.count.setVisibility(View.GONE);
        } else {
            holder.count.setVisibility(View.VISIBLE);
            holder.count.setText(count);
        }
    }

    static class HeaderViewHolder extends RecyclerView.ViewHolder {
        private final TextView title;
        private final TextView count;

        HeaderViewHolder(final View itemView) {
            super(itemView);
            this.title = itemView.findViewById(R.id.section_title);
            this.count = itemView.findViewById(R.id.section_count);
        }
    }
}
