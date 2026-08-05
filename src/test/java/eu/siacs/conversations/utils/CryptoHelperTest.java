package eu.siacs.conversations.utils;

import org.junit.Assert;
import org.junit.Test;

public class CryptoHelperTest {

    private static byte[] classicalIdentityKey() {
        final byte[] ik = new byte[33];
        ik[0] = 0x05; // libsignal's DJB type prefix
        java.util.Arrays.fill(ik, 1, ik.length, (byte) 0x11);
        return ik;
    }

    private static byte[] pqIdentityKey(final byte fill) {
        final byte[] pqIk = new byte[2592]; // ML-DSA-87 verification key
        java.util.Arrays.fill(pqIk, fill);
        return pqIk;
    }

    /**
     * Known-answer test locking the §4.9.3 hybrid fingerprint to the desktop client's
     * {@code hybrid_fingerprint} (see its {@code hybrid_fingerprint_known_answer}) and to the
     * vector documented in the proto-XEP. This is the string a user compares out of band, so a
     * divergence between the two clients would look to them exactly like a failed verification.
     */
    @Test
    public void hybridOmemo2FingerprintKnownAnswer() {
        Assert.assertEquals(
                "6b6ea370b7cbc0078f992487b235ab384a7f272b232e508a2d27da9b42f1def7"
                        + "f2def0daffbdfd91a33065c1e383473a1eacce6e5709833d286c5e399e19c77a",
                CryptoHelper.hybridOmemo2Fingerprint(classicalIdentityKey(), pqIdentityKey((byte) 0x22)));
    }

    /**
     * The ML-KEM-1024 tag predicate, which three call sites depend on: refusing a peer's KEM keys
     * on receive ({@code IqParser}), discarding our own retained keys after an upgrade
     * ({@code DatabaseBackend.purgeNonMlKemKyberPreKeys}), and the last-resort rotation guard.
     *
     * <p>Round-3 CRYSTALS-Kyber-1024 (tag {@code 0x08}) must never be accepted as ML-KEM-1024
     * (tag {@code 0x0A}): identical sizes, different shared secret (proto-XEP §5.1.1).
     */
    @Test
    public void mlKem1024TagIsDistinguishedFromKyber() {
        final byte[] mlKem = new byte[1 + 1568];
        mlKem[0] = 0x0A;
        Assert.assertTrue(CryptoHelper.isMlKem1024PublicKey(mlKem));

        final byte[] kyber = new byte[1 + 1568];
        kyber[0] = 0x08;
        Assert.assertFalse("Round-3 Kyber-1024 must not pass as ML-KEM-1024",
                CryptoHelper.isMlKem1024PublicKey(kyber));

        Assert.assertFalse(CryptoHelper.isMlKem1024PublicKey(new byte[0]));
        Assert.assertFalse(CryptoHelper.isMlKem1024PublicKey(null));
    }

    /**
     * The fingerprint must commit to the post-quantum half, not just the classical key — that
     * binding is the entire reason it exists.
     */
    @Test
    public void hybridOmemo2FingerprintCommitsToPqKey() {
        final byte[] ik = classicalIdentityKey();
        final String one = CryptoHelper.hybridOmemo2Fingerprint(ik, pqIdentityKey((byte) 0x22));
        final String other = CryptoHelper.hybridOmemo2Fingerprint(ik, pqIdentityKey((byte) 0x23));
        Assert.assertEquals("SHA3-512 renders as 128 hex characters", 128, one.length());
        Assert.assertNotEquals(one, other);
    }
}
