package im.conversations.android.xmpp.model.reactions;

import java.util.Collection;
import org.junit.Assert;
import org.junit.Test;

/**
 * Reaction content is remote input that gets stored and then drawn as a chip beside the message,
 * with no length or content bound anywhere further down. XEP-0444 says a reaction is an emoji, so
 * anything else is dropped here rather than rendered.
 */
public class ReactionsTest {

    private static Reactions of(final String... contents) {
        final var reactions = Reactions.to("message-id");
        for (final String content : contents) {
            final var reaction = new Reaction();
            reaction.setContent(content);
            reactions.addExtension(reaction);
        }
        return reactions;
    }

    private static Collection<String> parse(final String... contents) {
        return of(contents).getReactions();
    }

    @Test
    public void ordinaryEmojiSurvive() {
        final var reactions = parse("👍", "❤️", "😂");
        Assert.assertEquals(3, reactions.size());
        Assert.assertTrue(reactions.contains("👍"));
    }

    @Test
    public void plainTextIsRejected() {
        Assert.assertTrue(parse("not an emoji").isEmpty());
    }

    /** The case that motivated the filter: arbitrary text rendered where a badge is expected. */
    @Test
    public void impersonationAttemptIsRejected() {
        Assert.assertTrue(parse("Account verified by admin").isEmpty());
    }

    @Test
    public void markupIsRejected() {
        Assert.assertTrue(parse("<b>x</b>").isEmpty());
        Assert.assertTrue(parse("https://example.com/").isEmpty());
    }

    @Test
    public void emptyAndBlankAreRejected() {
        Assert.assertTrue(parse("").isEmpty());
        Assert.assertTrue(parse(" ").isEmpty());
    }

    @Test
    public void veryLongStringIsRejected() {
        Assert.assertTrue(parse("x".repeat(10_000)).isEmpty());
    }

    @Test
    public void emojiWithTrailingTextIsRejected() {
        Assert.assertTrue(parse("👍 and some text").isEmpty());
    }

    @Test
    public void validEmojiSurviveAlongsideRejectedEntries() {
        final var reactions = parse("👍", "arbitrary text", "");
        Assert.assertEquals(1, reactions.size());
        Assert.assertTrue(reactions.contains("👍"));
    }

    @Test
    public void noReactionChildrenYieldsEmpty() {
        Assert.assertTrue(Reactions.to("id").getReactions().isEmpty());
    }
}
