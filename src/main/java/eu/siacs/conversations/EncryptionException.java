package eu.siacs.conversations;

public class EncryptionException extends RuntimeException {
    public enum Reason { GENERIC, NEEDS_SESSION_PASSWORD, DB_WRONG_KEY, KEYSTORE_ERROR }

    public final Reason reason;

    public EncryptionException(String message, Throwable cause) {
        this(message, cause, Reason.GENERIC);
    }

    public EncryptionException(String message, Throwable cause, Reason reason) {
        super(message, cause);
        this.reason = reason;
    }
}
