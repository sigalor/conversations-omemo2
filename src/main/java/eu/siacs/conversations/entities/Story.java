package eu.siacs.conversations.entities;

import static eu.siacs.conversations.parser.AbstractParser.parseTimestamp;

import android.content.ContentValues;

import java.util.ArrayList;
import java.util.List;

import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xmpp.Jid;

public class Story extends AbstractEntity {

    public static final String CONTACT = "contact";
    public static final String URL = "url";
    public static final String TYPE = "type";
    public static final String TITLE = "title";
    public static final String PUBLISHED = "published";

    private final Jid contact;
    private final String url;
    private final String type;
    private final String title;
    private final long published;

    public Story(final String uuid, final Jid contact, final String url, final String type, final String title, final long published) {
        this.uuid = uuid;
        this.contact = contact;
        this.url = url;
        this.type = type;
        this.title = title;
        this.published = published;
    }

    public static Story fromElement(Element item, Jid contact) {
        Element entry = item.findChild("entry", "http://www.w3.org/2005/Atom");
        if (entry == null) {
            return null;
        }
        Element published = entry.findChild("published");
        Element link = null;
        for (Element child : entry.getChildren()) {
            if ("link".equals(child.getName()) && "enclosure".equals(child.getAttribute("rel"))) {
                link = child;
                break;
            }
        }
        if (link == null) {
            return null;
        }
        return new Story(
                item.getAttribute("id"),
                contact,
                link.getAttribute("href"),
                link.getAttribute("type"),
                entry.findChildContent("title"),
                parseTimestamp(published)
        );
    }

    public static List<Story> parseFromPubSub(Element pubsub, Jid contact) {
        List<Story> stories = new ArrayList<>();
        if (pubsub == null) {
            return stories;
        }
        Element items = pubsub.findChild("items");
        if (items != null) {
            for (Element item : items.getChildren()) {
                if (item.getName().equals("item")) {
                    Story story = fromElement(item, contact);
                    if (story != null) {
                        stories.add(story);
                    }
                }
            }
        }
        return stories;
    }

    @Override
    public ContentValues getContentValues() {
        ContentValues values = new ContentValues();
        values.put(UUID, uuid);
        values.put(CONTACT, contact.toString());
        values.put(URL, url);
        values.put(TYPE, type);
        values.put(TITLE, title);
        values.put(PUBLISHED, published);
        return values;
    }

    public Jid getContact() {
        return contact;
    }

    public String getUrl() {
        return url;
    }

    public String getType() {
        return type;
    }

    public String getTitle() {
        return title;
    }

    public long getPublished() {
        return published;
    }
}