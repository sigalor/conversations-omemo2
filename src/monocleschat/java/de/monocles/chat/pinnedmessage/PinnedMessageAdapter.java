package de.monocles.chat.pinnedmessage;

import android.content.Context;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;

import eu.siacs.conversations.R;

public class PinnedMessageAdapter extends ArrayAdapter<PinnedMessageRepository.DecryptedPinnedMessageData> {

    // Interface for the unpin action
    public interface OnUnpinClickListener {
        void onUnpinClick(PinnedMessageRepository.DecryptedPinnedMessageData messageData);
    }

    // NEW: Interface for the jump-to-message action
    public interface OnItemClickListener {
        void onItemClick(PinnedMessageRepository.DecryptedPinnedMessageData messageData);
    }

    private final OnUnpinClickListener unpinClickListener;
    private final OnItemClickListener itemClickListener; // Add new listener

    // Update the constructor
    public PinnedMessageAdapter(@NonNull Context context,
                                @NonNull List<PinnedMessageRepository.DecryptedPinnedMessageData> objects,
                                OnItemClickListener itemClickListener,
                                OnUnpinClickListener unpinClickListener) {
        super(context, 0, objects);
        this.itemClickListener = itemClickListener;
        this.unpinClickListener = unpinClickListener;
    }


    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext()).inflate(R.layout.item_pinned_message, parent, false);
        }

        PinnedMessageRepository.DecryptedPinnedMessageData messageData = getItem(position);

        TextView messageText = convertView.findViewById(R.id.message_text);
        ImageView mediaIcon = convertView.findViewById(R.id.media_icon);
        ImageView unpinButton = convertView.findViewById(R.id.unpin_button); // Get the unpin button

        if (messageData != null) {
            if (messageData.cid != null) {
                mediaIcon.setVisibility(View.VISIBLE);
                messageText.setText(!TextUtils.isEmpty(messageData.plaintextBody) ? messageData.plaintextBody : getContext().getString(R.string.pinned_media));
            } else {
                mediaIcon.setVisibility(View.GONE);
                messageText.setText(messageData.plaintextBody);
            }

            // Set the listener for the unpin button
            unpinButton.setOnClickListener(v -> {
                if (unpinClickListener != null) {
                    unpinClickListener.onUnpinClick(messageData);
                }
            });

            // Set the listener for the entire item view (for jumping)
            convertView.setOnClickListener(v -> {
                if (itemClickListener != null) {
                    itemClickListener.onItemClick(messageData);
                }
            });
        }

        return convertView;
    }
}
