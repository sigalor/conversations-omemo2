
package eu.siacs.conversations.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.widget.AvatarView;
import eu.siacs.conversations.utils.UIHelper;

public class CallsAdapter extends RecyclerView.Adapter<CallsAdapter.CallViewHolder> {

    private final List<Message> calls;
    private final OnCallAgainClickListener listener;
    private final XmppConnectionService xmppConnectionService;

    public interface OnCallAgainClickListener {
        void onCallAgainClick(Message call, boolean isVideoCall);
    }

    public CallsAdapter(List<Message> calls, OnCallAgainClickListener listener, XmppConnectionService xmppConnectionService) {
        this.calls = calls;
        this.listener = listener;
        this.xmppConnectionService = xmppConnectionService;
    }

    @NonNull
    @Override
    public CallViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_call, parent, false);
        return new CallViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CallViewHolder holder, int position) {
        Message call = calls.get(position);
        holder.bind(call, listener, xmppConnectionService);
    }

    @Override
    public int getItemCount() {
        return calls.size();
    }

    static class CallViewHolder extends RecyclerView.ViewHolder {

        private final AvatarView avatar;
        private final TextView contactName;
        private final TextView callInfo;
        private final TextView callDate;
        private final ImageButton callAgainButton;

        public CallViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.avatar);
            contactName = itemView.findViewById(R.id.contact_name);
            callInfo = itemView.findViewById(R.id.call_info);
            callDate = itemView.findViewById(R.id.call_date);
            callAgainButton = itemView.findViewById(R.id.call_again);
        }

        public void bind(final Message call, final OnCallAgainClickListener listener, final XmppConnectionService xmppConnectionService) {
            AvatarWorkerTask.loadAvatar(call.getConversation().getContact(), avatar, R.dimen.bubble_avatar_size);
            contactName.setText(call.getConversation().getContact().getDisplayName());
            callInfo.setText(UIHelper.getMessagePreview(xmppConnectionService, call).first);
            callDate.setText(UIHelper.readableTimeDifference(itemView.getContext(), call.getTimeSent(), false));
            callAgainButton.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(v.getContext(), v);
                popup.getMenuInflater().inflate(R.menu.call_again_context, popup.getMenu());
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == R.id.action_voice_call) {
                        listener.onCallAgainClick(call, false);
                        return true;
                    } else if (item.getItemId() == R.id.action_video_call) {
                        listener.onCallAgainClick(call, true);
                        return true;
                    }
                    return false;
                });
                popup.show();
            });
        }
    }
}
