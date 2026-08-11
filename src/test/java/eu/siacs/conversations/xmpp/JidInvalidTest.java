package eu.siacs.conversations.xmpp;

import org.junit.Assert;
import org.junit.Test;

/**
 * Addresses that fail JID parsing still arrive in stanzas, because the remote side - or a
 * conference service relaying a nick - decides what goes in the attribute. {@link Jid.Invalid} is
 * what the parser layer receives in that case, so the operations the stanza handlers actually
 * perform on it must not throw.
 */
public class JidInvalidTest {

    /** A nick a service may accept but resourceprep rejects: U+0007 is prohibited. */
    private static final String CONTROL_CHAR_NICK = "room@conference.example.com/bad\u0007nick";

    private static Jid parseAsStanzaWould(final String address) {
        return Jid.ofOrInvalid(address, false);
    }

    @Test
    public void controlCharacterInNickIsInvalid() {
        final var jid = parseAsStanzaWould(CONTROL_CHAR_NICK);
        Assert.assertFalse("a control character in the resource must not parse", Jid.Invalid.isValid(jid));
    }

    /**
     * The regression that motivated this: PresenceParser reached {@code getFrom().asBareJid()} on
     * an occupant presence, and an AssertionError there propagates out of the connection thread.
     */
    @Test
    public void asBareJidOnInvalidDoesNotThrow() {
        final var jid = parseAsStanzaWould(CONTROL_CHAR_NICK);
        final var bare = jid.asBareJid();
        Assert.assertNotNull(bare);
        Assert.assertEquals("room@conference.example.com", bare.toString());
    }

    /** When even the part before the resource separator is unusable, degrade rather than throw. */
    @Test
    public void asBareJidFallsBackToItselfWhenBareFormAlsoFails() {
        final var jid = parseAsStanzaWould("@@@/resource");
        Assert.assertFalse(Jid.Invalid.isValid(jid));
        Assert.assertNotNull(jid.asBareJid());
    }

    /**
     * Confirmed-unparsable forms that carry no resource separator at all, so {@code asBareJid()}
     * cannot fall back to splitting. A trailing slash is the realistic one: it is an empty
     * resource, which resourceprep rejects.
     */
    @Test
    public void asBareJidWithoutUsableResourceDoesNotThrow() {
        for (final String address :
                new String[] {"user@", "@example.com", "", "user@example.com/"}) {
            final var jid = parseAsStanzaWould(address);
            Assert.assertFalse("expected " + address + " to be unparsable", Jid.Invalid.isValid(jid));
            Assert.assertNotNull(jid.asBareJid());
        }
    }

    @Test
    public void wellFormedAddressIsUnaffected() {
        final var jid = parseAsStanzaWould("room@conference.example.com/nick");
        Assert.assertTrue(Jid.Invalid.isValid(jid));
        Assert.assertEquals("room@conference.example.com", jid.asBareJid().toString());
    }

    @Test
    public void isValidRejectsInvalidAndAcceptsParsed() {
        Assert.assertFalse(Jid.Invalid.isValid(parseAsStanzaWould(CONTROL_CHAR_NICK)));
        Assert.assertTrue(Jid.Invalid.isValid(Jid.of("alice@example.com")));
    }

    @Test
    public void getNullForInvalidDistinguishesTheTwo() {
        Assert.assertNull(Jid.Invalid.getNullForInvalid(parseAsStanzaWould(CONTROL_CHAR_NICK)));
        Assert.assertNotNull(Jid.Invalid.getNullForInvalid(Jid.of("alice@example.com")));
    }
}
