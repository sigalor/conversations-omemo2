package eu.siacs.conversations.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.GridLayoutManager;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.FragmentMediaBrowserBinding;
import eu.siacs.conversations.ui.adapter.MediaAdapter;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.GridManager;

public class MediaBrowserFragment extends Fragment {

    public enum MediaType {
        ALL, IMAGES, VIDEOS, AUDIO, FILES
    }

    private MediaAdapter mMediaAdapter;
    private MediaType mType = MediaType.ALL;
    private List<Attachment> mAllAttachments = new ArrayList<>();

    public static MediaBrowserFragment create(MediaType type) {
        MediaBrowserFragment fragment = new MediaBrowserFragment();
        Bundle args = new Bundle();
        args.putString("type", type.name());
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            mType = MediaType.valueOf(getArguments().getString("type"));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FragmentMediaBrowserBinding binding = DataBindingUtil.inflate(inflater, R.layout.fragment_media_browser, container, false);
        mMediaAdapter = new MediaAdapter((XmppActivity) requireActivity(), R.dimen.media_size);
        if (getActivity() instanceof MediaBrowserActivity activity) {
            mMediaAdapter.setOnSelectionChangedListener(activity.getSelectionChangedListener());
            mMediaAdapter.setSelectedAttachments(activity.getSelectedAttachments());
        }
        binding.media.setAdapter(mMediaAdapter);
        GridManager.setupLayoutManager(requireActivity(), binding.media, R.dimen.browser_media_size);

        if (binding.media.getLayoutManager() instanceof GridLayoutManager gridLayoutManager) {
            gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    return mMediaAdapter.getItemViewType(position) == MediaAdapter.VIEW_TYPE_DATE_SEPARATOR ? gridLayoutManager.getSpanCount() : 1;
                }
            });
        }
        updateAdapter();
        return binding.getRoot();
    }

    public void setAttachments(List<Attachment> attachments) {
        mAllAttachments = attachments;
        updateAdapter();
    }

    private void updateAdapter() {
        if (mMediaAdapter == null) return;
        List<Attachment> filtered = filter(mAllAttachments);
        mMediaAdapter.setAttachments(filtered);
    }

    private List<Attachment> filter(List<Attachment> attachments) {
        if (attachments == null) return new ArrayList<>();
        return attachments.stream().filter(this::matches).collect(Collectors.toList());
    }

    private boolean matches(Attachment attachment) {
        String mime = attachment.getMime();
        if (mime == null) mime = "";
        return switch (mType) {
            case ALL -> true;
            case IMAGES -> mime.startsWith("image/");
            case VIDEOS -> mime.startsWith("video/");
            case AUDIO -> mime.startsWith("audio/") || attachment.getType() == Attachment.Type.RECORDING;
            case FILES -> !mime.startsWith("image/") && !mime.startsWith("video/") && !mime.startsWith("audio/") && attachment.getType() != Attachment.Type.RECORDING;
        };
    }

    public MediaAdapter getMediaAdapter() {
        return mMediaAdapter;
    }
}
