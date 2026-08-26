package eu.siacs.conversations.crypto.axolotl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Assert;
import org.junit.Test;

/**
 * The bound on how many wrapped keys one stanza may make us try.
 *
 * <p>{@link XmppAxolotlSession#processReceiving} spends a full trial ratchet decryption on every
 * candidate it is handed, and a message header is attacker-supplied — nothing in the format stops
 * a sender from stuffing it with keys for our device. The bound is what keeps one stanza from
 * costing as much CPU as the sender cares to ask for.
 *
 * <p>Exercised through {@link XmppAxolotlSession#capCandidates} rather than {@code
 * processReceiving}: the latter needs a live {@code SessionCipher} and {@code SQLiteAxolotlStore},
 * neither of which stands up under plain JUnit, and this project carries no mocking framework.
 * That leaves the call itself — one line in {@code processReceiving} — outside this test's reach.
 */
public class XmppAxolotlSessionCandidateCapTest {

    private static List<XmppAxolotlSession.AxolotlKey> keys(final int count) {
        final List<XmppAxolotlSession.AxolotlKey> keys = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            // The device id doubles as a position marker, so order can be asserted below.
            keys.add(new XmppAxolotlSession.AxolotlKey(i, new byte[] {(byte) i}, false));
        }
        return keys;
    }

    /**
     * The real cases are untouched: one wrap, and the known benign two — a sender that rebuilt
     * the session mid-send and attached both the old and the new wrap.
     */
    @Test
    public void legitimateCandidateListsAreUnchanged() {
        for (int n = 0; n <= 2; n++) {
            final List<XmppAxolotlSession.AxolotlKey> offered = keys(n);
            Assert.assertSame(
                    "a list within the bound must be handed back as-is",
                    offered,
                    XmppAxolotlSession.capCandidates(offered));
        }
    }

    /** Exactly at the bound is not over it — the boundary an off-by-one would get wrong. */
    @Test
    public void exactlyTheCapIsNotTruncated() {
        final List<XmppAxolotlSession.AxolotlKey> offered =
                keys(XmppAxolotlSession.MAX_KEY_CANDIDATES);
        Assert.assertSame(offered, XmppAxolotlSession.capCandidates(offered));
    }

    /** Past the bound, only the first {@code MAX_KEY_CANDIDATES} are ever tried. */
    @Test
    public void oversizedCandidateListIsCapped() {
        final List<XmppAxolotlSession.AxolotlKey> capped =
                XmppAxolotlSession.capCandidates(
                        keys(XmppAxolotlSession.MAX_KEY_CANDIDATES * 500));
        Assert.assertEquals(XmppAxolotlSession.MAX_KEY_CANDIDATES, capped.size());
        // The kept ones are the FIRST ones, in order: a sender's real wrap comes first, so
        // keeping an arbitrary window could drop the one key that would actually have opened.
        for (int i = 0; i < capped.size(); i++) {
            Assert.assertEquals(i, capped.get(i).deviceId);
        }
    }

    /**
     * The caller's list must survive intact. {@code processReceiving} takes a list it does not
     * own, so capping by mutating it would corrupt state the caller still holds.
     */
    @Test
    public void theCallersListIsNotMutated() {
        final List<XmppAxolotlSession.AxolotlKey> offered =
                keys(XmppAxolotlSession.MAX_KEY_CANDIDATES + 5);
        final int before = offered.size();
        XmppAxolotlSession.capCandidates(offered);
        Assert.assertEquals(before, offered.size());
    }

    /** An empty header yields an empty list rather than throwing; the caller decides. */
    @Test
    public void emptyListIsHandledWithoutThrowing() {
        Assert.assertTrue(
                XmppAxolotlSession.capCandidates(Collections.emptyList()).isEmpty());
    }
}
