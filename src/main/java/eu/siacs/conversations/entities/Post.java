package eu.siacs.conversations.entities;import static eu.siacs.conversations.parser.AbstractParser.parseTimestamp;
import static eu.siacs.conversations.parser.AbstractParser.parseTimestampAtom;

import android.util.Log;

import java.text.ParseException;
import java.util.Date;
import java.util.Objects;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;

public class Post {

    public static final String TABLENAME = "posts";
    public static final String UUID = "uuid";
    public static final String ACCOUNT_UUID = "account_uuid";
    public static final String AUTHOR_JID = "author_jid";
    public static final String TITLE = "title";
    public static final String CONTENT = "content";
    public static final String ATTACHMENT_URL = "attachment_url";
    public static final String ATTACHMENT_TYPE = "attachment_type";
    public static final String PUBLISHED = "published";
    public static final String COMMENTS_NODE = "comments_node";

    private final String id;
    private final String title;
    private final String content;
    private final Jid author;
    private final Date published;
    private final String commentsNode;
    private final String attachmentUrl;
    private final String attachmentType;

    public Post(String id, String title, String content, Jid author, Date published, String commentsNode, String attachmentUrl, String attachmentType) {
        this.id = id;
        this.title = title;
        this.content = content;
        this.author = author;
        this.published = published;
        this.commentsNode = commentsNode;
        this.attachmentUrl = attachmentUrl;
        this.attachmentType = attachmentType;
    }

    public static Post fromElement(Element item) {
        final Element entry = item.findChild("entry", Namespace.ATOM);
        if (entry == null) {
            return null;
        }
        String id = item.getAttribute("id");
        String title = entry.findChildContent("title");
        String content = entry.findChildContent("content");
        Element authorElement = entry.findChild("author");
        Jid author = null;
        if (authorElement != null) {
            String uri = authorElement.findChildContent("uri");
            if (uri != null && uri.startsWith("xmpp:")) {
                try {
                    author = Jid.of(uri.substring(5));
                } catch (IllegalArgumentException e) {
                    // ignore
                }
            }
        }
        Date published = null;
        final String publishedString = entry.findChildContent("published");
        if (publishedString != null) {
            try {
                published = new Date(parseTimestamp(publishedString));
            } catch (Exception e) {
                try {
                    published =  new Date(parseTimestampAtom(publishedString));
                } catch (ParseException error) {
                    Log.e("Feeds", "Couldn't parse timestamp " + publishedString);
                }
            }
        }

        String commentsNode = null;
        String attachmentUrl = null;
        String attachmentType = null;
        for (Element child : entry.getChildren()) {
            if ("link".equals(child.getName()) && Namespace.ATOM.equals(child.getNamespace())) {
                String rel = child.getAttribute("rel");
                if ("replies".equals(rel)) {
                    commentsNode = child.getAttribute("href");
                } else if ("enclosure".equals(rel)) {
                    attachmentUrl = child.getAttribute("href");
                    attachmentType = child.getAttribute("type");
                }
            }
        }

        return new Post(id, title, content, author, published, commentsNode, attachmentUrl, attachmentType);
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getContent() {
        return content;
    }

    public Jid getAuthor() {
        return author;
    }

    public Date getPublished() {
        return published;
    }

    public String getCommentsNode() {
        return commentsNode;
    }

    public String getAttachmentUrl() {
        return attachmentUrl;
    }

    public String getAttachmentType() {
        return attachmentType;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Post post = (Post) o;
        return Objects.equals(id, post.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }


    public static Post fromCursor(android.database.Cursor cursor) {
        final String uuid = cursor.getString(cursor.getColumnIndex(UUID));
        final String authorJidStr = cursor.getString(cursor.getColumnIndex(AUTHOR_JID));
        eu.siacs.conversations.xmpp.Jid authorJid = null;
        try {
            if(authorJidStr != null) {
                authorJid = eu.siacs.conversations.xmpp.Jid.of(authorJidStr);
            }
        } catch (IllegalArgumentException e) {
            //ignore
        }
        final String title = cursor.getString(cursor.getColumnIndex(TITLE));
        final String content = cursor.getString(cursor.getColumnIndex(CONTENT));
        final String attachmentUrl = cursor.getString(cursor.getColumnIndex(ATTACHMENT_URL));
        final String attachmentType = cursor.getString(cursor.getColumnIndex(ATTACHMENT_TYPE));
        final long published = cursor.getLong(cursor.getColumnIndex(PUBLISHED));
        final String commentsNode = cursor.getString(cursor.getColumnIndex(COMMENTS_NODE));
        return new Post(uuid, title, content, authorJid, new java.util.Date(published), commentsNode, attachmentUrl, attachmentType);
    }

    public android.content.ContentValues getContentValues(eu.siacs.conversations.entities.Account account) {
        android.content.ContentValues values = new android.content.ContentValues();
        values.put(UUID, getId());
        values.put(ACCOUNT_UUID, account.getUuid());
        values.put(AUTHOR_JID, getAuthor() != null ? getAuthor().toString() : null);
        values.put(TITLE, getTitle());
        values.put(CONTENT, getContent());
        values.put(ATTACHMENT_URL, getAttachmentUrl());
        values.put(ATTACHMENT_TYPE, getAttachmentType());
        values.put(PUBLISHED, getPublished() != null ? getPublished().getTime() : 0);
        values.put(COMMENTS_NODE, getCommentsNode());
        return values;
    }
}