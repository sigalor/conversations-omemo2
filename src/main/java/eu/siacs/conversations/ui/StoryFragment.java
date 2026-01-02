package eu.siacs.conversations.ui;

import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.http.HttpConnectionManager;
import okhttp3.HttpUrl;

public class StoryFragment extends Fragment {

    private static final String ARG_URL = "url";
    private static final String ARG_MIME_TYPE = "mime_type";

    private OnStoryTapListener mListener;

    public interface OnStoryTapListener {
        void onStoryTapped();
    }

    public static StoryFragment newInstance(String url, String mimeType) {
        StoryFragment fragment = new StoryFragment();
        Bundle args = new Bundle();
        args.putString(ARG_URL, url);
        args.putString(ARG_MIME_TYPE, mimeType);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnStoryTapListener) {
            mListener = (OnStoryTapListener) context;
        } else {
            throw new RuntimeException(context.toString() + " must implement OnStoryTapListener");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_story, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        final ImageView imageView = view.findViewById(R.id.story_image_view);
        final VideoView videoView = view.findViewById(R.id.story_video_view);
        final ProgressBar progressBar = view.findViewById(R.id.story_progress);

        view.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onStoryTapped();
            }
        });

        final String url = getArguments().getString(ARG_URL);
        final String mimeType = getArguments().getString(ARG_MIME_TYPE);

        final StoryViewActivity activity = (StoryViewActivity) getActivity();
        if (activity == null || activity.xmppConnectionService == null) {
            return;
        }

        final File cacheFile = activity.getStoryCacheFile(url);

        new Thread(() -> {
            try {
                if (cacheFile != null && (!cacheFile.exists() || cacheFile.length() == 0)) {
                    activity.runOnUiThread(() -> progressBar.setVisibility(View.VISIBLE));
                    Log.d(Config.LOGTAG, "Story not in cache. Downloading from: " + url);
                    final HttpUrl httpUrl = HttpUrl.get(url);
                    final boolean useTor = activity.xmppConnectionService.useTorToConnect();
                    final boolean useI2p = activity.xmppConnectionService.useI2PToConnect();
                    try (InputStream inputStream = HttpConnectionManager.open(httpUrl, useTor, useI2p);
                         FileOutputStream outputStream = new FileOutputStream(cacheFile)) {
                        byte[] buffer = new byte[4096];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            outputStream.write(buffer, 0, bytesRead);
                        }
                    }
                } else if (cacheFile != null) {
                    Log.d(Config.LOGTAG, "Loading story from cache: " + cacheFile.getName());
                }

                activity.runOnUiThread(() -> {
                    progressBar.setVisibility(View.GONE);
                    if (isAdded() && getActivity() != null && cacheFile != null) {
                        videoView.stopPlayback();
                        if (mimeType != null && mimeType.startsWith("video/")) {
                            imageView.setVisibility(View.GONE);
                            videoView.setVisibility(View.VISIBLE);
                            videoView.setVideoURI(Uri.fromFile(cacheFile));
                            videoView.setOnPreparedListener(mp -> {
                                mp.setLooping(true);
                                videoView.start();
                            });
                        } else {
                            videoView.setVisibility(View.GONE);
                            imageView.setVisibility(View.VISIBLE);
                            Glide.with(this).load(cacheFile).into(imageView);
                        }
                    }
                });

            } catch (Exception e) {
                Log.e(Config.LOGTAG, "Failed to download or load story", e);
                if (cacheFile != null && cacheFile.exists()) {
                    cacheFile.delete();
                }
                activity.runOnUiThread(() -> {
                    if(isAdded()) {
                        Toast.makeText(getContext(), R.string.download_failed_file_not_found, Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }).start();
    }
    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }
}