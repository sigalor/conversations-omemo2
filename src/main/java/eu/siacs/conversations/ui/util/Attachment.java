/*
 * Copyright (c) 2018, Daniel Gultsch All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 * list of conditions and the following disclaimer.
 *
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 * this list of conditions and the following disclaimer in the documentation and/or
 * other materials provided with the distribution.
 *
 * 3. Neither the name of the copyright holder nor the names of its contributors
 * may be used to endorse or promote products derived from this software without
 * specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package eu.siacs.conversations.ui.util;

import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;

import androidx.annotation.NonNull;

import com.google.common.base.MoreObjects;

import eu.siacs.conversations.entities.Message;
import eu.siacs.conversations.utils.MimeUtils;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class Attachment implements Parcelable {

    Attachment(Parcel in) {
        uri = in.readParcelable(Uri.class.getClassLoader());
        mime = in.readString();
        uuid = UUID.fromString(in.readString());
        type = Type.valueOf(in.readString());
        timestamp = in.readLong();
        conversationUuid = in.readString();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(uri, flags);
        dest.writeString(mime);
        dest.writeString(uuid.toString());
        dest.writeString(type.toString());
        dest.writeLong(timestamp);
        dest.writeString(conversationUuid);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<Attachment> CREATOR =
            new Creator<Attachment>() {
                @Override
                public Attachment createFromParcel(Parcel in) {
                    return new Attachment(in);
                }

                @Override
                public Attachment[] newArray(int size) {
                    return new Attachment[size];
                }
            };

    public String getMime() {
        return mime;
    }

    public Type getType() {
        return type;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public String getConversationUuid() {
        return conversationUuid;
    }

    @NonNull
    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("uri", uri)
                .add("type", type)
                .add("uuid", uuid)
                .add("mime", mime)
                .add("timestamp", timestamp)
                .add("conversationUuid", conversationUuid)
                .toString();
    }

    public enum Type {
        FILE,
        IMAGE,
        LOCATION,
        RECORDING
    }

    private final Uri uri;
    private final Type type;
    private final UUID uuid;
    private final String mime;
    private final long timestamp;
    private final String conversationUuid;

    private Attachment(UUID uuid, Uri uri, Type type, String mime, long timestamp, String conversationUuid) {
        this.uri = uri;
        this.type = type;
        this.mime = mime;
        this.uuid = uuid;
        this.timestamp = timestamp;
        this.conversationUuid = conversationUuid;
    }

    private Attachment(Uri uri, Type type, String mime, long timestamp, String conversationUuid) {
        this.uri = uri;
        this.type = type;
        this.mime = mime;
        this.uuid = UUID.randomUUID();
        this.timestamp = timestamp;
        this.conversationUuid = conversationUuid;
    }

    public static boolean canBeSendInBand(final List<Attachment> attachments) {
        for (final Attachment attachment : attachments) {
            if (attachment.type != Type.LOCATION && !"https".equals(attachment.uri.getScheme())) {
                return false;
            }
        }
        return true;
    }

    public static List<Attachment> of(final Context context, Uri uri, Type type) {
        final String mime =
                type == Type.LOCATION ? null : MimeUtils.guessMimeTypeFromUri(context, uri);
        return Collections.singletonList(new Attachment(uri, type, mime, System.currentTimeMillis(), null));
    }

    public static Attachment of(final Message message) {
        final UUID uuid = UUID.fromString(message.getUuid());
        final String conversationUuid = message.getConversation().getUuid();
        if (message.isGeoUri()) {
            return new Attachment(uuid, Uri.EMPTY, Type.LOCATION, null, message.getTimeSent(), conversationUuid);
        }
        final String mime = message.getMimeType();
        if (MimeUtils.AMBIGUOUS_CONTAINER_FORMATS.contains(mime)) {
            final Message.FileParams fileParams = message.getFileParams();
            if (fileParams.width > 0 && fileParams.height > 0) {
                return new Attachment(uuid, Uri.EMPTY, Type.FILE, "video/*", message.getTimeSent(), conversationUuid);
            } else if (fileParams.runtime > 0) {
                return new Attachment(uuid, Uri.EMPTY, Type.FILE, "audio/*", message.getTimeSent(), conversationUuid);
            } else {
                return new Attachment(uuid, Uri.EMPTY, Type.FILE, "application/octet-stream", message.getTimeSent(), conversationUuid);
            }
        }
        return new Attachment(uuid, Uri.EMPTY, Type.FILE, mime, message.getTimeSent(), conversationUuid);
    }

    public static List<Attachment> of(final Context context, List<Uri> uris, final String type) {
        final List<Attachment> attachments = new ArrayList<>();
        for (final Uri uri : uris) {
            if (uri == null) {
                continue;
            }
            final String mime = MimeUtils.guessMimeTypeFromUriAndMime(context, uri, type);
            attachments.add(
                    new Attachment(
                            uri, mime != null && isImage(mime) ? Type.IMAGE : Type.FILE, mime, System.currentTimeMillis(), null));
        }
        return attachments;
    }

    public static Attachment of(UUID uuid, final File file, String mime) {
        return of(uuid, file, mime, System.currentTimeMillis(), null);
    }

    public static Attachment of(UUID uuid, final File file, String mime, long timestamp, String conversationUuid) {
        return new Attachment(
                uuid,
                Uri.fromFile(file),
                mime != null && (isImage(mime) || mime.startsWith("video/"))
                        ? Type.IMAGE
                        : Type.FILE,
                mime,
                timestamp,
                conversationUuid);
    }

    public static List<Attachment> extractAttachments(
            final Context context, final Intent intent, Type type) {
        List<Attachment> uris = new ArrayList<>();
        if (intent == null) {
            return uris;
        }
        final String contentType = intent.getType();
        final Uri data = intent.getData();
        if (data == null) {
            final ClipData clipData = intent.getClipData();
            if (clipData != null) {
                for (int i = 0; i < clipData.getItemCount(); ++i) {
                    final Uri uri = clipData.getItemAt(i).getUri();
                    final String mime =
                            MimeUtils.guessMimeTypeFromUriAndMime(context, uri, contentType);
                    uris.add(new Attachment(uri, type, mime, System.currentTimeMillis(), null));
                }
            }
        } else {
            final String mime = MimeUtils.guessMimeTypeFromUriAndMime(context, data, contentType);
            uris.add(new Attachment(data, type, mime, System.currentTimeMillis(), null));
        }
        return uris;
    }

    public boolean renderThumbnail() {
        return type == Type.IMAGE
                || (type == Type.FILE && mime != null && renderFileThumbnail(mime));
    }

    private static boolean renderFileThumbnail(final String mime) {
        return mime.startsWith("video/") || isImage(mime) || "application/pdf".equals(mime);
    }

    public Uri getUri() {
        return uri;
    }

    public UUID getUuid() {
        return uuid;
    }

    private static boolean isImage(final String mime) {
        return mime.startsWith("image/");
    }
}
