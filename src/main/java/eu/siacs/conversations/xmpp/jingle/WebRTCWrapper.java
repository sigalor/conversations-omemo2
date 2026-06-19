package eu.siacs.conversations.xmpp.jingle;

import android.content.Context;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.util.Log;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.preference.PreferenceManager;

import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.SettableFuture;
import eu.siacs.conversations.Config;
import eu.siacs.conversations.services.XmppConnectionService;
import java.util.Collection;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.CandidatePairChangeEvent;
import org.webrtc.DataChannel;
import org.webrtc.DefaultVideoDecoderFactory;
import org.webrtc.DefaultVideoEncoderFactory;
import org.webrtc.DtmfSender;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.MediaStreamTrack;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.RtpReceiver;
import org.webrtc.RtpTransceiver;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoTrack;
import org.webrtc.audio.JavaAudioDeviceModule;

import java.util.Map;

public class WebRTCWrapper {

    private static final String EXTENDED_LOGGING_TAG = WebRTCWrapper.class.getSimpleName();

    private final ExecutorService executorService = Executors.newSingleThreadExecutor();
    private final ExecutorService localDescriptionExecutorService =
            Executors.newSingleThreadExecutor();

    private static final int TONE_DURATION = 400;
    private static final int DEFAULT_TONE_VOLUME = 60;
    private static final Map<String,Integer> TONE_CODES;
    static {
        ImmutableMap.Builder<String,Integer> builder = new ImmutableMap.Builder<>();
        builder.put("0", ToneGenerator.TONE_DTMF_0);
        builder.put("1", ToneGenerator.TONE_DTMF_1);
        builder.put("2", ToneGenerator.TONE_DTMF_2);
        builder.put("3", ToneGenerator.TONE_DTMF_3);
        builder.put("4", ToneGenerator.TONE_DTMF_4);
        builder.put("5", ToneGenerator.TONE_DTMF_5);
        builder.put("6", ToneGenerator.TONE_DTMF_6);
        builder.put("7", ToneGenerator.TONE_DTMF_7);
        builder.put("8", ToneGenerator.TONE_DTMF_8);
        builder.put("9", ToneGenerator.TONE_DTMF_9);
        builder.put("*", ToneGenerator.TONE_DTMF_S);
        builder.put("#", ToneGenerator.TONE_DTMF_P);
        TONE_CODES = builder.build();
    }

    private static final Set<String> HARDWARE_AEC_BLACKLIST =
            new ImmutableSet.Builder<String>()
                    .add("Pixel")
                    .add("Pixel XL")
                    .add("Moto G5")
                    .add("Moto G (5S) Plus")
                    .add("Moto G4")
                    .add("TA-1053")
                    .add("Mi A1")
                    .add("Mi A2")
                    .add("E5823") // Sony z5 compact
                    .add("Redmi Note 5")
                    .add("FP2") // Fairphone FP2
                    .add("FP4") // Fairphone FP4
                    .add("MI 5")
                    .add("GT-I9515") // Samsung Galaxy S4 Value Edition (jfvelte)
                    .add("GT-I9515L") // Samsung Galaxy S4 Value Edition (jfvelte)
                    .add("GT-I9505") // Samsung Galaxy S4 (jfltexx)
                    .add("Nexus 7") // ASUS Nexus 7
                    .build();

    private final EventCallback eventCallback;
    private final AtomicBoolean readyToReceivedIceCandidates = new AtomicBoolean(false);
    private final Queue<IceCandidate> iceCandidates = new LinkedList<>();
    private TrackWrapper<AudioTrack> localAudioTrack = null;
    private TrackWrapper<VideoTrack> localVideoTrack = null;
    private VideoTrack remoteVideoTrack = null;

