package eu.siacs.conversations.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.drawable.Drawable;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
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
import java.util.ArrayList;

import eu.siacs.conversations.R;

public class StoryFragment extends Fragment {

    private static final String ARG_URL = "url";
    private static final String ARG_MIME_TYPE = "mime_type";
    private static final long STORY_DURATION_MS = 6000;

    private OnStoryInteractionListener mListener;
    private VideoView videoView;
    private ObjectAnimator currentAnimator;
    private LinearLayout progressBarContainer;
    private ArrayList<String> urls;
    private final Handler videoProgressHandler = new Handler(Looper.getMainLooper());
    private Runnable videoProgressRunnable;

    public interface OnStoryInteractionListener {
        void onNextStory();

        void pauseStory();

        void resumeStory();
    }

    public static StoryFragment newInstance(String url, String mimeType, ArrayList<String> urls) {
        StoryFragment fragment = new StoryFragment();
        Bundle args = new Bundle();
        args.putString(ARG_URL, url);
        args.putString(ARG_MIME_TYPE, mimeType);
        args.putStringArrayList("urls", urls);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof OnStoryInteractionListener) {
            mListener = (OnStoryInteractionListener) context;
        } else {
            throw new RuntimeException(context.toString() + " must implement OnStoryInteractionListener");
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

        view.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    pauseStory();
                    return true;
                case MotionEvent.ACTION_UP:
                    resumeStory();
                    return true;
            }
            return false;
        });

        progressBarContainer = requireActivity().findViewById(R.id.progress_bar_container);
        if (getArguments() != null) {
            urls = getArguments().getStringArrayList("urls");
        }
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
        videoView = view.findViewById(R.id.story_video_view);
        final ProgressBar progressBar = view.findViewById(R.id.story_progress);

        final Bundle args = getArguments();
        if (args == null) {
            return;
        }

        final String url = args.getString(ARG_URL);
        final String mimeType = args.getString(ARG_MIME_TYPE);

        progressBar.setVisibility(View.VISIBLE);

        if (urls == null) {
            return;
        }

        final int currentPosition = urls.indexOf(url);

        if (mimeType != null && mimeType.startsWith("video/")) {
            imageView.setVisibility(View.GONE);
            videoView.setVisibility(View.VISIBLE);
            videoView.setOnCompletionListener(mp -> {
                if (mListener != null) {
                    mListener.onNextStory();
                }
            });
            Glide.with(this)
                    .asFile()
                    .load(url)
                    .into(new CustomTarget<File>() {
                        @Override
                        public void onResourceReady(@NonNull File resource, @Nullable Transition<? super File> transition) {
                            if (!isAdded()) return;
                            videoView.setVideoURI(Uri.fromFile(resource));
                            videoView.setOnPreparedListener(mp -> {
                                progressBar.setVisibility(View.GONE);
                                mp.setLooping(false);
                                videoView.start();
                                final int duration = mp.getDuration();
                                if (progressBarContainer.getChildCount() > currentPosition) {
                                    final ProgressBar currentStoryProgressBar = (ProgressBar) progressBarContainer.getChildAt(currentPosition);
                                    if (currentStoryProgressBar != null) {
                                        videoProgressRunnable = new Runnable() {
                                            @Override
                                            public void run() {
                                                if (videoView != null && videoView.isPlaying()) {
                                                    int currentPosition = videoView.getCurrentPosition();
                                                    int progress = (int) (((float) currentPosition / duration) * 1000);
                                                    currentStoryProgressBar.setProgress(progress);
                                                    videoProgressHandler.postDelayed(this, 50);
                                                }
                                            }
                                        };
                                        videoProgressHandler.post(videoProgressRunnable);
                                    }
                                }
                            });
                        }

                        @Override
                        public void onLoadCleared(@Nullable Drawable placeholder) {
                            // Do nothing
                        }

                        @Override
                        public void onLoadFailed(@Nullable Drawable errorDrawable) {
                            super.onLoadFailed(errorDrawable);
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
            if (progressBarContainer.getChildCount() > currentPosition) {
                final ProgressBar currentProgressBar = (ProgressBar) progressBarContainer.getChildAt(currentPosition);
                currentAnimator = ObjectAnimator.ofInt(currentProgressBar, "progress", 0, 1000);
                currentAnimator.setDuration(STORY_DURATION_MS);
                currentAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        super.onAnimationEnd(animation);
                        if (mListener != null) {
                            mListener.onNextStory();
                        }
                    }
                });
                currentAnimator.start();
            }
        }
    }

    public void pauseVideo() {
        if (videoView != null && videoView.isPlaying()) {
            videoView.pause();
            if (videoProgressRunnable != null) {
                videoProgressHandler.removeCallbacks(videoProgressRunnable);
            }
        }
    }

    public void resumeVideo() {
        if (videoView != null && !videoView.isPlaying()) {
            videoView.start();
            if (videoProgressRunnable != null) {
                videoProgressHandler.post(videoProgressRunnable);
            }
        }
    }

    public void pauseStory() {
        if (currentAnimator != null && currentAnimator.isRunning()) {
            currentAnimator.pause();
        }
        mListener.pauseStory();
        pauseVideo();
    }

    public void resumeStory() {
        if (currentAnimator != null && currentAnimator.isPaused()) {
            currentAnimator.resume();
        }
        mListener.resumeStory();
        resumeVideo();
    }


    @Override
    public void onDetach() {
        super.onDetach();
        if (videoProgressRunnable != null) {
            videoProgressHandler.removeCallbacks(videoProgressRunnable);
        }
        mListener = null;
    }
}