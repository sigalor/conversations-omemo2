package eu.siacs.conversations.ui;

import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.VideoView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.bumptech.glide.Glide;

import java.io.File;

import eu.siacs.conversations.R;

public class StoryFragment extends Fragment {

    private static final String ARG_URL = "url";
    private static final String ARG_MIME_TYPE = "mime_type";

    public static StoryFragment newInstance(String url, String mimeType) {
        StoryFragment fragment = new StoryFragment();
        Bundle args = new Bundle();
        args.putString(ARG_URL, url);
        args.putString(ARG_MIME_TYPE, mimeType);
        fragment.setArguments(args);
        return fragment;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_story, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        ImageView imageView = view.findViewById(R.id.story_image_view);
        VideoView videoView = view.findViewById(R.id.story_video_view);

        String url = getArguments().getString(ARG_URL);
        String mimeType = getArguments().getString(ARG_MIME_TYPE);

        if (mimeType != null && mimeType.startsWith("video/")) {
            imageView.setVisibility(View.GONE);
            videoView.setVisibility(View.VISIBLE);
            videoView.setVideoURI(Uri.parse(url));
            videoView.setOnPreparedListener(mp -> {
                mp.setLooping(true);
                videoView.start();
            });
        } else {
            videoView.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            Glide.with(this).load(url).into(imageView);
        }
    }
}
