package eu.siacs.conversations.ui.adapter;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.AsyncTask;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.DimenRes;
import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.core.widget.ImageViewCompat;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.color.MaterialColors;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableList;
import eu.siacs.conversations.R;
import eu.siacs.conversations.databinding.ItemMediaBinding;
import eu.siacs.conversations.databinding.ItemDateSeparatorBinding;
import eu.siacs.conversations.ui.XmppActivity;
import eu.siacs.conversations.ui.util.Attachment;
import eu.siacs.conversations.ui.util.ViewUtil;
import eu.siacs.conversations.utils.MimeUtils;
import eu.siacs.conversations.worker.ExportBackupWorker;

import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;

public class MediaAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public static final int VIEW_TYPE_MEDIA = 0;
    public static final int VIEW_TYPE_DATE_SEPARATOR = 1;

    public static final List<String> DOCUMENT_MIMES =
            new ImmutableList.Builder<String>()
                    .add("application/pdf")
                    .add("text/x-tex")
                    .add("text/plain")
                    .addAll(MimeUtils.WORD_DOCUMENT_MIMES)
                    .build();
    public static final List<String> SPREAD_SHEET_MIMES =
            Arrays.asList(
                    "text/comma-separated-values",
                    "application/vnd.ms-excel",
                    "application/vnd.stardivision.calc",
                    "application/vnd.oasis.opendocument.spreadsheet",
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    public static final List<String> SLIDE_SHOW_MIMES =
            Arrays.asList(
                    "application/vnd.ms-powerpoint",
                    "application/vnd.stardivision.impress",
                    "application/vnd.oasis.opendocument.presentation",
                    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
                    "application/vnd.openxmlformats-officedocument.presentationml.slideshow");

    private static final List<String> ARCHIVE_MIMES =
            Arrays.asList(
                    "application/x-7z-compressed",
                    "application/zip",
                    "application/rar",
                    "application/x-gtar",
                    "application/x-tar");
    public static final List<String> CODE_MIMES = Arrays.asList("text/html", "text/xml");

    private final ArrayList<Object> items = new ArrayList<>();
    private HashSet<Attachment> selectedAttachments = new HashSet<>();
    private boolean selectionMode = false;

    private final XmppActivity activity;

    private int mediaSize = 0;

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int count);
    }

    private OnSelectionChangedListener selectionChangedListener;

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.selectionChangedListener = listener;
    }

    public void setSelectedAttachments(HashSet<Attachment> selectedAttachments) {
        this.selectedAttachments = selectedAttachments;
        this.selectionMode = !selectedAttachments.isEmpty();
        notifyDataSetChanged();
    }

    public MediaAdapter(XmppActivity activity, @DimenRes int mediaSize) {
        this.activity = activity;
        this.mediaSize = Math.round(activity.getResources().getDimension(mediaSize));
    }

    public void toggleSelection(Attachment attachment) {
        if (selectedAttachments.contains(attachment)) {
            selectedAttachments.remove(attachment);
            if (selectedAttachments.isEmpty()) {
                selectionMode = false;
            }
        } else {
            selectedAttachments.add(attachment);
            selectionMode = true;
        }
        notifyDataSetChanged();
        if (selectionChangedListener != null) {
            selectionChangedListener.onSelectionChanged(selectedAttachments.size());
        }
    }

    public void clearSelection() {
        selectedAttachments.clear();
        selectionMode = false;
        notifyDataSetChanged();
        if (selectionChangedListener != null) {
            selectionChangedListener.onSelectionChanged(0);
        }
    }

    public HashSet<Attachment> getSelectedAttachments() {
        return selectedAttachments;
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    @SuppressWarnings("rawtypes")
    public static void setMediaSize(final RecyclerView recyclerView, int mediaSize) {
        final RecyclerView.Adapter adapter = recyclerView.getAdapter();
        if (adapter instanceof MediaAdapter mediaAdapter) {
            mediaAdapter.setMediaSize(mediaSize);
        }
    }

    public static @DrawableRes int getImageDrawable(final Attachment attachment) {
        if (attachment.getType() == Attachment.Type.LOCATION) {
            return R.drawable.ic_location_pin_48dp;
        } else if (attachment.getType() == Attachment.Type.RECORDING) {
            return R.drawable.ic_mic_48dp;
        } else {
            return getImageDrawable(attachment.getMime());
        }
    }

    private static @DrawableRes int getImageDrawable(final String mime) {
        if (Strings.isNullOrEmpty(mime)) {
            return R.drawable.ic_help_center_48dp;
        } else if (mime.equals("audio/x-m4b")) {
            return R.drawable.ic_play_lesson_48dp;
        } else if (mime.startsWith("audio/")) {
            return R.drawable.ic_headphones_48dp;
        } else if (mime.equals("text/calendar") || (mime.equals("text/x-vcalendar"))) {
            return R.drawable.ic_event_48dp;
        } else if (mime.equals("text/x-vcard")) {
            return R.drawable.ic_person_48dp;
        } else if (mime.equals("application/vnd.android.package-archive")) {
            return R.drawable.ic_adb_48dp;
        } else if (ARCHIVE_MIMES.contains(mime)) {
            return R.drawable.ic_archive_48dp;
        } else if (mime.equals("application/epub+zip")
                || mime.equals("application/vnd.amazon.mobi8-ebook")) {
            return R.drawable.ic_book_48dp;
        } else if (mime.equals(ExportBackupWorker.MIME_TYPE)) {
            return R.drawable.ic_backup_48dp;
        } else if (DOCUMENT_MIMES.contains(mime)) {
            return R.drawable.ic_description_48dp;
        } else if (SPREAD_SHEET_MIMES.contains(mime)) {
            return R.drawable.ic_table_48dp;
        } else if (SLIDE_SHOW_MIMES.contains(mime)) {
            return R.drawable.ic_slideshow_48dp;
        } else if (mime.equals("application/gpx+xml")) {
            return R.drawable.ic_tour_48dp;
        } else if (mime.startsWith("image/")) {
            return R.drawable.ic_image_48dp;
        } else if (mime.startsWith("video/")) {
            return R.drawable.ic_movie_48dp;
        } else if (CODE_MIMES.contains(mime)) {
            return R.drawable.ic_code_48dp;
        } else if (mime.equals("message/rfc822")) {
            return R.drawable.ic_email_48dp;
        } else if (mime.equals("application/webxdc+zip")) {
            return R.drawable.toys_and_games_24dp;
        } else {
            return R.drawable.ic_help_center_48dp;
        }
    }

    static void renderPreview(final Attachment attachment, final ImageView imageView) {
        ImageViewCompat.setImageTintList(
                imageView,
                ColorStateList.valueOf(
                        MaterialColors.getColor(
                                imageView, com.google.android.material.R.attr.colorOnSurface)));
        imageView.setImageResource(getImageDrawable(attachment));
        imageView.setBackgroundColor(
                MaterialColors.getColor(
                        imageView,
                        com.google.android.material.R.attr.colorSurfaceContainerHighest));
    }

    private static boolean cancelPotentialWork(Attachment attachment, ImageView imageView) {
        final BitmapWorkerTask bitmapWorkerTask = getBitmapWorkerTask(imageView);

        if (bitmapWorkerTask != null) {
            final Attachment oldAttachment = bitmapWorkerTask.attachment;
            if (oldAttachment == null || !oldAttachment.equals(attachment)) {
                bitmapWorkerTask.cancel(true);
            } else {
                return false;
            }
        }
        return true;
    }

    private static BitmapWorkerTask getBitmapWorkerTask(ImageView imageView) {
        if (imageView != null) {
            final Drawable drawable = imageView.getDrawable();
            if (drawable instanceof AsyncDrawable asyncDrawable) {
                return asyncDrawable.getBitmapWorkerTask();
            }
        }
        return null;
    }

    @Override
    public int getItemViewType(int position) {
        return items.get(position) instanceof DateSeparator ? VIEW_TYPE_DATE_SEPARATOR : VIEW_TYPE_MEDIA;
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        final LayoutInflater layoutInflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_DATE_SEPARATOR) {
            ItemDateSeparatorBinding binding =
                    DataBindingUtil.inflate(layoutInflater, R.layout.item_date_separator, parent, false);
            return new DateSeparatorViewHolder(binding);
        } else {
            ItemMediaBinding binding =
                    DataBindingUtil.inflate(layoutInflater, R.layout.item_media, parent, false);
            return new MediaViewHolder(binding);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Object item = items.get(position);
        if (holder instanceof MediaViewHolder mediaViewHolder && item instanceof Attachment attachment) {
            if (attachment.renderThumbnail()) {
                loadPreview(attachment, mediaViewHolder.binding.media);
            } else {
                cancelPotentialWork(attachment, mediaViewHolder.binding.media);
                renderPreview(attachment, mediaViewHolder.binding.media);
            }

            final boolean isSelected = selectedAttachments.contains(attachment);
            mediaViewHolder.binding.selectionOverlay.setVisibility(isSelected ? View.VISIBLE : View.GONE);
            mediaViewHolder.binding.selectionCheck.setVisibility(isSelected ? View.VISIBLE : View.GONE);

            mediaViewHolder.binding.getRoot().setOnClickListener(v -> {
                if (selectionMode) {
                    toggleSelection(attachment);
                } else {
                    String convUuid = activity.getIntent().getStringExtra("conversation_uuid");
                    String accountUuid = activity.getIntent().getStringExtra("account");
                    String jid = activity.getIntent().getStringExtra("jid");
                    ViewUtil.view(activity, attachment, convUuid, accountUuid, jid);
                }
            });

            mediaViewHolder.binding.getRoot().setOnLongClickListener(v -> {
                if (!selectionMode) {
                    toggleSelection(attachment);
                    return true;
                }
                return false;
            });

            mediaViewHolder.binding.getRoot().setOnCreateContextMenuListener((menu, v, menuInfo) -> {
                if (selectionMode) return;
                final var path = activity.xmppConnectionService.getFileBackend().getOriginalPath(attachment.getUri());
                if (path == null) return;
                final var file = new File(path);
                if (!file.canWrite()) return;

                menu.add("Delete File").setOnMenuItemClickListener((x) -> {
                    if (file.delete()) {
                        activity.xmppConnectionService.evictPreview(file);
                        items.remove(attachment);
                        notifyDataSetChanged();
                    }
                    return true;
                });
            });
        } else if (holder instanceof DateSeparatorViewHolder dateSeparatorViewHolder && item instanceof DateSeparator dateSeparator) {
            dateSeparatorViewHolder.binding.date.setText(android.text.format.DateUtils.formatDateTime(activity, dateSeparator.timestamp, android.text.format.DateUtils.FORMAT_SHOW_DATE | android.text.format.DateUtils.FORMAT_SHOW_YEAR | android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY));
        }
    }

    public void setAttachments(final List<Attachment> attachments) {
        this.items.clear();
        if (attachments != null && !attachments.isEmpty()) {
            java.util.Calendar calendar = java.util.Calendar.getInstance();
            int currentDay = -1;
            int currentMonth = -1;
            int currentYear = -1;
            for (Attachment attachment : attachments) {
                calendar.setTimeInMillis(attachment.getTimestamp());
                int day = calendar.get(java.util.Calendar.DAY_OF_YEAR);
                int month = calendar.get(java.util.Calendar.MONTH);
                int year = calendar.get(java.util.Calendar.YEAR);
                if (day != currentDay || month != currentMonth || year != currentYear) {
                    items.add(new DateSeparator(attachment.getTimestamp()));
                    currentDay = day;
                    currentMonth = month;
                    currentYear = year;
                }
                items.add(attachment);
            }
        }
        notifyDataSetChanged();
    }

    private void setMediaSize(int mediaSize) {
        this.mediaSize = mediaSize;
    }

    private void loadPreview(Attachment attachment, ImageView imageView) {
        if (cancelPotentialWork(attachment, imageView)) {
            final Bitmap bm =
                    activity.xmppConnectionService
                            .getFileBackend()
                            .getPreviewForUri(attachment, mediaSize, true);
            if (bm != null) {
                cancelPotentialWork(attachment, imageView);
                imageView.setImageBitmap(bm);
                imageView.setBackgroundColor(Color.TRANSPARENT);
            } else {
                // TODO consider if this is still a good, general purpose loading color
                imageView.setBackgroundColor(0xff333333);
                imageView.setImageDrawable(null);
                final BitmapWorkerTask task = new BitmapWorkerTask(mediaSize, imageView);
                final AsyncDrawable asyncDrawable =
                        new AsyncDrawable(activity.getResources(), null, task);
                imageView.setImageDrawable(asyncDrawable);
                try {
                    task.execute(attachment);
                } catch (final RejectedExecutionException ignored) {
                }
            }
        }
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class AsyncDrawable extends BitmapDrawable {
        private final WeakReference<BitmapWorkerTask> bitmapWorkerTaskReference;

        AsyncDrawable(Resources res, Bitmap bitmap, BitmapWorkerTask bitmapWorkerTask) {
            super(res, bitmap);
            bitmapWorkerTaskReference = new WeakReference<>(bitmapWorkerTask);
        }

        BitmapWorkerTask getBitmapWorkerTask() {
            return bitmapWorkerTaskReference.get();
        }
    }

    static class MediaViewHolder extends RecyclerView.ViewHolder {

        private final ItemMediaBinding binding;

        MediaViewHolder(ItemMediaBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    static class DateSeparatorViewHolder extends RecyclerView.ViewHolder {
        private final ItemDateSeparatorBinding binding;

        DateSeparatorViewHolder(ItemDateSeparatorBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
        }
    }

    public static class DateSeparator {
        public final long timestamp;

        public DateSeparator(long timestamp) {
            this.timestamp = timestamp;
        }
    }

    private static class BitmapWorkerTask extends AsyncTask<Attachment, Void, Bitmap> {
        private final WeakReference<ImageView> imageViewReference;
        private Attachment attachment = null;
        private final int mediaSize;

        BitmapWorkerTask(int mediaSize, ImageView imageView) {
            this.mediaSize = mediaSize;
            imageViewReference = new WeakReference<>(imageView);
        }

        @Override
        protected Bitmap doInBackground(final Attachment... params) {
            this.attachment = params[0];
            final XmppActivity activity = XmppActivity.find(imageViewReference);
            if (activity == null) {
                return null;
            }
            return activity.xmppConnectionService
                    .getFileBackend()
                    .getPreviewForUri(this.attachment, mediaSize, false);
        }

        @Override
        protected void onPostExecute(Bitmap bitmap) {
            if (bitmap != null && !isCancelled()) {
                final ImageView imageView = imageViewReference.get();
                if (imageView != null) {
                    imageView.setImageBitmap(bitmap);
                    imageView.setBackgroundColor(0x00000000);
                }
            }
        }
    }
}
