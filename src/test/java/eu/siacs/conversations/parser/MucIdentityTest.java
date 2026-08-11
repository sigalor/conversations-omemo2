package eu.siacs.conversations.parser;

import eu.siacs.conversations.entities.Account;
import eu.siacs.conversations.entities.Conversation;
import eu.siacs.conversations.entities.MucOptions;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;
import eu.siacs.conversations.xmpp.Jid;
import im.conversations.android.xmpp.model.occupant.OccupantId;
import java.util.HashSet;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the rule that decides whether a conference stanza may speak for our own account.
 *
 * <p>The {@code <x xmlns='http://jabber.org/protocol/muc#user'/>} copy travels inside the message,
 * so its {@code <item jid='…'/>} is written by whoever sent that message. Believing a claim of our
 * own address would let another occupant have their message attributed to us: rendered in our
 * outgoing bubble, treated as our own reaction, and accepted as our own read marker. The claim is
 * therefore only honoured when the room independently identifies the sender as us.
 */
public class MucIdentityTest {

    private static final Jid OURS = Jid.of("alice@example.com");
    private static final Jid ROOM = Jid.of("room@conference.example.com");
    private static final Jid OUR_OCCUPANT = Jid.of("room@conference.example.com/alice");
    private static final Jid ATTACKER_OCCUPANT = Jid.of("room@conference.example.com/mallory");

    private Account account;
    private MucOptions mucOptions;

    @Before
    public void setUp() {
        this.account = new Account(OURS, "password");
        final var conversation = new Conversation("room", account, ROOM, Conversation.MODE_MULTI);
        this.mucOptions = conversation.getMucOptions();
    }

    /** The forged element an occupant would attach to their own message. */
    private static Element mucUserClaiming(final Jid realJid) {
        final var x = new Element("x");
        x.setAttribute("xmlns", Namespace.MUC_USER);
        x.addChild("item").setAttribute("jid", realJid.toString());
        return x;
    }

    private static OccupantId occupantId(final String id) {
        final var occupant = new OccupantId();
        occupant.setAttribute("id", id);
        return occupant;
    }

    private void giveSelfAnOccupantId(final String id) {
        mucOptions.getSelf().setOccupantId(id);
    }

    // ---- the attack ----

    /**
     * The core case: another occupant's message claims our address, and nothing about the sender
     * corroborates it. The claim must be dropped in favour of the presence-derived fallback.
     */
    @Test
    public void forgedSelfClaimFromAnotherOccupantIsRejected() {
        final boolean senderIsSelf =
                MessageParser.isSelfInConference(mucOptions, (OccupantId) null, ATTACKER_OCCUPANT);
        Assert.assertFalse("the room does not identify mallory as us", senderIsSelf);

        final Jid resolved =
                MessageParser.getTrueCounterpart(
                        mucUserClaiming(OURS), /* fallback= */ null, account, senderIsSelf);
        Assert.assertNull("a forged claim of our own address must not be honoured", resolved);
    }

    /** Rejecting the claim must fall back to what the room's presence bookkeeping said. */
    @Test
    public void rejectedClaimFallsBackToPresenceDerivedAddress() {
        final Jid presenceDerived = Jid.of("mallory@elsewhere.example");
        final Jid resolved =
                MessageParser.getTrueCounterpart(
                        mucUserClaiming(OURS), presenceDerived, account, false);
        Assert.assertEquals(presenceDerived, resolved);
    }

    /** An occupant-id that is not ours is positive proof the sender is somebody else. */
    @Test
    public void forgedSelfClaimIsRejectedEvenWithAnOccupantId() {
        giveSelfAnOccupantId("our-occupant-id");
        final boolean senderIsSelf =
                MessageParser.isSelfInConference(
                        mucOptions, occupantId("mallory-occupant-id"), ATTACKER_OCCUPANT);
        Assert.assertFalse(senderIsSelf);
        Assert.assertNull(
                MessageParser.getTrueCounterpart(mucUserClaiming(OURS), null, account, senderIsSelf));
    }

    // ---- legitimate traffic must still work ----

    /** Our own message reflected back by the room, identified by nick. */
    @Test
    public void ourOwnReflectionIsRecognisedByNick() {
        Assert.assertTrue(
                MessageParser.isSelfInConference(mucOptions, (OccupantId) null, OUR_OCCUPANT));
    }

    /**
     * Our own message from a second device. Occupant ids are assigned per real user, so both
     * devices share ours even though the second joined under a different nick - which is why the
     * occupant id is preferred over the nick.
     */
    @Test
    public void ourOwnReflectionFromAnotherDeviceIsRecognisedByOccupantId() {
        giveSelfAnOccupantId("our-occupant-id");
        final Jid otherDevice = Jid.of("room@conference.example.com/alice-tablet");
        Assert.assertTrue(
                MessageParser.isSelfInConference(
                        mucOptions, occupantId("our-occupant-id"), otherDevice));
    }

    /** With corroboration, the claim of our own address is accepted. */
    @Test
    public void corroboratedSelfClaimIsAccepted() {
        final Jid resolved =
                MessageParser.getTrueCounterpart(
                        mucUserClaiming(OURS), null, account, /* senderIsSelf= */ true);
        Assert.assertEquals(OURS, resolved);
    }

    /** Claims about third parties are unaffected; only our own address is guarded. */
    @Test
    public void claimAboutAThirdPartyIsStillHonoured() {
        final Jid mallory = Jid.of("mallory@elsewhere.example");
        Assert.assertEquals(
                mallory,
                MessageParser.getTrueCounterpart(mucUserClaiming(mallory), null, account, false));
    }

    @Test
    public void absentItemFallsBackWithoutInspectingTheClaim() {
        final var x = new Element("x");
        x.setAttribute("xmlns", Namespace.MUC_USER);
        final Jid fallback = Jid.of("someone@elsewhere.example");
        Assert.assertEquals(
                fallback, MessageParser.getTrueCounterpart(x, fallback, account, false));
    }

    @Test
    public void absentMucUserElementFallsBack() {
        final Jid fallback = Jid.of("someone@elsewhere.example");
        Assert.assertEquals(
                fallback, MessageParser.getTrueCounterpart(null, fallback, account, false));
    }

    /** A malformed address in the claim must not throw out of the parser. */
    @Test
    public void unparsableClaimDoesNotThrow() {
        final var x = new Element("x");
        x.setAttribute("xmlns", Namespace.MUC_USER);
        x.addChild("item").setAttribute("jid", "@@@not-a-jid");
        MessageParser.getTrueCounterpart(x, null, account, false);
    }

    /** An occupant id on the stanza is ignored while the room has assigned us none. */
    @Test
    public void occupantIdIsIgnoredWhenWeHaveNone() {
        Assert.assertFalse(
                MessageParser.isSelfInConference(
                        mucOptions, occupantId("anything"), ATTACKER_OCCUPANT));
        Assert.assertTrue(
                "without occupant ids the nick decides",
                MessageParser.isSelfInConference(mucOptions, occupantId("anything"), OUR_OCCUPANT));
    }

    @Test
    public void nullMucOptionsIsNotSelf() {
        Assert.assertFalse(
                MessageParser.isSelfInConference((MucOptions) null, (OccupantId) null, OUR_OCCUPANT));
    }
}
