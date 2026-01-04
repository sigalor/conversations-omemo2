
package eu.siacs.conversations.ui;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import eu.siacs.conversations.R;
import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.services.CallIntegrationConnectionService;
import eu.siacs.conversations.services.XmppConnectionService;
import eu.siacs.conversations.ui.adapter.CallsAdapter;

public class CallsFragment extends Fragment implements CallsAdapter.OnCallAgainClickListener {

    private XmppConnectionService xmppConnectionService;
    private RecyclerView recyclerView;
    private CallsAdapter adapter;
    private List<Message> calls = new ArrayList<>();
    private Message mPendingCall;
    private boolean mPendingVideoCall;

    private static final int REQUEST_START_AUDIO_CALL = 0x213;
    private static final int REQUEST_START_VIDEO_CALL = 0x214;

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
        adapter = new CallsAdapter(calls, this, xmppConnectionService);
        recyclerView.setAdapter(adapter);

        return view;
    }

    private void loadCalls() {
        if (xmppConnectionService != null) {
            calls.clear();
            calls.addAll(getCalls());
            adapter = new CallsAdapter(calls, this, xmppConnectionService);
            recyclerView.setAdapter(adapter);
            adapter.notifyDataSetChanged();
        }
    }

    private List<Message> getCalls() {
        List<Message> calls = new ArrayList<>();
        if (xmppConnectionService == null) {
            return calls;
        }
        for (Conversation conversation : xmppConnectionService.getConversations()) {
            for (Message message : xmppConnectionService.databaseBackend.getMessages(conversation, 100)) { // Limiting to last 100 messages per conversation for performance
                if (message.getType() == Message.TYPE_RTP_SESSION) {
                    calls.add(message);
                }
            }
        }
        Collections.sort(calls, (o1, o2) -> Long.compare(o2.getTimeSent(), o1.getTimeSent()));
        return calls;
    }

    @Override
    public void onCallAgainClick(Message call, boolean isVideoCall) {
        mPendingCall = call;
        mPendingVideoCall = isVideoCall;
        if (isVideoCall) {
            checkPermissionAndTriggerVideoCall();
        } else {
            checkPermissionAndTriggerAudioCall();
        }
    }

    private void checkPermissionAndTriggerAudioCall() {
        if (xmppConnectionService.useTorToConnect() || mPendingCall.getConversation().getAccount().isOnion()) {
            Toast.makeText(getActivity(), R.string.disable_tor_to_make_call, Toast.LENGTH_SHORT).show();
            return;
        }
        if (xmppConnectionService.useI2PToConnect() || mPendingCall.getConversation().getAccount().isI2P()) {
            Toast.makeText(getActivity(), R.string.no_i2p_calls, Toast.LENGTH_SHORT).show();
            return;
        }

        final List<String> permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = Arrays.asList(Manifest.permission.RECORD_AUDIO, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions = Collections.singletonList(Manifest.permission.RECORD_AUDIO);
        }
        if (hasPermissions(permissions, REQUEST_START_AUDIO_CALL)) {
            triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VOICE_CALL);
        }
    }

    private void checkPermissionAndTriggerVideoCall() {
        if (xmppConnectionService.useTorToConnect() || mPendingCall.getConversation().getAccount().isOnion()) {
            Toast.makeText(getActivity(), R.string.disable_tor_to_make_call, Toast.LENGTH_SHORT).show();
            return;
        }
        if (xmppConnectionService.useI2PToConnect() || mPendingCall.getConversation().getAccount().isI2P()) {
            Toast.makeText(getActivity(), R.string.no_i2p_calls, Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> permissions;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions = Arrays.asList(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA, Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions = Arrays.asList(Manifest.permission.RECORD_AUDIO, Manifest.permission.CAMERA);
        }
        if (hasPermissions(permissions, REQUEST_START_VIDEO_CALL)) {
            triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL);
        }
    }

    private boolean hasPermissions(List<String> permissions, int requestCode) {
        final List<String> missingPermissions = new ArrayList<>();
        for (String permission : permissions) {
            if (getActivity().checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missingPermissions.add(permission);
            }
        }
        if (missingPermissions.size() == 0) {
            return true;
        } else {
            requestPermissions(missingPermissions.toArray(new String[0]), requestCode);
            return false;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (mPendingCall != null) {
                if(requestCode == REQUEST_START_AUDIO_CALL) {
                    triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VOICE_CALL);
                } else if (requestCode == REQUEST_START_VIDEO_CALL) {
                    triggerRtpSession(RtpSessionActivity.ACTION_MAKE_VIDEO_CALL);
                }
            }
        }
    }

    private void triggerRtpSession(final String action) {
        if (xmppConnectionService.getJingleConnectionManager().isBusy()) {
            Toast.makeText(getActivity(), R.string.only_one_call_at_a_time, Toast.LENGTH_LONG).show();
            return;
        }
        final Conversation conversation = (Conversation) mPendingCall.getConversation();
        final Account account = conversation.getAccount();
        if (account.setOption(Account.OPTION_SOFT_DISABLED, false)) {
            xmppConnectionService.updateAccount(account);
        }
        CallIntegrationConnectionService.placeCall(
                xmppConnectionService,
                account,
                conversation.getJid(),
                RtpSessionActivity.actionToMedia(action));
        mPendingCall = null;
    }
}
