package uz.jtscorp.filesync.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uz.jtscorp.filesync.Texts;
import uz.jtscorp.filesync.config.SyncMode;
import uz.jtscorp.filesync.sync.PlanInterpreter.Interpretation;
import uz.jtscorp.filesync.sync.PlanInterpreter.Rename;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.SyncAction.Direction;
import uz.jtscorp.filesync.sync.SyncAction.Origin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class PlanInterpreterTest {

    @TempDir
    Path tmp;

    private Path lap() throws IOException {
        return Files.createDirectories(tmp.resolve("lap"));
    }

    private Path hdd() throws IOException {
        return Files.createDirectories(tmp.resolve("hdd"));
    }

    private static void file(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private Optional<Manifest> manifest(Map<String, Manifest.Entry> entries) throws IOException {
        return Optional.of(new Manifest("p", lap().toString(), Manifest.localHost(),
                Instant.parse("2026-10-16T12:38:38Z"), entries));
    }

    /** A file record whose time matches nothing on disk: for rows rule 2 must not touch anyway. */
    private static Manifest.Entry f(long size) {
        return new Manifest.Entry('f', size, 0);
    }

    /** The record a manifest written now would hold for the backup copy at {@code path}. */
    private Manifest.Entry onHdd(String path) throws IOException {
        Path p = hdd().resolve(path);
        return new Manifest.Entry('f', Files.size(p),
                Files.getLastModifiedTime(p, LinkOption.NOFOLLOW_LINKS).toMillis());
    }

    private static final Manifest.Entry DIR = new Manifest.Entry('d', 0, 0);

    private Interpretation run(List<SyncAction> parsed, SyncMode mode, Optional<Manifest> manifest)
            throws IOException {
        return PlanInterpreter.interpret(parsed, mode, manifest, lap(), hdd(), List.of());
    }

    private static SyncAction newOnBackup(String path) {
        return new SyncAction(path, Direction.HDD_TO_LAPTOP, ActionType.NEW);
    }

    private static SyncAction backupDelete(String path, Origin origin) {
        return new SyncAction(path, Direction.LAPTOP_TO_HDD, ActionType.DELETED, origin);
    }

    @Test
    void pruneTurnsBackupOnlyCreationsIntoBackupDeletes() throws IOException {
        SyncAction toBackup = new SyncAction("a.txt", Direction.LAPTOP_TO_HDD, ActionType.NEW);
        Interpretation result = run(List.of(newOnBackup("old.txt"), toBackup), SyncMode.TWO_WAY_PRUNE,
                Optional.empty());

        assertEquals(List.of(backupDelete("old.txt", Origin.MODE), toBackup), result.actions());
        assertEquals(Map.of("old.txt", Texts.t("note.mode.prune")), result.notes());
    }

    @Test
    void pruneLeavesBackupEditsAlone() throws IOException {
        List<SyncAction> parsed = List.of(new SyncAction("e.txt", Direction.HDD_TO_LAPTOP, ActionType.CHANGED));
        Interpretation result = run(parsed, SyncMode.TWO_WAY_PRUNE, Optional.empty());

        assertEquals(parsed, result.actions());
        assertEquals(Map.of(), result.notes());
    }

    @Test
    void manifestTurnsStaleBackupFileIntoBackupDelete() throws IOException {
        // The 16-10 case: an old package left on the backup, deleted on the main side long ago.
        file(hdd().resolve("uz/ishonch/Main.java"), "class Main {}");
        file(hdd().resolve("uz/ishonch/Edited.java"), "class Edited { int x; }");
        Optional<Manifest> m = manifest(Map.of("uz", DIR, "uz/ishonch", DIR,
                "uz/ishonch/Main.java", onHdd("uz/ishonch/Main.java"), "uz/ishonch/Edited.java", f(14)));

        Interpretation result = run(List.of(newOnBackup("uz/ishonch/Main.java"),
                newOnBackup("uz/ishonch/Edited.java")), SyncMode.TWO_WAY, m);

        // Edited.java changed on the backup since the manifest: it is not the copy the main
        // side deleted, so Unison's row stands.
        assertEquals(List.of(backupDelete("uz/ishonch/Main.java", Origin.MANIFEST),
                newOnBackup("uz/ishonch/Edited.java")), result.actions());
        assertEquals(Map.of("uz/ishonch/Main.java", Texts.t("note.manifest.deletedOnMain")), result.notes());
    }

    @Test
    void sameSizeEditOnBackupAfterTheManifestIsNotReinterpreted() throws IOException {
        file(hdd().resolve("old.txt"), "abc");
        file(hdd().resolve("d/in.txt"), "in");
        Optional<Manifest> m = manifest(Map.of("old.txt", onHdd("old.txt"), "d", DIR, "d/in.txt", onHdd("d/in.txt")));
        // Edited on the backup since the manifest, same size: only the time tells.
        for (String edited : List.of("old.txt", "d/in.txt")) {
            Path p = hdd().resolve(edited);
            Files.setLastModifiedTime(p, FileTime.fromMillis(Files.getLastModifiedTime(p).toMillis() + 60_000));
        }

        List<SyncAction> parsed = List.of(newOnBackup("old.txt"), newOnBackup("d"));
        Interpretation result = run(parsed, SyncMode.TWO_WAY, m);

        assertEquals(parsed, result.actions());
        assertEquals(Map.of(), result.notes());
    }

    @Test
    void manifestTurnsStaleFolderIntoOneDeleteWhenAllInsideMatch() throws IOException {
        file(hdd().resolve("uz/ishonch/Main.java"), "class Main {}");
        file(hdd().resolve("uz/ishonch/util/Io.java"), "io");
        Optional<Manifest> m = manifest(Map.of("uz", DIR, "uz/ishonch", DIR, "uz/ishonch/Main.java", onHdd("uz/ishonch/Main.java"),
                "uz/ishonch/util", DIR, "uz/ishonch/util/Io.java", onHdd("uz/ishonch/util/Io.java")));

        Interpretation result = run(List.of(newOnBackup("uz/ishonch")), SyncMode.TWO_WAY, m);

        assertEquals(List.of(backupDelete("uz/ishonch", Origin.MANIFEST)), result.actions());
        assertEquals(Map.of("uz/ishonch", Texts.t("note.manifest.deletedOnMain")), result.notes());
    }

    @Test
    void folderWithAFileAddedOnBackupIsNotReinterpreted() throws IOException {
        file(hdd().resolve("uz/ishonch/Main.java"), "class Main {}");
        file(hdd().resolve("uz/ishonch/Added.java"), "new");
        Optional<Manifest> m = manifest(Map.of("uz/ishonch", DIR, "uz/ishonch/Main.java", onHdd("uz/ishonch/Main.java")));

        List<SyncAction> parsed = List.of(newOnBackup("uz/ishonch"));
        Interpretation result = run(parsed, SyncMode.TWO_WAY, m);

        assertEquals(parsed, result.actions());
        assertEquals(Map.of("uz/ishonch", Texts.t("note.manifest.addedInside")), result.notes());
    }

    @Test
    void folderHoldingAnIgnoredFileIsNotReinterpreted() throws IOException {
        // Verified on 2.53.3: "-force <lap> -path proj -ignore 'Name *.iso'" deletes proj/x.iso too.
        file(hdd().resolve("proj/a.txt"), "a");
        file(hdd().resolve("proj/x.iso"), "iso");
        Optional<Manifest> m = manifest(Map.of("proj", DIR, "proj/a.txt", onHdd("proj/a.txt")));

        List<SyncAction> parsed = List.of(newOnBackup("proj"));
        Interpretation result = PlanInterpreter.interpret(parsed, SyncMode.TWO_WAY, m, lap(), hdd(),
                List.of("Name *.iso"));

        assertEquals(parsed, result.actions());
        assertEquals(Map.of("proj", Texts.t("note.manifest.addedInside")), result.notes());
    }

    @Test
    void folderWithASizeMismatchInsideIsNotReinterpretedAndGetsNoNote() throws IOException {
        file(hdd().resolve("proj/a.txt"), "abc");
        Optional<Manifest> m = manifest(Map.of("proj", DIR, "proj/a.txt", f(1)));

        List<SyncAction> parsed = List.of(newOnBackup("proj"));
        Interpretation result = run(parsed, SyncMode.TWO_WAY, m);

        assertEquals(parsed, result.actions());
        assertEquals(Map.of(), result.notes());
    }

    @Test
    void rowIsNotReinterpretedWhileMainHasSomethingThere() throws IOException {
        file(hdd().resolve("old.txt"), "abc");
        file(lap().resolve("old.txt"), "xyz");
        Optional<Manifest> m = manifest(Map.of("old.txt", onHdd("old.txt")));

        List<SyncAction> parsed = List.of(newOnBackup("old.txt"));
        Interpretation result = run(parsed, SyncMode.TWO_WAY, m);

        assertEquals(parsed, result.actions());
        assertEquals(Map.of(), result.notes());
    }

    @Test
    void twoNotesOnOnePathAreJoined() throws IOException {
        file(hdd().resolve("t/inside.txt"), "i");
        Optional<Manifest> m = manifest(Map.of("t", f(5)));

        Interpretation result = run(List.of(newOnBackup("t")), SyncMode.TWO_WAY_PRUNE, m);

        assertEquals(Map.of("t", Texts.t("note.mode.prune") + " · "
                + Texts.t("note.manifest.typeChanged", Texts.t("kind.file"), Texts.t("kind.dir")))
                , result.notes());
    }

    @Test
    void interpretationCannotBeModified() throws IOException {
        Interpretation result = run(new ArrayList<>(List.of(newOnBackup("x"))), SyncMode.TWO_WAY_PRUNE,
                Optional.empty());
        assertThrows(UnsupportedOperationException.class, () -> result.actions().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.flags().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.renames().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.notes().clear());
    }

    @Test
    void folderWithAnUnreadablePartIsNotReinterpreted() throws IOException {
        assumeFalse("root".equals(System.getProperty("user.name")));
        file(hdd().resolve("d/a.txt"), "a");
        Path locked = Files.createDirectories(hdd().resolve("d/locked"));
        Optional<Manifest> m = manifest(Map.of("d", DIR, "d/a.txt", onHdd("d/a.txt"), "d/locked", DIR));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            List<SyncAction> parsed = List.of(newOnBackup("d"));
            Interpretation result = run(parsed, SyncMode.TWO_WAY, m);

            assertEquals(parsed, result.actions());
            assertEquals(Map.of(), result.notes());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void manifestFromAnotherHostOrRootIsIgnored() throws IOException {
        file(hdd().resolve("old.txt"), "abc");
        Instant when = Instant.parse("2026-10-16T12:38:38Z");
        Map<String, Manifest.Entry> entries = Map.of("old.txt", onHdd("old.txt"), "lost.txt", f(1));
        file(lap().resolve("lost.txt"), "x");
        List<SyncAction> parsed = List.of(newOnBackup("old.txt"));

        for (Manifest other : List.of(
                new Manifest("p", lap().toString(), "another-host", when, entries),
                new Manifest("p", "/somewhere/else", Manifest.localHost(), when, entries))) {
            Interpretation result = run(parsed, SyncMode.TWO_WAY, Optional.of(other));
            assertEquals(parsed, result.actions());
            assertEquals(List.of(), result.flags());
            assertEquals(Map.of(), result.notes());
        }
    }

    @Test
    void lostOnBackupIsFlaggedButNeverTurnedIntoALaptopDelete() throws IOException {
        file(lap().resolve("x.txt"), "x");
        List<SyncAction> parsed = List.of(new SyncAction("x.txt", Direction.HDD_TO_LAPTOP, ActionType.DELETED));

        Interpretation result = run(parsed, SyncMode.TWO_WAY, manifest(Map.of("x.txt", f(1))));

        assertEquals(parsed, result.actions());
        assertEquals(List.of(RiskFlag.forFile("x.txt", Texts.t("risk.lostOnBackup"))), result.flags());
    }

    @Test
    void lostFolderIsOneFlag() throws IOException {
        file(lap().resolve("d/a.txt"), "a");
        file(lap().resolve("d/sub/b.txt"), "b");
        Optional<Manifest> m = manifest(Map.of("d", DIR, "d/a.txt", f(1), "d/sub", DIR, "d/sub/b.txt", f(1)));

        Interpretation result = run(List.of(), SyncMode.TWO_WAY, m);

        assertEquals(List.of(RiskFlag.forFile("d", Texts.t("risk.lostOnBackup"))), result.flags());
    }

    @Test
    void moreThanFiftyLostPlacesAddOneBatchFlag() throws IOException {
        Map<String, Manifest.Entry> entries = new HashMap<>();
        for (int i = 0; i < 51; i++) {
            file(lap().resolve("f" + i), "x");
            entries.put("f" + i, f(1));
        }
        List<RiskFlag> flags = run(List.of(), SyncMode.MIRROR_TO_BACKUP, manifest(entries)).flags();
        assertEquals(51, flags.stream().filter(fl -> fl.scope() == RiskFlag.Scope.FILE).count());
        assertEquals(List.of(RiskFlag.forBatch(Texts.t("risk.lostOnBackupMass", 51))),
                flags.stream().filter(fl -> fl.scope() == RiskFlag.Scope.BATCH).toList());

        entries.remove("f0");
        flags = run(List.of(), SyncMode.MIRROR_TO_BACKUP, manifest(entries)).flags();
        assertTrue(flags.stream().noneMatch(fl -> fl.scope() == RiskFlag.Scope.BATCH));
    }

    @Test
    void ignoredOrUnreadablePlacesAreNotLost() throws IOException {
        assumeFalse("root".equals(System.getProperty("user.name")));
        file(lap().resolve("node_modules/x.js"), "x");
        file(lap().resolve("locked/y.txt"), "y");
        Path locked = Files.createDirectories(hdd().resolve("locked"));
        Optional<Manifest> m = manifest(Map.of("node_modules", DIR, "node_modules/x.js", f(1),
                "locked", DIR, "locked/y.txt", f(1)));
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            Interpretation result = PlanInterpreter.interpret(List.of(), SyncMode.TWO_WAY, m, lap(), hdd(),
                    List.of("Name node_modules"));
            assertEquals(List.of(), result.flags());
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void detectsFileRenameByBytes() throws IOException {
        file(hdd().resolve("old-name.txt"), "hello");
        file(lap().resolve("new-name.txt"), "hello");
        List<SyncAction> parsed = List.of(
                new SyncAction("old-name.txt", Direction.LAPTOP_TO_HDD, ActionType.DELETED),
                new SyncAction("new-name.txt", Direction.LAPTOP_TO_HDD, ActionType.NEW));

        Interpretation result = run(parsed, SyncMode.TWO_WAY, Optional.empty());

        assertEquals(parsed, result.actions());
        assertEquals(List.of(new Rename("old-name.txt", "new-name.txt", Direction.LAPTOP_TO_HDD)),
                result.renames());
    }

    @Test
    void detectsFolderRenameByContent() throws IOException {
        file(lap().resolve("old/a.txt"), "a");
        file(lap().resolve("old/sub/b.txt"), "bb");
        file(hdd().resolve("new/a.txt"), "a");
        file(hdd().resolve("new/sub/b.txt"), "bb");
        // The copies were written at different moments; a rename is about bytes, not times.
        Files.setLastModifiedTime(hdd().resolve("new/a.txt"), FileTime.fromMillis(1_000_000_000_000L));
        List<SyncAction> parsed = List.of(
                new SyncAction("old", Direction.HDD_TO_LAPTOP, ActionType.DELETED),
                new SyncAction("new", Direction.HDD_TO_LAPTOP, ActionType.NEW));

        assertEquals(List.of(new Rename("old", "new", Direction.HDD_TO_LAPTOP)),
                run(parsed, SyncMode.TWO_WAY, Optional.empty()).renames());
    }

    @Test
    void sameSizeDifferentBytesIsNotARename() throws IOException {
        file(hdd().resolve("old-name.txt"), "hello");
        file(lap().resolve("new-name.txt"), "world");
        List<SyncAction> parsed = List.of(
                new SyncAction("old-name.txt", Direction.LAPTOP_TO_HDD, ActionType.DELETED),
                new SyncAction("new-name.txt", Direction.LAPTOP_TO_HDD, ActionType.NEW));

        assertEquals(List.of(), run(parsed, SyncMode.TWO_WAY, Optional.empty()).renames());
    }

    @Test
    void emptyFilesNeverPairAsARename() throws IOException {
        file(hdd().resolve("old-name.txt"), "");
        file(lap().resolve("new-name.txt"), "");
        List<SyncAction> parsed = List.of(
                new SyncAction("old-name.txt", Direction.LAPTOP_TO_HDD, ActionType.DELETED),
                new SyncAction("new-name.txt", Direction.LAPTOP_TO_HDD, ActionType.NEW));

        assertEquals(List.of(), run(parsed, SyncMode.TWO_WAY, Optional.empty()).renames());
    }

    @Test
    void aPathIsNeverInTwoRenames() throws IOException {
        // One delete, two identical creates; then two identical deletes, one create.
        file(hdd().resolve("a1.txt"), "same");
        file(lap().resolve("b1.txt"), "same");
        file(lap().resolve("b2.txt"), "same");
        file(lap().resolve("c1.txt"), "other");
        file(lap().resolve("c2.txt"), "other");
        file(hdd().resolve("d1.txt"), "other");
        List<SyncAction> parsed = List.of(
                new SyncAction("a1.txt", Direction.LAPTOP_TO_HDD, ActionType.DELETED),
                new SyncAction("b1.txt", Direction.LAPTOP_TO_HDD, ActionType.NEW),
                new SyncAction("b2.txt", Direction.LAPTOP_TO_HDD, ActionType.NEW),
                new SyncAction("c1.txt", Direction.HDD_TO_LAPTOP, ActionType.DELETED),
                new SyncAction("c2.txt", Direction.HDD_TO_LAPTOP, ActionType.DELETED),
                new SyncAction("d1.txt", Direction.HDD_TO_LAPTOP, ActionType.NEW));

        assertEquals(List.of(new Rename("a1.txt", "b1.txt", Direction.LAPTOP_TO_HDD),
                        new Rename("c1.txt", "d1.txt", Direction.HDD_TO_LAPTOP)),
                run(parsed, SyncMode.TWO_WAY, Optional.empty()).renames());
    }

    @Test
    void forcedModesPassThroughUnchanged() throws IOException {
        file(hdd().resolve("old.txt"), "abc");
        Optional<Manifest> m = manifest(Map.of("old.txt", onHdd("old.txt")));
        List<SyncAction> parsed = new ArrayList<>(List.of(newOnBackup("old.txt"),
                new SyncAction("b.txt", Direction.LAPTOP_TO_HDD, ActionType.DELETED, Origin.MODE)));

        for (SyncMode mode : List.of(SyncMode.MIRROR_TO_BACKUP, SyncMode.RESTORE_FROM_BACKUP)) {
            Interpretation result = run(parsed, mode, m);
            assertEquals(parsed, result.actions());
            assertEquals(Map.of(), result.notes());
        }
    }

    @Test
    void typeChangeGetsANote() throws IOException {
        file(hdd().resolve("t/inside.txt"), "i");
        Optional<Manifest> m = manifest(Map.of("t", f(5)));

        List<SyncAction> parsed = List.of(newOnBackup("t"));
        Interpretation result = run(parsed, SyncMode.TWO_WAY, m);

        assertEquals(parsed, result.actions());
        assertEquals(Map.of("t", Texts.t("note.manifest.typeChanged", Texts.t("kind.file"), Texts.t("kind.dir"))),
                result.notes());
    }
}