    private final SettableFuture<Void> iceGatheringComplete = SettableFuture.create();
    private final PeerConnection.Observer peerConnectionObserver =
            new PeerConnection.Observer() {
                @Override
                public void onSignalingChange(PeerConnection.SignalingState signalingState) {
                    Log.d(EXTENDED_LOGGING_TAG, "onSignalingChange(" + signalingState + ")");
                    // this is called after removeTrack or addTrack
                    // and should then trigger a content-add or content-remove or something
                    // https://developer.mozilla.org/en-US/docs/Web/API/RTCPeerConnection/removeTrack
                }

                @Override
                public void onConnectionChange(final PeerConnection.PeerConnectionState newState) {
                    eventCallback.onConnectionChange(newState);
                }

                @Override
                public void onIceConnectionChange(
                        PeerConnection.IceConnectionState iceConnectionState) {
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onIceConnectionChange(" + iceConnectionState + ")");
                }

                @Override
                public void onSelectedCandidatePairChanged(CandidatePairChangeEvent event) {
                    Log.d(Config.LOGTAG, "remote candidate selected: " + event.remote);
                    Log.d(Config.LOGTAG, "local candidate selected: " + event.local);
                }

                @Override
                public void onIceConnectionReceivingChange(boolean b) {}

                @Override
                public void onIceGatheringChange(
                        final PeerConnection.IceGatheringState iceGatheringState) {
                    Log.d(EXTENDED_LOGGING_TAG, "onIceGatheringChange(" + iceGatheringState + ")");
                    if (iceGatheringState == PeerConnection.IceGatheringState.COMPLETE) {
                        iceGatheringComplete.set(null);
                    }
                }

                @Override
                public void onIceCandidate(IceCandidate iceCandidate) {
                    if (readyToReceivedIceCandidates.get()) {
                        eventCallback.onIceCandidate(iceCandidate);
                    } else {
                        iceCandidates.add(iceCandidate);
                    }
                }

                @Override
                public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {}

                @Override
                public void onAddStream(MediaStream mediaStream) {
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onAddStream(numAudioTracks="
                                    + mediaStream.audioTracks.size()
                                    + ",numVideoTracks="
                                    + mediaStream.videoTracks.size()
                                    + ")");
                }

                @Override
                public void onRemoveStream(MediaStream mediaStream) {}

                @Override
                public void onDataChannel(DataChannel dataChannel) {}

                @Override
                public void onRenegotiationNeeded() {
                    Log.d(EXTENDED_LOGGING_TAG, "onRenegotiationNeeded()");
                    final PeerConnection.PeerConnectionState currentState =
                            peerConnection == null ? null : peerConnection.connectionState();
                    if (currentState != null
                            && currentState != PeerConnection.PeerConnectionState.NEW) {
                        eventCallback.onRenegotiationNeeded();
                    }
                }

                @Override
                public void onAddTrack(RtpReceiver rtpReceiver, MediaStream[] mediaStreams) {
                    final MediaStreamTrack track = rtpReceiver.track();
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onAddTrack(kind="
                                    + (track == null ? "null" : track.kind())
                                    + ",numMediaStreams="
                                    + mediaStreams.length
                                    + ")");
                    if (track instanceof VideoTrack) {
                        remoteVideoTrack = (VideoTrack) track;
                    }
                }

                @Override
                public void onTrack(final RtpTransceiver transceiver) {
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "onTrack(mid="
                                    + transceiver.getMid()
                                    + ",media="
                                    + transceiver.getMediaType()
                                    + ",direction="
                                    + transceiver.getDirection()
                                    + ")");
                }

