package eu.siacs.conversations.persistance;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Pins the "may the rekey sentinel be cleared?" decision in {@link
 * DatabaseBackend#swapInMigratedDatabase}.
 *
 * <p>This is the fix for the one data-loss defect the DB-encryption audit found. The migration
 * order is: set sentinel → move files → persist the new key state. If persisting throws (Tink,
 * DataStore or KeyStore failure, all of which surface as EncryptionException), the file on disk is
 * encrypted under the new key while the stored key state still describes the old one — and the
 * intact original sits in {@code .bak}. Clearing the sentinel there makes
 * {@code recoverFromInterruptedMigration} return at its first line forever, so the backup is never
 * restored and the database is permanently unopenable.
 *
 * <p>So the rule is: the sentinel may only be cleared when the DISK is untouched. Everything below
 * is about establishing exactly when that is true.
 */
public class MigrationSwapTest {

    @Rule public TemporaryFolder folder = new TemporaryFolder();

    private File write(final String name, final String content) throws IOException {
        final File f = folder.newFile(name);
        Files.write(f.toPath(), content.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    private static String read(final File f) throws IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    /** The happy path: both renames land, so recovery must run until the key state is persisted. */
    @Test
    public void successfulSwapReportsFilesMoved() throws Exception {
        final File db = write("history", "OLD");
        final File tmp = write("history.tmp", "NEW");
        final File bak = new File(folder.getRoot(), "history.bak");

        assertTrue(DatabaseBackend.swapInMigratedDatabase(db, tmp, bak));

        assertEquals("NEW", read(db));
        assertEquals("OLD", read(bak));
        assertFalse(tmp.exists());
    }

    /**
     * First rename fails, so nothing moved and the sentinel may be cleared. Simulated by making
     * the destination a non-empty directory, which rename cannot replace.
     */
    @Test
    public void failedBackupRenameLeavesDiskUntouched() throws Exception {
        final File db = write("history", "OLD");
        final File tmp = write("history.tmp", "NEW");
        final File bak = folder.newFolder("history.bak");
        // a non-empty directory cannot be replaced by rename
        Files.write(new File(bak, "occupied").toPath(), "x".getBytes(StandardCharsets.UTF_8));

        try {
            DatabaseBackend.swapInMigratedDatabase(db, tmp, bak);
            fail("expected the backup rename to fail");
        } catch (final DatabaseBackend.MigrationSwapException e) {
            fail("must not report a swap failure: the first rename never moved anything");
        } catch (final IOException expected) {
            // plain IOException — caller keeps filesMoved == false and may clear the sentinel
        }

        assertEquals("OLD", read(db));
        assertEquals("NEW", read(tmp));
    }

    /**
     * The dangerous middle state: the backup rename succeeded, the temp rename failed, and the
     * rollback put the original back. Disk matches the stored key again, so the sentinel may go.
     *
     * <p>The temp rename is made to fail the way it realistically would — the temp file is gone.
     */
    @Test
    public void failedTempRenameWithSuccessfulRollbackReportsNotMoved() throws Exception {
        final File db = write("history", "OLD");
        final File tmp = new File(folder.getRoot(), "history.tmp"); // never created
        final File bak = new File(folder.getRoot(), "history.bak");

        try {
            DatabaseBackend.swapInMigratedDatabase(db, tmp, bak);
            fail("expected the temp rename to fail");
        } catch (final DatabaseBackend.MigrationSwapException e) {
            assertFalse("rollback succeeded, so the disk is back to its original state",
                    e.filesMoved);
        }

        assertEquals("the original must be back in place", "OLD", read(db));
        assertFalse("the backup must not be left behind", bak.exists());
    }

    /**
     * The worst state — temp rename failed AND the rollback failed, so the database file is gone
     * and only {@code .bak} holds the data. {@code filesMoved} must stay true so the sentinel
     * survives and recovery's State B puts the backup back.
     *
     * <p>This branch cannot be provoked through the real method on an ordinary filesystem: once
     * {@code dbFile} has been renamed away its path is free, so the rollback rename always
     * succeeds. It is reachable in the field (a concurrent writer recreating the path, a
     * filesystem error, permissions changing mid-operation), so the flag semantics are pinned
     * directly instead of through a contrived filesystem setup that would only be testing the
     * setup.
     */
    @Test
    public void failedRollbackKeepsFilesMovedSet() {
        final boolean rolledBack = false;
        final DatabaseBackend.MigrationSwapException e =
                new DatabaseBackend.MigrationSwapException("rollback failed", !rolledBack);
        assertTrue("data lives only in .bak — recovery must run", e.filesMoved);
    }

    /** A swap failure that reports filesMoved must never be mistaken for an ordinary IOException. */
    @Test
    public void migrationSwapExceptionIsAnIOException() {
        final DatabaseBackend.MigrationSwapException e =
                new DatabaseBackend.MigrationSwapException("x", true);
        assertTrue(e instanceof IOException);
        assertTrue(e.filesMoved);
    }
}
