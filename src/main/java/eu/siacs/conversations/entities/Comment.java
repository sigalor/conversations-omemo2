package eu.siacs.conversations.entities;

import static eu.siacs.conversations.parser.AbstractParser.parseTimestamp;
import static eu.siacs.conversations.parser.AbstractParser.parseTimestampAtom;

import android.util.Log;

import java.text.ParseException;
import java.util.Date;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;

public class Comment {

    private final String id;
    private final String title;
    private final Jid author;
    private final Date published;

    public Comment(String id, String title, Jid author, Date published) {
        this.id = id;
        this.title = title;
        this.author = author;
        this.published = published;
    }

    public static Comment fromElement(Element entry) {
        String id = entry.findChildContent("id", Namespace.ATOM);
        String title = entry.findChildContent("title", Namespace.ATOM);
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
        return new Comment(id, title, author, published);
    }

    public String getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public Jid getAuthor() {
        return author;
    }

    public Date getPublished() {
        return published;
    }
}