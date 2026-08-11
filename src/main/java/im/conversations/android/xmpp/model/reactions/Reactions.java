package im.conversations.android.xmpp.model.reactions;

import com.google.common.base.Strings;
import com.google.common.collect.Collections2;

import im.conversations.android.annotation.XmlElement;
import im.conversations.android.xmpp.model.Extension;

import java.util.Collection;
import net.fellbaum.jemoji.EmojiManager;

@XmlElement
public class Reactions extends Extension {

    public Reactions() {
        super(Reactions.class);
    }

    /**
     * The emoji carried by this element, with everything else discarded.
     *
     * <p>XEP-0444 says a reaction should be an emoji, and nothing downstream bounds the string:
     * whatever survives here is stored and then drawn as a reaction chip next to the message. A
     * sender that put arbitrary text in a {@code <reaction/>} could therefore render text of their
     * choosing into the chat, in a spot the reader takes for a short trusted badge. Anything that
     * is not an emoji is dropped rather than displayed.
     */
    public Collection<String> getReactions() {
        return Collections2.filter(
                Collections2.transform(
                        getExtensions(Reaction.class),
                        reaction -> reaction == null ? null : reaction.getContent()),
                r -> !Strings.isNullOrEmpty(r) && EmojiManager.isEmoji(r));
    }

    public String getId() {
        return Strings.emptyToNull(this.getAttribute("id"));
    }

    public void setId(String id) {
        this.setAttribute("id", id);
    }

    public static Reactions to(final String id) {
        final var reactions = new Reactions();
        reactions.setId(id);
        return reactions;
    }
}
