package eu.siacs.conversations;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import eu.siacs.conversations.entities.Message;

import org.junit.Test;

/**
 * Pins {@link Config#isSendableEncryption(int)} -- the fail-closed gate that {@code
 * XmppConnectionService.sendMessage()} uses to refuse to actually transmit (or upload an
 * attachment for) a message whose {@code encryption} column carries a pre-fork value.
 *
 * <p>This is the pure decision half of a fix that can't otherwise be unit-tested end to end:
 * the real send path needs a live {@code Account}/{@code XmppConnection}, so the switch-statement
 * gate itself is only exercisable through a careful manual code-trace (see
 * task-11b-report.md). What plain JUnit *can* pin is that the underlying decision function
 * agrees with {@code Config.ENCRYPTION_MASK} for every encryption value {@code Message} defines,
 * so a future change to the mask (or to which cases the gate switches on) cannot silently drift
 * out of sync with each other.
 */
public class ConfigTest {

    /** This fork's {@code ENCRYPTION_MASK} is OMEMO-only -- no plaintext, OTR, or PGP, ever. */
    @Test
    public void legacyEncryptionTypesAreNotSendable() {
        assertFalse(Config.isSendableEncryption(Message.ENCRYPTION_NONE));
        assertFalse(Config.isSendableEncryption(Message.ENCRYPTION_OTR));
        assertFalse(Config.isSendableEncryption(Message.ENCRYPTION_PGP));
        assertFalse(Config.isSendableEncryption(Message.ENCRYPTION_DECRYPTED));
    }

    @Test
    public void onlyOmemo2IsSendableAmongOmemoTypes() {
        assertFalse(Config.isSendableEncryption(Message.ENCRYPTION_AXOLOTL));
        assertTrue(Config.isSendableEncryption(Message.ENCRYPTION_AXOLOTL_OMEMO2));
    }

    /**
     * Consistency check against the underlying support flags directly, rather than just
     * hardcoding today's mask -- catches drift if {@code ENCRYPTION_MASK} is ever changed
     * without updating {@code isSendableEncryption}'s switch to match.
     */
    @Test
    public void agreesWithUnderlyingSupportFlags() {
        assertEqualsBool(Config.supportUnencrypted(), Config.isSendableEncryption(Message.ENCRYPTION_NONE));
        assertEqualsBool(Config.supportOtr(), Config.isSendableEncryption(Message.ENCRYPTION_OTR));
        assertEqualsBool(Config.supportOpenPgp(), Config.isSendableEncryption(Message.ENCRYPTION_PGP));
        assertEqualsBool(Config.supportOpenPgp(), Config.isSendableEncryption(Message.ENCRYPTION_DECRYPTED));
    }

    private static void assertEqualsBool(final boolean expected, final boolean actual) {
        org.junit.Assert.assertEquals(expected, actual);
    }
}
