
package eu.siacs.conversations.ui;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Call;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.adapter.CallsAdapter;
import eu.siacs.conversations.entities.RtpSessionStatus;


public class CallsFragment extends Fragment implements CallsAdapter.OnCallAgainClickListener {

    private XmppConnectionService xmppConnectionService;
    private RecyclerView recyclerView;
    private CallsAdapter adapter;
    private List<Call> calls = new ArrayList<>();

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            XmppConnectionService.XmppConnectionBinder binder = (XmppConnectionService.XmppConnectionBinder) service;
            xmppConnectionService = binder.getService();
            loadCalls();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            xmppConnectionService = null;
        }
    };

    @Override
    public void onStart() {
        super.onStart();
        Intent intent = new Intent(getActivity(), XmppConnectionService.class);
        getActivity().bindService(intent, mConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onStop() {
        super.onStop();
        getActivity().unbindService(mConnection);
    }


    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_calls, container, false);

        recyclerView = view.findViewById(R.id.list);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new CallsAdapter(calls, this);
        recyclerView.setAdapter(adapter);

        return view;
    }

    private void loadCalls() {
        if (xmppConnectionService != null) {
            calls.clear();
            calls.addAll(getCalls());
            adapter.notifyDataSetChanged();
        }
    }

    private List<Call> getCalls() {
        List<Call> calls = new ArrayList<>();
        for (Conversation conversation : xmppConnectionService.getConversations()) {
            for (Message message : xmppConnectionService.databaseBackend.getMessages(conversation, 100)) { // Limiting to last 100 messages per conversation for performance
                if (message.getType() == Message.TYPE_RTP_SESSION) {
                    RtpSessionStatus rtpSessionStatus = RtpSessionStatus.of(message.getBody());
                    boolean isVideo = message.getBody().contains("video");
                    Call call = new Call(conversation.getName().toString(), conversation.getJid(), message.getTimeSent(), message.getStatus(), isVideo, rtpSessionStatus.successful);
                    calls.add(call);
                }
            }
        }
        return calls;
    }

    @Override
    public void onCallAgainClick(Call call) {
        Intent intent = new Intent(getActivity(), RtpSessionActivity.class);
        if (call.isVideoCall()) {
            intent.setAction(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL);
        } else {
            intent.setAction(RtpSessionActivity.ACTION_MAKE_VOICE_CALL);
        }
        intent.putExtra("jid", call.getJid().toString());
        startActivity(intent);
    }
}
