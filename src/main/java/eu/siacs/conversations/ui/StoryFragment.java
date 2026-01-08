package eu.siacs.conversations.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
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
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;

import java.io.File;

import eu.siacs.conversations.R;

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

        view.setOnClickListener(v -> {
            if (mListener != null) {
                mListener.onStoryTapped();
            }
        });

        loadStory();
    }

    public void loadStory() {
        if (!isAdded()) {
            return;
        }

        final View view = getView();
        if (view == null) {
            return;
        }

        final ImageView imageView = view.findViewById(R.id.story_image_view);
        final VideoView videoView = view.findViewById(R.id.story_video_view);
        final ProgressBar progressBar = view.findViewById(R.id.story_progress);

        final Bundle args = getArguments();
        if (args == null) {
            return;
        }

        final String url = args.getString(ARG_URL);
        final String mimeType = args.getString(ARG_MIME_TYPE);

        progressBar.setVisibility(View.VISIBLE);

        if (mimeType != null && mimeType.startsWith("video/")) {
            imageView.setVisibility(View.GONE);
            videoView.setVisibility(View.VISIBLE);
            Glide.with(this)
                    .asFile()
                    .load(url)
                    .into(new CustomTarget<File>() {
                        @Override
                        public void onResourceReady(@NonNull File resource, @Nullable Transition<? super File> transition) {
                            if (!isAdded()) return;
                            progressBar.setVisibility(View.GONE);
                            videoView.setVideoURI(Uri.fromFile(resource));
                            videoView.setOnPreparedListener(mp -> {
                                mp.setLooping(true);
                                videoView.start();
                            });
                        }

                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholder) {
                            // Do nothing
                        }

                        @Override
                        public void onLoadFailed(@Nullable Drawable errorDrawable) {
                            if (!isAdded()) return;
                            progressBar.setVisibility(View.GONE);
                            Toast.makeText(getContext(), R.string.download_failed_file_not_found, Toast.LENGTH_SHORT).show();
                        }
                    });
        } else {
            videoView.setVisibility(View.GONE);
            imageView.setVisibility(View.VISIBLE);
            Glide.with(this)
                    .load(url)
                    .listener(new RequestListener<Drawable>() {
                        @Override
                        public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                            if (isAdded()) {
                                progressBar.setVisibility(View.GONE);
                                Toast.makeText(getContext(), R.string.download_failed_file_not_found, Toast.LENGTH_SHORT).show();
                            }
                            return false;
                        }

                        @Override
                        public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                            if (isAdded()) {
                                progressBar.setVisibility(View.GONE);
                            }
                            return false;
                        }
                    })
                    .into(imageView);
        }
    }


    @Override
    public void onDetach() {
        super.onDetach();
        mListener = null;
    }
}
