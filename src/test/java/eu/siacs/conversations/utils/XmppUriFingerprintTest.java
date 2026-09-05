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
 * Pins the PQ OMEMO2 fingerprint parameter contract: {@code omemo-pq-sid-<id>} is the only
 * OMEMO fingerprint format this fork emits or parses any more — the legacy XEP-0384 v0.3
 * {@code omemo-sid-<id>}/bare {@code omemo} format was removed along with the rest of the
 * legacy OMEMO1 crypto backend.
 *
 * <p>Also pins the validation of the fingerprint VALUE. A scanned code is untrusted input that
 * ends up written into the identities table as a VERIFIED row, so anything that is not actually
 * a fingerprint has to be dropped here rather than stored verbatim.
 *
 * <p>Only the pure-Java halves are exercised here. Splitting an actual {@code xmpp:} URI goes
 * through {@code android.net.Uri}, which unit tests do not have.
 */
public class XmppUriFingerprintTest {

    /** A well-formed fingerprint: 64 hex chars, i.e. the key without its leading "05" byte. */
    private static final String FP_A =
            "aa11bb22cc33dd44ee55ff6600778899aabbccddeeff00112233445566778899";

    private static final String FP_B =
            "bb22cc33dd44ee55ff6600778899aabbccddeeff001122334455667788990011";

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
    public void parsesPqParameter() {
        final List<Fingerprint> fingerprints =
                XmppUri.parseFingerprints(parameters("omemo-pq-sid-1234", FP_A));
        assertEquals(1, fingerprints.size());
        assertEquals(FingerprintType.OMEMO_PQ, fingerprints.get(0).type);
        assertEquals(FP_A, fingerprints.get(0).fingerprint);
        assertEquals(1234, fingerprints.get(0).deviceId);
    }

    @Test
    public void fingerprintValuesAreLowercased() {
        final List<Fingerprint> fingerprints =
                XmppUri.parseFingerprints(
                        parameters("omemo-pq-sid-1", FP_A.toUpperCase(java.util.Locale.US)));
        assertEquals(1, fingerprints.size());
        assertEquals(FP_A, fingerprints.get(0).fingerprint);
    }

    @Test
    public void invalidDeviceIdIsIgnored() {
        assertTrue(
                XmppUri.parseFingerprints(parameters("omemo-pq-sid-notanumber", FP_A)).isEmpty());
        assertTrue(XmppUri.parseFingerprints(parameters("omemo-pq-sid-", FP_A)).isEmpty());
        assertTrue(XmppUri.parseFingerprints(parameters("omemo-pq-sid--3", FP_A)).isEmpty());
    }

    @Test
    public void unrelatedParametersAreIgnored() {
        assertTrue(XmppUri.parseFingerprints(parameters("preauth", "token")).isEmpty());
    }

    /**
     * The legacy XEP-0384 v0.3 fingerprint format has been removed along with the rest of the
     * legacy OMEMO1 crypto backend: neither the prefixed {@code omemo-sid-<id>} nor the bare
     * {@code omemo} parameter carry any meaning any more, so both are just ignored like any
     * other unrelated parameter.
     */
    @Test
    public void legacyOmemoParametersAreIgnored() {
        assertTrue(XmppUri.parseFingerprints(parameters("omemo-sid-1234", FP_A)).isEmpty());
        assertTrue(XmppUri.parseFingerprints(parameters("omemo", FP_A)).isEmpty());
    }

    // --- value validation -------------------------------------------------------------------
    // A scanned fingerprint is stored as a VERIFIED identity row, so a value that cannot be a
    // fingerprint must never get that far.

    @Test
    public void nonHexFingerprintIsRejected() {
        assertTrue(
                XmppUri.parseFingerprints(parameters("omemo-pq-sid-1", "not a fingerprint"))
                        .isEmpty());
    }

    @Test
    public void wrongLengthFingerprintIsRejected() {
        assertTrue(XmppUri.parseFingerprints(parameters("omemo-pq-sid-1", "")).isEmpty());
        assertTrue(
                XmppUri.parseFingerprints(parameters("omemo-pq-sid-1", FP_A.substring(0, 63)))
                        .isEmpty());
        assertTrue(XmppUri.parseFingerprints(parameters("omemo-pq-sid-1", FP_A + "aa")).isEmpty());
        // the full 66-char form WITH the leading 05 type byte is not what the URI carries
        assertTrue(XmppUri.parseFingerprints(parameters("omemo-pq-sid-1", "05" + FP_A)).isEmpty());
    }

    /** One bad entry must not discard the good ones alongside it. */
    @Test
    public void invalidEntriesDoNotDropValidOnes() {
        final List<Fingerprint> fingerprints =
                XmppUri.parseFingerprints(
                        parameters(
                                "omemo-pq-sid-1", "garbage",
                                "omemo-pq-sid-2", FP_A));
        assertEquals(1, fingerprints.size());
        assertEquals(FP_A, fingerprints.get(0).fingerprint);
        assertEquals(2, fingerprints.get(0).deviceId);
    }

    /** A single code must not be able to ask us to write unbounded rows into `identities`. */
    @Test
    public void fingerprintCountIsCapped() {
        final Map<String, String> parameters = new LinkedHashMap<>();
        for (int i = 0; i < 500; ++i) {
            parameters.put("omemo-pq-sid-" + i, FP_A);
        }
        assertEquals(64, XmppUri.parseFingerprints(parameters).size());
    }

    // --- building ---------------------------------------------------------------------------

    @Test
    public void buildsUriWithSemicolonSeparator() {
        final String uri =
                XmppUri.getFingerprintUri(
                        "xmpp:user@example.com",
                        ImmutableList.of(new Fingerprint(FingerprintType.OMEMO_PQ, FP_A, 1234)),
                        ';');
        assertEquals("xmpp:user@example.com?omemo-pq-sid-1234=" + FP_A, uri);
    }

    /** The https invite form uses '&' instead of ';'. */
    @Test
    public void buildsLinkWithAmpersandSeparator() {
        final String uri =
                XmppUri.getFingerprintUri(
                        "https://monocles.chat/i/user@example.com",
                        ImmutableList.of(
                                new Fingerprint(FingerprintType.OMEMO_PQ, FP_A, 1),
                                new Fingerprint(FingerprintType.OTR, FP_B)),
                        '&');
        assertEquals(
                "https://monocles.chat/i/user@example.com?omemo-pq-sid-1="
                        + FP_A
                        + "&otr-fingerprint="
                        + FP_B,
                uri);
    }

    @Test
    public void buildRoundTripsThroughTheParser() {
        final List<Fingerprint> original =
                ImmutableList.of(new Fingerprint(FingerprintType.OMEMO_PQ, FP_A, 5));
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
