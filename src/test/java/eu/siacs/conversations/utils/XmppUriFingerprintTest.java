package eu.siacs.conversations.utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.google.common.collect.ImmutableList;

import eu.siacs.conversations.utils.XmppUri.Fingerprint;
import eu.siacs.conversations.utils.XmppUri.FingerprintType;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pins the OMEMO fingerprint parameter contract shared with every other XMPP client and with the
 * monocles desktop client: {@code omemo-sid-<id>} is the ecosystem-standard XEP-0384 v0.3 key and
 * {@code omemo-pq-sid-<id>} is the PQ OMEMO2 key. Both travel in ONE QR code, so both directions
 * have to keep working.
 *
 * <p>Only the pure-Java halves are exercised here. Splitting an actual {@code xmpp:} URI goes
 * through {@code android.net.Uri}, which unit tests do not have.
 */
public class XmppUriFingerprintTest {

    private static Map<String, String> parameters(final String... keysAndValues) {
        // LinkedHashMap: parseFingerprints iterates the map, and the prefix-ordering check below
        // is only meaningful with a predictable order.
        final Map<String, String> parameters = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            parameters.put(keysAndValues[i], keysAndValues[i + 1]);
        }
        return parameters;
    }

    @Test
    public void parsesLegacyAndPqUnderTheirOwnParameters() {
        final List<Fingerprint> fingerprints =
                XmppUri.parseFingerprints(
                        parameters(
                                "omemo-sid-1234", "aa11",
                                "omemo-pq-sid-1234", "bb22"));
        assertEquals(2, fingerprints.size());
        assertEquals(FingerprintType.OMEMO, fingerprints.get(0).type);
        assertEquals("aa11", fingerprints.get(0).fingerprint);
        assertEquals(FingerprintType.OMEMO_PQ, fingerprints.get(1).type);
        assertEquals("bb22", fingerprints.get(1).fingerprint);
    }

    /**
     * Both stacks publish under the SAME device id, so the two parameters coexist and must not be
     * collapsed into one entry.
     */
    @Test
    public void sameDeviceIdInBothStacksStaysTwoEntries() {
        final List<Fingerprint> fingerprints =
                XmppUri.parseFingerprints(
                        parameters(
                                "omemo-sid-7", "aa",
                                "omemo-pq-sid-7", "bb"));
        assertEquals(2, fingerprints.size());
        assertTrue(fingerprints.get(0).type != fingerprints.get(1).type);
    }

    /**
     * "omemo-pq-sid-" must be tested before "omemo-sid-". They do not actually share a prefix, but
     * a future tweak to either constant could make them do so, and the PQ key silently landing in
     * the legacy slot would hand peers a fingerprint that never appears on the wire.
     */
    @Test
    public void pqParameterIsNotParsedAsLegacy() {
        final List<Fingerprint> fingerprints =
                XmppUri.parseFingerprints(parameters("omemo-pq-sid-42", "cc"));
        assertEquals(1, fingerprints.size());
        assertEquals(FingerprintType.OMEMO_PQ, fingerprints.get(0).type);
    }

    @Test
    public void fingerprintValuesAreLowercased() {
        final List<Fingerprint> fingerprints =
                XmppUri.parseFingerprints(parameters("omemo-sid-1", "AABBCC"));
        assertEquals("aabbcc", fingerprints.get(0).fingerprint);
    }

    @Test
    public void invalidDeviceIdIsIgnored() {
        assertTrue(
                XmppUri.parseFingerprints(parameters("omemo-sid-notanumber", "aa")).isEmpty());
        assertTrue(
                XmppUri.parseFingerprints(parameters("omemo-pq-sid-", "aa")).isEmpty());
    }

    @Test
    public void unrelatedParametersAreIgnored() {
        assertTrue(XmppUri.parseFingerprints(parameters("preauth", "token")).isEmpty());
    }

    @Test
    public void bareOmemoParameterIsTreatedAsLegacy() {
        final List<Fingerprint> fingerprints =
                XmppUri.parseFingerprints(parameters("omemo", "dd"));
        assertEquals(1, fingerprints.size());
        assertEquals(FingerprintType.OMEMO, fingerprints.get(0).type);
        assertEquals(0, fingerprints.get(0).deviceId);
    }

    @Test
    public void buildsUriWithBothParametersAndSemicolonSeparator() {
        final String uri =
                XmppUri.getFingerprintUri(
                        "xmpp:user@example.com",
                        ImmutableList.of(
                                new Fingerprint(FingerprintType.OMEMO, "aa11", 1234),
                                new Fingerprint(FingerprintType.OMEMO_PQ, "bb22", 1234)),
                        ';');
        assertEquals(
                "xmpp:user@example.com?omemo-sid-1234=aa11;omemo-pq-sid-1234=bb22", uri);
    }

    /** The https invite form uses '&' instead of ';'. */
    @Test
    public void buildsLinkWithAmpersandSeparator() {
        final String uri =
                XmppUri.getFingerprintUri(
                        "https://monocles.chat/i/user@example.com",
                        ImmutableList.of(
                                new Fingerprint(FingerprintType.OMEMO, "aa11", 1),
                                new Fingerprint(FingerprintType.OMEMO_PQ, "bb22", 1)),
                        '&');
        assertEquals(
                "https://monocles.chat/i/user@example.com?omemo-sid-1=aa11&omemo-pq-sid-1=bb22",
                uri);
    }

    @Test
    public void buildRoundTripsThroughTheParser() {
        final List<Fingerprint> original =
                ImmutableList.of(
                        new Fingerprint(FingerprintType.OMEMO, "aa11", 5),
                        new Fingerprint(FingerprintType.OMEMO_PQ, "bb22", 5));
        final String uri = XmppUri.getFingerprintUri("xmpp:user@example.com", original, ';');
        final String query = uri.substring(uri.indexOf('?') + 1);
        final Map<String, String> parameters = new LinkedHashMap<>();
        for (final String pair : query.split(";")) {
            final int eq = pair.indexOf('=');
            parameters.put(pair.substring(0, eq), pair.substring(eq + 1));
        }
        final List<Fingerprint> parsed = XmppUri.parseFingerprints(parameters);
        assertEquals(original.size(), parsed.size());
        for (int i = 0; i < original.size(); ++i) {
            assertEquals(original.get(i).type, parsed.get(i).type);
            assertEquals(original.get(i).fingerprint, parsed.get(i).fingerprint);
            assertEquals(original.get(i).deviceId, parsed.get(i).deviceId);
        }
    }
}
