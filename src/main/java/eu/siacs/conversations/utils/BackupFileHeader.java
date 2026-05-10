package eu.siacs.conversations.utils;

import androidx.annotation.NonNull;
import eu.siacs.conversations.xmpp.Jid;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public class BackupFileHeader {

    public static final int VERSION = 4;

    private final int fileVersion;
    private final String app;
    private final Jid jid;
    private final long timestamp;
    private final byte[] iv;
    private final byte[] salt;

    @NonNull
    @Override
    public String toString() {
        return "BackupFileHeader{"
                + "version="
                + fileVersion
                + ", app='"
                + app
                + '\''
                + ", jid="
                + jid
                + ", timestamp="
                + timestamp
                + ", iv="
                + CryptoHelper.bytesToHex(iv)
                + ", salt="
                + CryptoHelper.bytesToHex(salt)
                + '}';
    }

    public BackupFileHeader(String app, Jid jid, long timestamp, byte[] iv, byte[] salt) {
        this(VERSION, app, jid, timestamp, iv, salt);
    }

    public BackupFileHeader(int fileVersion, String app, Jid jid, long timestamp, byte[] iv, byte[] salt) {
        this.fileVersion = fileVersion;
        this.app = app;
        this.jid = jid;
        this.timestamp = timestamp;
        this.iv = iv;
        this.salt = salt;
    }

    public void write(DataOutputStream dataOutputStream) throws IOException {
        dataOutputStream.writeInt(fileVersion);
        dataOutputStream.writeUTF(app);
        dataOutputStream.writeUTF(jid.asBareJid().toString());
        dataOutputStream.writeLong(timestamp);
        dataOutputStream.write(iv);
        dataOutputStream.write(salt);
    }

    public static BackupFileHeader read(DataInputStream inputStream) throws IOException {
        final int version = inputStream.readInt();
        final String app = inputStream.readUTF();
        final String jid = inputStream.readUTF();
        long timestamp = inputStream.readLong();
        final byte[] iv = new byte[12];
        inputStream.readFully(iv);
        final byte[] salt = new byte[16];
        inputStream.readFully(salt);
        if (version < 2) {
            throw new OutdatedBackupFileVersion();
        }
        if (version > VERSION) {
            throw new IllegalArgumentException(
                    "Backup File version was "
                            + version
                            + " but app only supports version "
                            + VERSION);
        }
        return new BackupFileHeader(version, app, Jid.of(jid), timestamp, iv, salt);
    }

    public int getVersion() {
        return fileVersion;
    }

    public byte[] getSalt() {
        return salt;
    }

    public byte[] getIv() {
        return iv;
    }

    public Jid getJid() {
        return jid;
    }

    public String getApp() {
        return app;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public static class OutdatedBackupFileVersion extends RuntimeException {}
}
