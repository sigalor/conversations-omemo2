package eu.siacs.conversations.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.google.common.io.BaseEncoding;

import eu.siacs.conversations.crypto.axolotl.AxolotlService;
import eu.siacs.conversations.xml.Element;
import eu.siacs.conversations.xml.Namespace;

import im.conversations.android.xmpp.model.stanza.Iq;

import org.junit.Test;

import java.util.Set;

/**
 * Pins the "a parser handed attacker bytes never throws" contract for the OMEMO2 bundle and
 * device-list parsers.
 *
 * <p>These run on the connection thread inside an IQ callback, which has no catch-all above it
 * (the same reasoning as {@link StanzaFuzzTest}). A single unguarded {@code base64decode} on a
 * peer-published bundle therefore used to take the whole process down, so malformed input has to
 * be skipped or reported as null — never raised.
 */
public class IqParserOmemoTest {

    /** 32 zero bytes, base64 — structurally valid where a raw EC key is expected. */
    private static final String VALID_B64_32 =
            BaseEncoding.base64().encode(new byte[32]);

    private static Iq pubsubResult(final Element payload) {
        final Iq iq = new Iq(Iq.Type.RESULT);
        final Element pubsub = iq.addChild("pubsub", Namespace.PUBSUB);
        final Element items = pubsub.addChild("items");
        final Element item = items.addChild("item");
        item.addChild(payload);
        return iq;
    }

    private static Element omemo2Bundle() {
        final Element bundle = new Element("bundle", Namespace.OMEMO2);
        bundle.addChild("spk").setAttribute("id", "1").setContent(VALID_B64_32);
        bundle.addChild("spks").setContent(VALID_B64_32);
        bundle.addChild("ik").setContent(VALID_B64_32);
        return bundle;
    }

    // --- F1a: malformed base64 in a kem-pk `sig` attribute -----------------------------------

    /**
     * The regression test that matters: {@code sig} was decoded outside the loop's try, so this
     * input escaped as an IllegalArgumentException and killed the connection thread.
     */
    @Test
    public void malformedKemPreKeySignatureIsSkippedNotThrown() {
        final Element bundle = omemo2Bundle();
        final Element kemPrekeys = bundle.addChild("kem-prekeys");
        kemPrekeys.addChild("kem-pk")
                .setAttribute("id", "1")
                .setAttribute("sig", "!!! not base64 !!!")
                .setContent(VALID_B64_32);
        // does not throw, and the bad entry is not returned
        assertTrue(IqParser.omemo2KemPreKeys(pubsubResult(bundle)).isEmpty());
    }

    /** A kem-pk whose body is malformed must be skipped the same way. */
    @Test
    public void malformedKemPreKeyBodyIsSkippedNotThrown() {
        final Element bundle = omemo2Bundle();
        final Element kemPrekeys = bundle.addChild("kem-prekeys");
        kemPrekeys.addChild("kem-pk")
                .setAttribute("id", "1")
                .setContent("@@@@");
        assertTrue(IqParser.omemo2KemPreKeys(pubsubResult(bundle)).isEmpty());
    }

    /** A missing sig stays an empty signature — the bundle then fails signature checks, closed. */
    @Test
    public void absentKemPreKeySignatureDoesNotSkipTheEntry() {
        final Element bundle = omemo2Bundle();
        final Element kemPrekeys = bundle.addChild("kem-prekeys");
        kemPrekeys.addChild("kem-pk").setAttribute("id", "7").setContent(VALID_B64_32);
        // The 32-byte body is not a valid ML-KEM-1024 key, so it is refused by the algorithm
        // gate rather than the base64 guard — either way, no throw.
        IqParser.omemo2KemPreKeys(pubsubResult(bundle));
    }

    // --- malformed OMEMO2 bundle fields --------------------------------------------------------

    @Test
    public void malformedOmemo2BundleFieldsYieldNullNotThrow() {
        for (final String field : new String[] {"spk", "spks", "ik"}) {
            final Element bundle = omemo2Bundle();
            // replace the field's content with garbage
            for (final Element child : bundle.getChildren()) {
                if (field.equals(child.getName())) {
                    child.setContent("### bad ###");
                }
            }
            assertNull("expected null for malformed <" + field + ">",
                    IqParser.omemo2Bundle(pubsubResult(bundle)));
        }
    }

    @Test
    public void malformedOmemo2PreKeyIsSkipped() {
        final Element bundle = omemo2Bundle();
        final Element prekeys = bundle.addChild("prekeys");
        prekeys.addChild("pk").setAttribute("id", "1").setContent("&&&&");
        prekeys.addChild("pk").setAttribute("id", "2").setContent(VALID_B64_32);
        final var result = IqParser.omemo2PreKeyPublics(pubsubResult(bundle));
        assertEquals(1, result.size());
        assertNotNull(result.get(2));
    }

    @Test
    public void malformedPqIdentityYieldsNull() {
        final Element bundle = omemo2Bundle();
        bundle.addChild("pq-ik").setContent("not base64");
        bundle.addChild("pq-sig").setContent(VALID_B64_32);
        assertNull(IqParser.omemo2PqIdentity(pubsubResult(bundle)));
    }

    // --- F2: device-list cap -------------------------------------------------------------------

    @Test
    public void omemo2DeviceListIsCapped() {
        final Element devices = new Element("devices", Namespace.OMEMO2);
        for (int i = 1; i <= 500; ++i) {
            devices.addChild("device").setAttribute("id", String.valueOf(i));
        }
        final Element item = new Element("item");
        item.addChild(devices);
        final Set<Integer> ids = IqParser.omemo2DeviceIds(item);
        assertEquals(AxolotlService.MAX_DEVICES_PER_JID, ids.size());
    }

    @Test
    public void legacyDeviceListIsCapped() {
        final Element list = new Element("list");
        for (int i = 1; i <= 500; ++i) {
            list.addChild("device").setAttribute("id", String.valueOf(i));
        }
        final Element item = new Element("item");
        item.addChild(list);
        final Set<Integer> ids = IqParser.deviceIds(item);
        assertEquals(AxolotlService.MAX_DEVICES_PER_JID, ids.size());
    }

    /** A normal-sized list must be unaffected by the cap. */
    @Test
    public void ordinaryDeviceListIsUnchanged() {
        final Element devices = new Element("devices", Namespace.OMEMO2);
        devices.addChild("device").setAttribute("id", "111");
        devices.addChild("device").setAttribute("id", "222");
        devices.addChild("device").setAttribute("id", "0"); // invalid, dropped
        final Element item = new Element("item");
        item.addChild(devices);
        final Set<Integer> ids = IqParser.omemo2DeviceIds(item);
        assertEquals(2, ids.size());
        assertTrue(ids.contains(111) && ids.contains(222));
    }
}
