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
    public void parsesLegacyAndPqUnderTheirOwnParameters() {
        final List<Fingerprint> fingerprints =
                XmppUri.parseFingerprints(
                        parameters(
                                "omemo-sid-1234", FP_A,
                                "omemo-pq-sid-1234", FP_B));
        assertEquals(2, fingerprints.size());
        assertEquals(FingerprintType.OMEMO, fingerprints.get(0).type);
        assertEquals(FP_A, fingerprints.get(0).fingerprint);
        assertEquals(FingerprintType.OMEMO_PQ, fingerprints.get(1).type);
        assertEquals(FP_B, fingerprints.get(1).fingerprint);
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
                                "omemo-sid-7", FP_A,
                                "omemo-pq-sid-7", FP_B));
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
                XmppUri.parseFingerprints(parameters("omemo-pq-sid-42", FP_A));
        assertEquals(1, fingerprints.size());
        assertEquals(FingerprintType.OMEMO_PQ, fingerprints.get(0).type);
    }

    @Test
    public void fingerprintValuesAreLowercased() {
        final List<Fingerprint> fingerprints =
                XmppUri.parseFingerprints(
                        parameters("omemo-sid-1", FP_A.toUpperCase(java.util.Locale.US)));
        assertEquals(1, fingerprints.size());
        assertEquals(FP_A, fingerprints.get(0).fingerprint);
    }

    @Test
    public void invalidDeviceIdIsIgnored() {
        assertTrue(
                XmppUri.parseFingerprints(parameters("omemo-sid-notanumber", FP_A)).isEmpty());
        assertTrue(XmppUri.parseFingerprints(parameters("omemo-pq-sid-", FP_A)).isEmpty());
        assertTrue(XmppUri.parseFingerprints(parameters("omemo-sid--3", FP_A)).isEmpty());
    }

    @Test
    public void unrelatedParametersAreIgnored() {
        assertTrue(XmppUri.parseFingerprints(parameters("preauth", "token")).isEmpty());
    }

    @Test
    public void bareOmemoParameterIsTreatedAsLegacy() {
        final List<Fingerprint> fingerprints =
                XmppUri.parseFingerprints(parameters("omemo", FP_A));
        assertEquals(1, fingerprints.size());
        assertEquals(FingerprintType.OMEMO, fingerprints.get(0).type);
        assertEquals(0, fingerprints.get(0).deviceId);
    }

    // --- value validation -------------------------------------------------------------------
    // A scanned fingerprint is stored as a VERIFIED identity row, so a value that cannot be a
    // fingerprint must never get that far.

    @Test
    public void nonHexFingerprintIsRejected() {
        final String almost = FP_A.substring(0, 63) + "z";
        assertTrue(XmppUri.parseFingerprints(parameters("omemo-sid-1", almost)).isEmpty());
        assertTrue(
                XmppUri.parseFingerprints(parameters("omemo-pq-sid-1", "not a fingerprint"))
                        .isEmpty());
    }

    @Test
    public void wrongLengthFingerprintIsRejected() {
        assertTrue(XmppUri.parseFingerprints(parameters("omemo-sid-1", "")).isEmpty());
        assertTrue(
                XmppUri.parseFingerprints(parameters("omemo-sid-1", FP_A.substring(0, 63)))
                        .isEmpty());
        assertTrue(XmppUri.parseFingerprints(parameters("omemo-sid-1", FP_A + "aa")).isEmpty());
        // the full 66-char form WITH the leading 05 type byte is not what the URI carries
        assertTrue(XmppUri.parseFingerprints(parameters("omemo-sid-1", "05" + FP_A)).isEmpty());
    }

    /** One bad entry must not discard the good ones alongside it. */
    @Test
    public void invalidEntriesDoNotDropValidOnes() {
        final List<Fingerprint> fingerprints =
                XmppUri.parseFingerprints(
                        parameters(
                                "omemo-sid-1", "garbage",
                                "omemo-sid-2", FP_A));
        assertEquals(1, fingerprints.size());
        assertEquals(FP_A, fingerprints.get(0).fingerprint);
        assertEquals(2, fingerprints.get(0).deviceId);
    }

    /** A single code must not be able to ask us to write unbounded rows into `identities`. */
    @Test
    public void fingerprintCountIsCapped() {
        final Map<String, String> parameters = new LinkedHashMap<>();
        for (int i = 0; i < 500; ++i) {
            parameters.put("omemo-sid-" + i, FP_A);
        }
        assertEquals(64, XmppUri.parseFingerprints(parameters).size());
    }

    // --- building ---------------------------------------------------------------------------

    @Test
    public void buildsUriWithBothParametersAndSemicolonSeparator() {
        final String uri =
                XmppUri.getFingerprintUri(
                        "xmpp:user@example.com",
                        ImmutableList.of(
                                new Fingerprint(FingerprintType.OMEMO, FP_A, 1234),
                                new Fingerprint(FingerprintType.OMEMO_PQ, FP_B, 1234)),
                        ';');
        assertEquals(
                "xmpp:user@example.com?omemo-sid-1234=" + FP_A + ";omemo-pq-sid-1234=" + FP_B,
                uri);
    }

    /** The https invite form uses '&' instead of ';'. */
    @Test
    public void buildsLinkWithAmpersandSeparator() {
        final String uri =
                XmppUri.getFingerprintUri(
                        "https://monocles.chat/i/user@example.com",
                        ImmutableList.of(
                                new Fingerprint(FingerprintType.OMEMO, FP_A, 1),
                                new Fingerprint(FingerprintType.OMEMO_PQ, FP_B, 1)),
                        '&');
        assertEquals(
                "https://monocles.chat/i/user@example.com?omemo-sid-1="
                        + FP_A
                        + "&omemo-pq-sid-1="
                        + FP_B,
                uri);
    }

    @Test
    public void buildRoundTripsThroughTheParser() {
        final List<Fingerprint> original =
                ImmutableList.of(
                        new Fingerprint(FingerprintType.OMEMO, FP_A, 5),
                        new Fingerprint(FingerprintType.OMEMO_PQ, FP_B, 5));
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
