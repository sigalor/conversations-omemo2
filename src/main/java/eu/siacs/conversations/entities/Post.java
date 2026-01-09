package eu.siacs.conversations.entities;import static eu.siacs.conversations.parser.AbstractParser.parseTimestamp;

import java.util.Date;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;

public class Post {

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

    public static Post fromElement(Element entry) {
        String id = entry.findChildContent("id", Namespace.ATOM);
        String title = entry.findChildContent("title", Namespace.ATOM);
        String content = entry.findChildContent("content", Namespace.ATOM);
        Element authorElement = entry.findChild("author", Namespace.ATOM);
        Jid author = null;
        if (authorElement != null) {
            String uri = authorElement.findChildContent("uri", Namespace.ATOM);
            if (uri != null && uri.startsWith("xmpp:")) {
                try {
                    author = Jid.of(uri.substring(5));
                } catch (IllegalArgumentException e) {
                    // ignore
                }
            }
        }
        Date published = null;
        final String publishedString = entry.findChildContent("published", Namespace.ATOM);
        if (publishedString != null) {
            try {
                published = new Date(parseTimestamp(publishedString));
            } catch (Exception e) {
                // ignore
            }
        }

        String commentsNode = null;
        String attachmentUrl = null;
        String attachmentType = null;
        for (Element link : entry.getChildren()) {
            if ("link".equals(link.getName()) && Namespace.ATOM.equals(link.getNamespace())) {
                String rel = link.getAttribute("rel");
                if ("replies".equals(rel)) {
                    commentsNode = link.getAttribute("href");
                } else if ("enclosure".equals(rel)) {
                    attachmentUrl = link.getAttribute("href");
                    attachmentType = link.getAttribute("type");
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
}