                @Override
                public void onRemoveTrack(final RtpReceiver receiver) {
                    Log.d(EXTENDED_LOGGING_TAG, "onRemoveTrack(" + receiver.id() + ")");
                }
            };
    @Nullable private PeerConnectionFactory peerConnectionFactory = null;
    @Nullable private PeerConnection peerConnection = null;
    private Context context = null;
    private EglBase eglBase = null;
    private VideoSourceWrapper videoSourceWrapper;

    // XEP-0272 Muji: group-call legs share ONE PeerConnectionFactory (+ AudioDeviceModule + EglBase)
    // so the microphone is captured once and libwebrtc mixes every leg's remote audio to a single
    // output. The shared resources are reference-counted and disposed when the last leg closes.
    private boolean useSharedResources = false;
    private boolean usingSharedResources = false; // whether THIS wrapper acquired the shared refs
    private static final Object SHARED_LOCK = new Object();
    @Nullable private static PeerConnectionFactory sharedFactory = null;
    @Nullable private static EglBase sharedEglBase = null;
    private static int sharedRefCount = 0;
    // Shared camera capture for group video: one capturer/VideoSource feeds every leg's own
    // VideoTrack, so the (single-open) camera is opened once. Refcounted by video legs.
    @Nullable private static VideoSourceWrapper sharedVideoSource = null;
    private static int sharedVideoRefCount = 0;
    private boolean usingSharedVideoSource = false;

    WebRTCWrapper(final EventCallback eventCallback) {
        this.eventCallback = eventCallback;
    }

    /** Mark this wrapper to use the shared (group-call) PeerConnectionFactory + audio device. */
    void setUseSharedResources(final boolean shared) {
        this.useSharedResources = shared;
    }

    /** Acquire (creating on first use) the shared factory + EglBase; refcounted. */
    private static PeerConnectionFactory acquireSharedFactory(
            final Context context, final boolean useHardwareAec) {
        synchronized (SHARED_LOCK) {
            if (sharedFactory == null) {
                sharedEglBase = EglBase.create();
                sharedFactory =
                        PeerConnectionFactory.builder()
                                .setVideoDecoderFactory(
                                        new DefaultVideoDecoderFactory(
                                                sharedEglBase.getEglBaseContext()))
                                .setVideoEncoderFactory(
                                        new DefaultVideoEncoderFactory(
                                                sharedEglBase.getEglBaseContext(), true, true))
                                .setAudioDeviceModule(
                                        JavaAudioDeviceModule.builder(context)
                                                .setUseHardwareAcousticEchoCanceler(useHardwareAec)
                                                .createAudioDeviceModule())
                                .createPeerConnectionFactory();
            }
            sharedRefCount++;
            Log.d(Config.LOGTAG, "muji: using shared PeerConnectionFactory, leg count=" + sharedRefCount);
            return sharedFactory;
        }
    }

    /** Acquire (creating on first use) the shared camera capturer; refcounted by video legs. */
    private static VideoSourceWrapper acquireSharedVideoSource(
            final Context context, final PeerConnectionFactory factory, final EglBase eglBase) {
        synchronized (SHARED_LOCK) {
            if (sharedVideoSource == null) {
                final VideoSourceWrapper wrapper = new VideoSourceWrapper.Factory(context).create();
                if (wrapper == null) {
                    throw new IllegalStateException("Could not instantiate shared VideoSourceWrapper");
                }
                wrapper.initialize(factory, context, eglBase.getEglBaseContext());
                wrapper.startCapture();
                sharedVideoSource = wrapper;
            }
            sharedVideoRefCount++;
            Log.d(Config.LOGTAG, "muji: using shared camera capture, video leg count=" + sharedVideoRefCount);
            return sharedVideoSource;
        }
    }

    /** Release the shared camera capturer; stop + dispose it when the last video leg is gone. */
    private static void releaseSharedVideoSource() {
        synchronized (SHARED_LOCK) {
            sharedVideoRefCount--;
            if (sharedVideoRefCount <= 0) {
                sharedVideoRefCount = 0;
                if (sharedVideoSource != null) {
                    try {
                        sharedVideoSource.stopCapture();
                    } catch (final InterruptedException e) {
                        Log.w(Config.LOGTAG, "muji: interrupted stopping shared camera");
                    }
                    sharedVideoSource.dispose();
                    sharedVideoSource = null;
                }
            }
        }
    }

    /** Release the shared factory; dispose it (and EglBase) when the last leg is gone. */
    private static void releaseSharedFactory() {
        synchronized (SHARED_LOCK) {
            sharedRefCount--;
            if (sharedRefCount <= 0) {
                sharedRefCount = 0;
                if (sharedFactory != null) {
                    sharedFactory.dispose();
                    sharedFactory = null;
                }
                if (sharedEglBase != null) {
                    sharedEglBase.release();
                    sharedEglBase = null;
                }
            }
        }
    }

    private static void dispose(final PeerConnection peerConnection) {
        try {
            peerConnection.dispose();
        } catch (final IllegalStateException e) {
            Log.e(Config.LOGTAG, "unable to dispose of peer connection", e);
        }
    }

    public void setup(final XmppConnectionService service) throws InitializationException {
        try {
            PeerConnectionFactory.initialize(
                    PeerConnectionFactory.InitializationOptions.builder(service)
                            .setFieldTrials("WebRTC-BindUsingInterfaceName/Enabled/")
                            .createInitializationOptions());
        } catch (final UnsatisfiedLinkError e) {
            throw new InitializationException("Unable to initialize PeerConnectionFactory", e);
        }
        this.context = service;
        // For shared (Muji) resources the EglBase is acquired together with the shared factory in
        // initializePeerConnection(); only create a per-wrapper one for ordinary 1:1 calls.
        if (this.useSharedResources) {
            return;
        }
        try {
            this.eglBase = EglBase.create();
        } catch (final RuntimeException e) {
            throw new InitializationException("Unable to create EGL base", e);
        }
    }

    synchronized void initializePeerConnection(
            final Set<Media> media,
            final Collection<PeerConnection.IceServer> iceServers,
            final boolean trickle,
            final boolean useRelay)
            throws InitializationException {
        Preconditions.checkNotNull(media);
        Preconditions.checkArgument(
                !media.isEmpty(), "media can not be empty when initializing peer connection");
        final boolean setUseHardwareAcousticEchoCanceler =
                !HARDWARE_AEC_BLACKLIST.contains(Build.MODEL);
        Log.d(
                Config.LOGTAG,
                String.format(
                        "setUseHardwareAcousticEchoCanceler(%s) model=%s",
                        setUseHardwareAcousticEchoCanceler, Build.MODEL));
        if (this.useSharedResources) {
            // Group call: share one factory/ADM/EglBase across all per-pair legs.
            this.peerConnectionFactory =
                    acquireSharedFactory(requireContext(), setUseHardwareAcousticEchoCanceler);
            this.usingSharedResources = true;
            synchronized (SHARED_LOCK) {
                this.eglBase = sharedEglBase;
            }
        } else {
            Preconditions.checkState(this.eglBase != null);
            this.peerConnectionFactory =
                    PeerConnectionFactory.builder()
                            .setVideoDecoderFactory(
                                    new DefaultVideoDecoderFactory(eglBase.getEglBaseContext()))
                            .setVideoEncoderFactory(
                                    new DefaultVideoEncoderFactory(
                                            eglBase.getEglBaseContext(), true, true))
                            .setAudioDeviceModule(
                                    JavaAudioDeviceModule.builder(requireContext())
                                            .setUseHardwareAcousticEchoCanceler(
                                                    setUseHardwareAcousticEchoCanceler)
                                            .createAudioDeviceModule())
                            .createPeerConnectionFactory();
        }

        final PeerConnection.RTCConfiguration rtcConfig =
                buildConfiguration(iceServers, trickle, useRelay);
        final PeerConnection peerConnection =
                requirePeerConnectionFactory()
                        .createPeerConnection(rtcConfig, peerConnectionObserver);
        if (peerConnection == null) {
            throw new InitializationException("Unable to create PeerConnection");
        }

        if (media.contains(Media.VIDEO)) {
            addVideoTrack(peerConnection);
        }

        if (media.contains(Media.AUDIO)) {
            addAudioTrack(peerConnection);
        }
        peerConnection.setAudioPlayout(true);
        peerConnection.setAudioRecording(true);

        this.peerConnection = peerConnection;
    }

    private VideoSourceWrapper initializeVideoSourceWrapper() {
        final VideoSourceWrapper existingVideoSourceWrapper = this.videoSourceWrapper;
        if (existingVideoSourceWrapper != null) {
            existingVideoSourceWrapper.startCapture();
            return existingVideoSourceWrapper;
        }
        // Group video legs share one camera capturer (opened once) via the shared factory/egl.
        if (this.useSharedResources) {
            final VideoSourceWrapper shared =
                    acquireSharedVideoSource(requireContext(), requirePeerConnectionFactory(), eglBase);
            this.usingSharedVideoSource = true;
            this.videoSourceWrapper = shared;
            return shared;
        }
        final VideoSourceWrapper videoSourceWrapper =
                new VideoSourceWrapper.Factory(requireContext()).create();
        if (videoSourceWrapper == null) {
            throw new IllegalStateException("Could not instantiate VideoSourceWrapper");
        }
        videoSourceWrapper.initialize(
                requirePeerConnectionFactory(), requireContext(), eglBase.getEglBaseContext());
        videoSourceWrapper.startCapture();
        this.videoSourceWrapper = videoSourceWrapper;
        return videoSourceWrapper;
    }

    public synchronized boolean addTrack(final Media media) {
        if (media == Media.VIDEO) {
            return addVideoTrack(requirePeerConnection());
        } else if (media == Media.AUDIO) {
            return addAudioTrack(requirePeerConnection());
        }
        throw new IllegalStateException(String.format("Could not add track for %s", media));
    }

    public synchronized void removeTrack(final Media media) {
        if (media == Media.VIDEO) {
            removeVideoTrack(requirePeerConnection());
        }
    }

    private boolean addAudioTrack(final PeerConnection peerConnection) {
        final AudioSource audioSource =
                requirePeerConnectionFactory().createAudioSource(new MediaConstraints());
        final AudioTrack audioTrack =
                requirePeerConnectionFactory()
                        .createAudioTrack(TrackWrapper.id(AudioTrack.class), audioSource);
        this.localAudioTrack = TrackWrapper.addTrack(peerConnection, audioTrack);
        return true;
    }

    private boolean addVideoTrack(final PeerConnection peerConnection) {
        final TrackWrapper<VideoTrack> existing = this.localVideoTrack;
        if (existing != null) {
            final RtpTransceiver transceiver =
                    TrackWrapper.getTransceiver(peerConnection, existing);
            if (transceiver == null) {
                Log.w(EXTENDED_LOGGING_TAG, "unable to restart video transceiver");
                return false;
            }
            transceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.SEND_RECV);
            this.videoSourceWrapper.startCapture();
            return true;
        }
        final VideoSourceWrapper videoSourceWrapper;
        try {
            videoSourceWrapper = initializeVideoSourceWrapper();
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "could not add video track", e);
            return false;
        }
        final VideoTrack videoTrack =
                requirePeerConnectionFactory()
                        .createVideoTrack(
                                TrackWrapper.id(VideoTrack.class),
                                videoSourceWrapper.getVideoSource());
        this.localVideoTrack = TrackWrapper.addTrack(peerConnection, videoTrack);
        return true;
    }

    private void removeVideoTrack(final PeerConnection peerConnection) {
        final TrackWrapper<VideoTrack> localVideoTrack = this.localVideoTrack;
        if (localVideoTrack != null) {

            final RtpTransceiver exactTransceiver =
                    TrackWrapper.getTransceiver(peerConnection, localVideoTrack);
            if (exactTransceiver == null) {
                throw new IllegalStateException();
            }
            exactTransceiver.setDirection(RtpTransceiver.RtpTransceiverDirection.INACTIVE);
        }
        // For a shared (Muji) camera, don't stop the capturer — other legs are still using it;
        // setting this leg's transceiver inactive is enough.
        if (this.usingSharedVideoSource) {
            return;
        }
        final VideoSourceWrapper videoSourceWrapper = this.videoSourceWrapper;
        if (videoSourceWrapper != null) {
            try {
                videoSourceWrapper.stopCapture();
            } catch (final InterruptedException e) {
                Log.e(Config.LOGTAG, "could not stop capturing video source", e);
            }
        }
    }

    public static PeerConnection.RTCConfiguration buildConfiguration(
            final Collection<PeerConnection.IceServer> iceServers,
            final boolean trickle,
            final boolean useRelay) {
        final PeerConnection.RTCConfiguration rtcConfig =
                new PeerConnection.RTCConfiguration(ImmutableList.copyOf(iceServers));
        rtcConfig.tcpCandidatePolicy =
                PeerConnection.TcpCandidatePolicy.DISABLED; // XEP-0176 doesn't support tcp
        if (trickle) {
            rtcConfig.continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY;
        } else {
            rtcConfig.continualGatheringPolicy =
                    PeerConnection.ContinualGatheringPolicy.GATHER_ONCE;
        }
        rtcConfig.sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN;
        rtcConfig.rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.NEGOTIATE;
        rtcConfig.enableImplicitRollback = true;
        rtcConfig.iceTransportsType =
                useRelay
                        ? PeerConnection.IceTransportsType.RELAY
                        : PeerConnection.IceTransportsType.ALL;
        return rtcConfig;
    }

    void reconfigurePeerConnection(
            final Set<PeerConnection.IceServer> iceServers,
            final boolean trickle,
            final boolean useRelay) {
        requirePeerConnection().setConfiguration(buildConfiguration(iceServers, trickle, useRelay));
    }

    void restartIceAsync() {
        this.execute(this::restartIce);
    }

    private void restartIce() {
        final PeerConnection peerConnection;
        try {
            peerConnection = requirePeerConnection();
        } catch (final PeerConnectionNotInitialized e) {
            Log.w(EXTENDED_LOGGING_TAG, "PeerConnection vanished before we could execute restart");
            return;
        }
        setIsReadyToReceiveIceCandidates(false);
        peerConnection.restartIce();
    }

    public void setIsReadyToReceiveIceCandidates(final boolean ready) {
        readyToReceivedIceCandidates.set(ready);
        final int was = iceCandidates.size();
        while (ready && iceCandidates.peek() != null) {
            eventCallback.onIceCandidate(iceCandidates.poll());
        }
        final int is = iceCandidates.size();
        Log.d(
                EXTENDED_LOGGING_TAG,
                "setIsReadyToReceiveCandidates(" + ready + ") was=" + was + " is=" + is);
    }

    synchronized void close() {
        final PeerConnection peerConnection = this.peerConnection;
        final PeerConnectionFactory peerConnectionFactory = this.peerConnectionFactory;
        final VideoSourceWrapper videoSourceWrapper = this.videoSourceWrapper;
        final EglBase eglBase = this.eglBase;
        if (peerConnection != null) {
            this.peerConnection = null;
            dispose(peerConnection);
        }
        this.localVideoTrack = null;
        this.remoteVideoTrack = null;
        if (this.usingSharedVideoSource) {
            // Shared (Muji) camera: don't stop/dispose it here (other legs use it) — release the
            // refcount; the last video leg actually stops + disposes the capturer.
            this.videoSourceWrapper = null;
            this.usingSharedVideoSource = false;
            releaseSharedVideoSource();
        } else if (videoSourceWrapper != null) {
            this.videoSourceWrapper = null;
            try {
                videoSourceWrapper.stopCapture();
            } catch (final InterruptedException e) {
                Log.e(Config.LOGTAG, "unable to stop capturing");
            }
            videoSourceWrapper.dispose();
        }
        if (this.usingSharedResources) {
            // Shared (Muji) factory/EglBase: just drop our references + release the refcount; the
            // last leg to close actually disposes them.
            this.eglBase = null;
            this.peerConnectionFactory = null;
            this.usingSharedResources = false;
            releaseSharedFactory();
        } else {
            if (eglBase != null) {
                eglBase.release();
                this.eglBase = null;
            }
            if (peerConnectionFactory != null) {
                this.peerConnectionFactory = null;
                peerConnectionFactory.dispose();
            }
        }
    }

    synchronized void verifyClosed() {
        if (this.peerConnection != null
                || this.eglBase != null
                || this.localVideoTrack != null
                || this.remoteVideoTrack != null) {
            final AssertionError e =
                    new AssertionError("WebRTCWrapper hasn't been closed properly");
            Log.e(Config.LOGTAG, "verifyClosed() failed. Going to throw", e);
            throw e;
        }
    }

    boolean isCameraSwitchable() {
        final VideoSourceWrapper videoSourceWrapper = this.videoSourceWrapper;
        return videoSourceWrapper != null && videoSourceWrapper.isCameraSwitchable();
    }

    boolean isFrontCamera() {
        final VideoSourceWrapper videoSourceWrapper = this.videoSourceWrapper;
        return videoSourceWrapper == null || videoSourceWrapper.isFrontCamera();
    }

    ListenableFuture<Boolean> switchCamera() {
        final VideoSourceWrapper videoSourceWrapper = this.videoSourceWrapper;
        if (videoSourceWrapper == null) {
            return Futures.immediateFailedFuture(
                    new IllegalStateException("VideoSourceWrapper has not been initialized"));
        }
        return videoSourceWrapper.switchCamera();
    }

    boolean isMicrophoneEnabled() {
        Optional<AudioTrack> audioTrack = null;
        try {
            audioTrack = TrackWrapper.get(peerConnection, this.localAudioTrack);
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "unable to check microphone", e);
            // ignoring race condition in case sender has been disposed
            return false;
        }
        if (audioTrack.isPresent()) {
            try {
                return audioTrack.get().enabled();
            } catch (final IllegalStateException e) {
                // sometimes UI might still be rendering the buttons when a background thread has
                // already ended the call
                return false;
            }
        } else {
            return false;
        }
    }

    boolean setMicrophoneEnabledOrThrow(final boolean enabled) {
        final Optional<AudioTrack> audioTrack =
                TrackWrapper.get(peerConnection, this.localAudioTrack);
        if (audioTrack.isPresent()) {
            return setEnabled(audioTrack.get(), enabled);

        } else {
            throw new IllegalStateException("Local audio track does not exist (yet)");
        }
    }

    private static boolean setEnabled(final AudioTrack audioTrack, final boolean enabled) {
        try {
            audioTrack.setEnabled(enabled);
            return true;
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "unable to toggle audio track", e);
            // ignoring race condition in case MediaStreamTrack has been disposed
            return false;
        }
    }

    void setMicrophoneEnabled(final boolean enabled) {
        final Optional<AudioTrack> audioTrack =
                TrackWrapper.get(peerConnection, this.localAudioTrack);
        if (audioTrack.isPresent()) {
            setEnabled(audioTrack.get(), enabled);
        }
    }

    boolean isVideoEnabled() {
        final Optional<VideoTrack> videoTrack;
        try {
            videoTrack = TrackWrapper.get(peerConnection, this.localVideoTrack);
        } catch (final IllegalStateException e) {
            Log.d(Config.LOGTAG, "unable to check video", e);
            // ignoring race condition in case the transceiver/sender has been disposed
            return false;
        }
        if (videoTrack.isPresent()) {
            try {
                return videoTrack.get().enabled();
            } catch (final IllegalStateException e) {
                // UI may still render the buttons when a background thread already ended the call
                return false;
            }
        }
        return false;
    }

    void setVideoEnabledOrThrow(final boolean enabled) {
        final Optional<VideoTrack> videoTrack =
                TrackWrapper.get(peerConnection, this.localVideoTrack);
        if (videoTrack.isPresent()) {
            videoTrack.get().setEnabled(enabled);
            return;
        }
        throw new IllegalStateException("Local video track does not exist");
    }

    void setVideoEnabled(final boolean enabled) {
        final Optional<VideoTrack> videoTrack =
                TrackWrapper.get(peerConnection, this.localVideoTrack);
        if (videoTrack.isPresent()) {
            videoTrack.get().setEnabled(enabled);
        }
    }

    synchronized ListenableFuture<SessionDescription> setLocalDescription(
            final boolean waitForCandidates) {
        this.setIsReadyToReceiveIceCandidates(false);
        return Futures.transformAsync(
                getPeerConnectionFuture(),
                peerConnection -> {
                    if (peerConnection == null) {
                        return Futures.immediateFailedFuture(
                                new IllegalStateException("PeerConnection was null"));
                    }
                    final SettableFuture<SessionDescription> future = SettableFuture.create();
                    peerConnection.setLocalDescription(
                            new SetSdpObserver() {
                                @Override
                                public void onSetSuccess() {
                                    if (waitForCandidates) {
                                        final var delay = getIceGatheringCompleteOrTimeout();
                                        final var delayedSessionDescription =
                                                Futures.transformAsync(
                                                        delay,
                                                        v -> {
                                                            iceCandidates.clear();
                                                            return getLocalDescriptionFuture();
                                                        },
                                                        MoreExecutors.directExecutor());
                                        future.setFuture(delayedSessionDescription);
                                    } else {
                                        future.setFuture(getLocalDescriptionFuture());
                                    }
                                }

                                @Override
                                public void onSetFailure(final String message) {
                                    future.setException(
                                            new FailureToSetDescriptionException(message));
                                }
                            });
                    return future;
                },
                MoreExecutors.directExecutor());
    }

    private ListenableFuture<Void> getIceGatheringCompleteOrTimeout() {
        return Futures.catching(
                Futures.withTimeout(
                        iceGatheringComplete,
                        2,
                        TimeUnit.SECONDS,
                        JingleConnectionManager.SCHEDULED_EXECUTOR_SERVICE),
                TimeoutException.class,
                ex -> {
                    Log.d(
                            EXTENDED_LOGGING_TAG,
                            "timeout while waiting for ICE gathering to complete");
                    return null;
                },
                MoreExecutors.directExecutor());
    }

    private ListenableFuture<SessionDescription> getLocalDescriptionFuture() {
        return Futures.submit(
                () -> {
                    final SessionDescription description =
                            limitOpusBitrateInSdp(this.context, requirePeerConnection().getLocalDescription());
                    Log.d(EXTENDED_LOGGING_TAG, "local description:");
                    logDescription(description);
                    return description;
                },
                localDescriptionExecutorService);
    }

    public static void logDescription(final SessionDescription sessionDescription) {
        for (final String line :
                sessionDescription.description.split(
                        eu.siacs.conversations.xmpp.jingle.SessionDescription.LINE_DIVIDER)) {
            Log.d(EXTENDED_LOGGING_TAG, line);
        }
    }

    synchronized ListenableFuture<Void> setRemoteDescription(
            final SessionDescription sessionDescription) {
        Log.d(EXTENDED_LOGGING_TAG, "setting remote description:");
        logDescription(sessionDescription);
        return Futures.transformAsync(
                getPeerConnectionFuture(),
                peerConnection -> {
                    if (peerConnection == null) {
                        return Futures.immediateFailedFuture(
                                new IllegalStateException("PeerConnection was null"));
                    }
                    final SettableFuture<Void> future = SettableFuture.create();
                    peerConnection.setRemoteDescription(
                            new SetSdpObserver() {
                                @Override
                                public void onSetSuccess() {
                                    future.set(null);
                                }

                                @Override
                                public void onSetFailure(final String message) {
                                    future.setException(
                                            new FailureToSetDescriptionException(message));
                                }
                            },
                            sessionDescription);
                    return future;
                },
                MoreExecutors.directExecutor());
    }

    @NonNull
    private ListenableFuture<PeerConnection> getPeerConnectionFuture() {
        final PeerConnection peerConnection = this.peerConnection;
        if (peerConnection == null) {
            return Futures.immediateFailedFuture(new PeerConnectionNotInitialized());
        } else {
            return Futures.immediateFuture(peerConnection);
        }
    }

    @NonNull
    private PeerConnection requirePeerConnection() {
        final PeerConnection peerConnection = this.peerConnection;
        if (peerConnection == null) {
            throw new PeerConnectionNotInitialized();
        }
        return peerConnection;
    }

    public boolean applyDtmfTone(String tone) {
        if (localAudioTrack == null || localAudioTrack.rtpSender == null) return false;

        try {
            localAudioTrack.rtpSender.dtmf().insertDtmf(tone, TONE_DURATION, 100);
        } catch (final IllegalStateException e) {
            // Race condition, DtmfSender has been disposed
            return false;
        }
        final var handler = new android.os.Handler(android.os.Looper.getMainLooper());
        handler.post(() -> {
            final var toneGenerator = new ToneGenerator(AudioManager.STREAM_VOICE_CALL, DEFAULT_TONE_VOLUME);
            toneGenerator.startTone(TONE_CODES.get(tone), TONE_DURATION);
            handler.postDelayed(() -> toneGenerator.release(), TONE_DURATION+2);
        });

        return true;
    }

    @NonNull
    private PeerConnectionFactory requirePeerConnectionFactory() {
        final PeerConnectionFactory peerConnectionFactory = this.peerConnectionFactory;
        if (peerConnectionFactory == null) {
            throw new IllegalStateException("Make sure PeerConnectionFactory is initialized");
        }
        return peerConnectionFactory;
    }

    void addIceCandidate(IceCandidate iceCandidate) {
        requirePeerConnection().addIceCandidate(iceCandidate);
    }

    PeerConnection.PeerConnectionState getState() {
        return requirePeerConnection().connectionState();
    }

    public PeerConnection.SignalingState getSignalingState() {
        try {
            return requirePeerConnection().signalingState();
        } catch (final IllegalStateException e) {
            return PeerConnection.SignalingState.CLOSED;
        }
    }

    EglBase.Context getEglBaseContext() {
        return this.eglBase.getEglBaseContext();
    }

    Optional<VideoTrack> getLocalVideoTrack() {
        try {
            return TrackWrapper.get(peerConnection, this.localVideoTrack);
        } catch (IllegalStateException e) {
            return Optional.absent();
        }
    }

    Optional<VideoTrack> getRemoteVideoTrack() {
        return Optional.fromNullable(this.remoteVideoTrack);
    }

    private Context requireContext() {
        final Context context = this.context;
        if (context == null) {
            throw new IllegalStateException("call setup first");
        }
        return context;
    }

    void execute(final Runnable command) {
        this.executorService.execute(command);
    }

    public interface EventCallback {
        void onIceCandidate(IceCandidate iceCandidate);

        void onConnectionChange(PeerConnection.PeerConnectionState newState);

        void onRenegotiationNeeded();
    }

    public abstract static class SetSdpObserver implements SdpObserver {

        @Override
        public void onCreateSuccess(org.webrtc.SessionDescription sessionDescription) {
            throw new IllegalStateException("Not able to use SetSdpObserver");
        }

        @Override
        public void onCreateFailure(String s) {
            throw new IllegalStateException("Not able to use SetSdpObserver");
        }
    }

    public static class InitializationException extends Exception {

        private InitializationException(final String message, final Throwable throwable) {
            super(message, throwable);
        }

        private InitializationException(final String message) {
            super(message);
        }
    }

    public static class PeerConnectionNotInitialized extends IllegalStateException {

        public PeerConnectionNotInitialized() {
            super("initialize PeerConnection first");
        }
    }

    public static class FailureToSetDescriptionException extends IllegalArgumentException {
        public FailureToSetDescriptionException(String message) {
            super(message);
        }
    }

    private static SessionDescription limitOpusBitrateInSdp(Context context, SessionDescription sessionDescription) {
        if (context != null && sessionDescription != null
                && PreferenceManager.getDefaultSharedPreferences(context)
                .getBoolean("low_quality_opus", false)
                && sessionDescription.description != null
                && !sessionDescription.description.contains("maxaveragebitrate=16000")) {

            String sdp = sessionDescription.description;
            String modifiedSdp;

            if (sdp.contains("useinbandfec=1")) {
                modifiedSdp = sdp.replace("useinbandfec=1",
                        "useinbandfec=1;maxaveragebitrate=16000;maxplaybackrate=8000;usedtx=1;minptime=60;x-google-min-bitrate=6;x-google-max-bitrate=16;x-google-start-bitrate=12");
            } else if (sdp.contains("opus/48000/2\r\n")) {
                modifiedSdp = sdp.replace("opus/48000/2\r\n",
                        "opus/48000/2\r\na=fmtp:111 minptime=60;useinbandfec=1;maxaveragebitrate=16000;maxplaybackrate=8000;usedtx=1;x-google-min-bitrate=6;x-google-max-bitrate=16;x-google-start-bitrate=12\r\n");
            } else {
                return sessionDescription;
            }

            return new SessionDescription(sessionDescription.type, modifiedSdp);
        }
        return sessionDescription;
    }
}
