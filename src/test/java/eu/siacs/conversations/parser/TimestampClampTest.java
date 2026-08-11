package eu.siacs.conversations.parser;

import eu.siacs.conversations.xml.Element;
import java.text.ParseException;
import org.junit.Assert;
import org.junit.Test;

/**
 * A {@code <delay/>} is written by whoever sent or relayed the stanza, so its stamp is remote
 * input. A message claiming to have been sent in the future becomes the newest {@code timeSent}
 * in its conversation, which is what {@code Conversation.getLastMessageTransmitted()} hands to MAM
 * as the catch-up anchor - so an unclamped stamp leaves the real backlog permanently unfetched.
 */
public class TimestampClampTest {

    private static final long MINUTE = 60_000L;

    private static Element withDelay(final String stamp) {
        final var message = new Element("message");
        message.addChild("delay", "urn:xmpp:delay").setAttribute("stamp", stamp);
        return message;
    }

    @Test
    public void futureDelayIsClampedToNow() {
        final long before = System.currentTimeMillis();
        final long parsed = AbstractParser.parseTimestamp(withDelay("2099-01-01T00:00:00Z"), 0L);
        final long after = System.currentTimeMillis();
        Assert.assertTrue(
                "a stamp in the year 2099 must not survive as the message time",
                parsed >= before && parsed <= after);
    }

    @Test
    public void slightlyFutureDelayIsAlsoClamped() {
        final long now = System.currentTimeMillis();
        final long parsed = AbstractParser.parseTimestamp(withDelay(iso(now + 60 * MINUTE)), 0L);
        Assert.assertTrue("an hour into the future is still the future", parsed <= System.currentTimeMillis());
    }

    @Test
    public void pastDelayIsPreservedExactly() {
        // MAM catch-up depends on old stamps surviving untouched.
        final long parsed = AbstractParser.parseTimestamp(withDelay("2020-05-11T10:00:00Z"), 0L);
        Assert.assertEquals(1589191200000L, parsed);
    }

    @Test
    public void defaultIsUsedWhenNoDelayPresent() {
        Assert.assertEquals(4242L, (long) AbstractParser.parseTimestamp(new Element("message"), 4242L));
    }

    @Test
    public void earliestDelayWins() {
        final var message = new Element("message");
        message.addChild("delay", "urn:xmpp:delay").setAttribute("stamp", "2021-01-01T00:00:00Z");
        message.addChild("delay", "urn:xmpp:delay").setAttribute("stamp", "2020-01-01T00:00:00Z");
        Assert.assertEquals(1577836800000L, (long) AbstractParser.parseTimestamp(message, 0L));
    }

    @Test
    public void unparseableStampFallsBackToDefault() {
        Assert.assertEquals(
                7L, (long) AbstractParser.parseTimestamp(withDelay("not-a-timestamp"), 7L));
    }

    /**
     * The clamp must stay out of the String overload. That one also parses OMEMO2 SCE {@code
     * <time>} affixes - where XmppOmemo2Message rejects a future stamp itself, and would stop
     * being able to if the value arrived pre-clamped - and ephemeral expiry stamps, which are
     * future by definition.
     */
    @Test
    public void stringOverloadKeepsFutureValues() throws ParseException {
        final long parsed = AbstractParser.parseTimestamp("2099-01-01T00:00:00Z");
        Assert.assertTrue(
                "expiry stamps and SCE time affixes must keep their exact value",
                parsed > System.currentTimeMillis());
    }

    private static String iso(final long millis) {
        final var format =
                new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return format.format(new java.util.Date(millis));
    }
}
