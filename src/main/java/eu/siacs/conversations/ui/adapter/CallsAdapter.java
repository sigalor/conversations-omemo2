package eu.siacs.conversations.ui.adapter;

import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Contact;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.widget.AvatarView;
import eu.siacs.conversations.utils.UIHelper;

public class CallsAdapter extends RecyclerView.Adapter<CallsAdapter.CallViewHolder> {

    private final List<Message> calls;
    private final OnCallAgainClickListener callAgainClickListener;
    private final OnContactClickListener contactClickListener;
    private final XmppConnectionService xmppConnectionService;

    public interface OnCallAgainClickListener {
        void onCallAgainClick(Message call, boolean isVideoCall);
    }

    public interface OnContactClickListener {
        void onContactClick(Contact contact);
    }

    public CallsAdapter(List<Message> calls, OnCallAgainClickListener callAgainClickListener, OnContactClickListener contactClickListener, XmppConnectionService xmppConnectionService) {
        this.calls = calls;
        this.callAgainClickListener = callAgainClickListener;
        this.contactClickListener = contactClickListener;
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
        holder.bind(call, callAgainClickListener, contactClickListener, xmppConnectionService);
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

        public void bind(final Message call, final OnCallAgainClickListener callAgainClickListener, final OnContactClickListener contactClickListener, final XmppConnectionService xmppConnectionService) {
            AvatarWorkerTask.loadAvatar(call.getConversation().getContact(), avatar, R.dimen.bubble_avatar_size);
            contactName.setText(call.getConversation().getContact().getDisplayName());

            final Contact contact = call.getConversation().getContact();
            if (contact != null && !contact.isSelf()) {
                View.OnClickListener clickListener = v -> contactClickListener.onContactClick(contact);
                avatar.setOnClickListener(clickListener);
                contactName.setOnClickListener(clickListener);
            } else {
                avatar.setOnClickListener(null);
                contactName.setOnClickListener(null);
            }

            final eu.siacs.conversations.entities.RtpSessionStatus rtpSessionStatus = eu.siacs.conversations.entities.RtpSessionStatus.of(call.getBody());
            final boolean received = call.getStatus() == Message.STATUS_RECEIVED;
            final boolean missed = received && !rtpSessionStatus.successful;

            int color;
            if (missed) {
                color = ContextCompat.getColor(itemView.getContext(), R.color.red_700);
            } else {
                color = contactName.getCurrentTextColor();
            }
            callInfo.setTextColor(color);
            android.graphics.drawable.Drawable drawable = ContextCompat.getDrawable(itemView.getContext(), eu.siacs.conversations.entities.RtpSessionStatus.getDrawable(received, rtpSessionStatus.successful));
            if (drawable != null) {
                drawable.setTint(color);
                final int size = (int) (18 * itemView.getContext().getResources().getDisplayMetrics().density);
                drawable.setBounds(0, 0, size, size);
            }

            callInfo.setText(UIHelper.getMessagePreview(xmppConnectionService, call).first);
            callInfo.setCompoundDrawables(drawable, null, null, null);
            callInfo.setCompoundDrawablePadding((int) (6 * itemView.getContext().getResources().getDisplayMetrics().density));

            callDate.setText(UIHelper.readableTimeDifference(itemView.getContext(), call.getTimeSent(), false));
            callAgainButton.setOnClickListener(v -> {
                PopupMenu popup = new PopupMenu(v.getContext(), v);
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    popup.setForceShowIcon(true);
                }
                popup.getMenuInflater().inflate(R.menu.call_again_context, popup.getMenu());
                popup.setOnMenuItemClickListener(item -> {
                    if (item.getItemId() == R.id.action_voice_call) {
                        callAgainClickListener.onCallAgainClick(call, false);
                        return true;
                    } else if (item.getItemId() == R.id.action_video_call) {
                        callAgainClickListener.onCallAgainClick(call, true);
                        return true;
                    }
                    return false;
                });
                popup.show();
            });
        }
    }
}