
package eu.siacs.conversations.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Call;
import eu.siacs.conversations.ui.util.AvatarWorkerTask;
import eu.siacs.conversations.ui.widget.AvatarView;
import eu.siacs.conversations.utils.UIHelper;

public class CallsAdapter extends RecyclerView.Adapter<CallsAdapter.CallViewHolder> {

    private final List<Call> calls;
    private final OnCallAgainClickListener listener;

    public interface OnCallAgainClickListener {
        void onCallAgainClick(Call call);
    }

    public CallsAdapter(List<Call> calls, OnCallAgainClickListener listener) {
        this.calls = calls;
        this.listener = listener;
    }

    @NonNull
    @Override
    public CallViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_call, parent, false);
        return new CallViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull CallViewHolder holder, int position) {
        Call call = calls.get(position);
        holder.bind(call, listener);
    }

    @Override
    public int getItemCount() {
        return calls.size();
    }

    static class CallViewHolder extends RecyclerView.ViewHolder {

        private final AvatarView avatar;
        private final TextView contactName;
        private final TextView callInfo;
        private final ImageButton callAgainButton;

        public CallViewHolder(@NonNull View itemView) {
            super(itemView);
            avatar = itemView.findViewById(R.id.avatar);
            contactName = itemView.findViewById(R.id.contact_name);
            callInfo = itemView.findViewById(R.id.call_info);
            callAgainButton = itemView.findViewById(R.id.call_again);
        }

        public void bind(final Call call, final OnCallAgainClickListener listener) {
            Glide.with(itemView.getContext()).load(call).into(avatar);
            //AvatarWorkerTask.loadAvatar(call, avatar, R.dimen.avatar_story_size); //TODO add correct avatar loading
            contactName.setText(call.getContact());
            callInfo.setText(UIHelper.getCallInfo(itemView.getContext(), call));
            callAgainButton.setOnClickListener(v -> listener.onCallAgainClick(call));
        }
    }
}
