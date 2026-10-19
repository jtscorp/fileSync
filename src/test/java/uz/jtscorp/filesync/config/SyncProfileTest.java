package uz.jtscorp.filesync.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncProfileTest {

    @TempDir
    Path tempDir;

    @Test
    void roundTripsThroughPrfFile() throws Exception {
        SyncProfile original = new SyncProfile(
                "Documents",
                Path.of("/home/user/Documents"),
                Path.of("/media/user/HDD/Documents"),
                List.of("Name *.tmp", "Path Private/secret-notes"));
        Path prfFile = tempDir.resolve("Documents.prf");

        original.writeTo(prfFile);
        SyncProfile loaded = SyncProfile.readFrom("Documents", prfFile);

        assertEquals(original.laptopRoot(), loaded.laptopRoot());
        assertEquals(original.hddRoot(), loaded.hddRoot());
        assertEquals(original.ignorePatterns(), loaded.ignorePatterns());
    }

    @Test
    void writeToLeavesNoTmpFileBehindAfterASuccessfulWrite() throws Exception {
        SyncProfile profile = new SyncProfile(
                "Documents", Path.of("/a"), Path.of("/b"), List.of());
        Path prfFile = tempDir.resolve("Documents.prf");

        profile.writeTo(prfFile);

        assertTrue(Files.exists(prfFile));
        assertFalse(Files.exists(tempDir.resolve("Documents.prf.tmp")));
    }

    @Test
    void failedWriteLeavesOriginalFileUnchanged() throws Exception {
        // writeTo() writes a sibling .tmp file and then moves it, so a failed write must
        // leave the original .prf intact. A directory at the .tmp path forces the failure.
        Path prfFile = tempDir.resolve("Documents.prf");
        SyncProfile original = new SyncProfile("Documents", Path.of("/original/laptop"),
                Path.of("/original/hdd"), List.of("Name *.bak"));
        original.writeTo(prfFile);

        Path tmpFile = prfFile.resolveSibling(prfFile.getFileName().toString() + ".tmp");
        Files.createDirectory(tmpFile);

        SyncProfile broken = new SyncProfile("Documents", Path.of("/different/laptop"),
                Path.of("/different/hdd"), List.of());

        assertThrows(IOException.class, () -> broken.writeTo(prfFile));

        SyncProfile stillOriginal = SyncProfile.readFrom("Documents", prfFile);
        assertEquals(original.laptopRoot(), stillOriginal.laptopRoot());
        assertEquals(original.hddRoot(), stillOriginal.hddRoot());
        assertEquals(original.ignorePatterns(), stillOriginal.ignorePatterns());
    }

    @Test
    void sanitizesSpecialCharactersForFileNames() {
        assertEquals("My_Docs_2024", SyncProfile.sanitizeFileName("My Docs/2024"));
    }

    @Test
    void keepsLettersOfAnyScriptInFileNames() {
        assertEquals("Документы_2024", SyncProfile.sanitizeFileName("Документы 2024"));
        assertEquals("Oʻzbekcha", SyncProfile.sanitizeFileName("Oʻzbekcha"));
    }

    @Test
    void fileNameNeverStartsWithADash() {
        // Unison is handed the name as an argument and would read "-batch" as an option.
        assertEquals("_-batch", SyncProfile.sanitizeFileName("-batch"));
    }

    @Test
    void normalizesBareIgnorePatternsByShape() {
        // A separator forces "Path": "Name" matches the last component only, so
        // "Name Private/secret-notes" would save and display fine while never matching.
        assertEquals("Name *.tmp", SyncProfile.normalizeIgnorePattern("*.tmp"));
        assertEquals("Name node_modules", SyncProfile.normalizeIgnorePattern("node_modules"));
        assertEquals("Path Private/secret-notes",
                SyncProfile.normalizeIgnorePattern("Private/secret-notes"));
    }

    @Test
    void leavesAlreadyPrefixedIgnorePatternsAlone() {
        for (String rule : List.of("Name *.tmp", "Path a/b", "BelowPath a/b", "Regex .*\\.log")) {
            assertEquals(rule, SyncProfile.normalizeIgnorePattern(rule));
        }
        assertEquals("", SyncProfile.normalizeIgnorePattern("   "));
    }

    @Test
    void roundTripsEmptyPathsForAFreshlyCreatedProfile() throws Exception {
        // Mirrors MainView.onNewProfile(), which saves a profile with empty
        // roots before the user has picked any folders.
        SyncProfile fresh = new SyncProfile("Demo", Path.of(""), Path.of(""), List.of());
        Path prfFile = tempDir.resolve("Demo.prf");

        fresh.writeTo(prfFile);
        SyncProfile loaded = SyncProfile.readFrom("Demo", prfFile);

        assertEquals(Path.of(""), loaded.laptopRoot());
        assertEquals(Path.of(""), loaded.hddRoot());
    }

    @Test
    void roundTripsTheMergeBackupOptIn() throws Exception {
        Path prfFile = tempDir.resolve("Merge.prf");

        new SyncProfile("Merge", Path.of("/a"), Path.of("/b"), List.of(), true).writeTo(prfFile);
        assertTrue(Files.readString(prfFile).contains("backupcurrent = Name *"));
        assertTrue(SyncProfile.readFrom("Merge", prfFile).keepMergeBackups());

        new SyncProfile("Merge", Path.of("/a"), Path.of("/b"), List.of()).writeTo(prfFile);
        assertFalse(Files.readString(prfFile).contains("backupcurrent"));
        assertFalse(SyncProfile.readFrom("Merge", prfFile).keepMergeBackups());
    }

    private SyncProfile withMode(SyncMode mode, List<String> ignores) {
        return new SyncProfile("P", Path.of("/a"), Path.of("/b"), ignores, false, mode);
    }

    @Test
    void roundTripsEachMode() throws Exception {
        for (SyncMode mode : SyncMode.values()) {
            Path prf = tempDir.resolve(mode.prfValue() + ".prf");
            withMode(mode, List.of()).writeTo(prf);
            assertEquals(mode, SyncProfile.readFrom("P", prf).mode());
        }
    }

    @Test
    void missingOrUnknownModeReadsAsTwoWay() throws Exception {
        Path none = tempDir.resolve("none.prf");
        Files.writeString(none, "root = /a\nroot = /b\n");
        assertEquals(SyncMode.TWO_WAY, SyncProfile.readFrom("P", none).mode());
        Path odd = tempDir.resolve("odd.prf");
        Files.writeString(odd, "root = /a\nroot = /b\n# filesync-mode = sideways\n");
        assertEquals(SyncMode.TWO_WAY, SyncProfile.readFrom("P", odd).mode());
    }

    @Test
    void writesTheModeLineVerbatim() throws Exception {
        Path prf = tempDir.resolve("v.prf");
        withMode(SyncMode.TWO_WAY_PRUNE, List.of()).writeTo(prf);
        assertTrue(Files.readString(prf).contains("# filesync-mode = two-way-prune\n"));
    }

    @Test
    void alwaysWritesManifestIgnoreButNeverListsIt() throws Exception {
        Path prf = tempDir.resolve("m.prf");
        SyncProfile p = withMode(SyncMode.TWO_WAY, List.of("Name *.tmp", "Path .filesync"));
        p.writeTo(prf);
        String text = Files.readString(prf);
        String line = "ignore = Path .filesync\n";
        assertEquals(text.indexOf(line), text.lastIndexOf(line));
        assertTrue(text.contains(line));
        SyncProfile loaded = SyncProfile.readFrom("P", prf);
        assertFalse(loaded.ignorePatterns().contains("Path .filesync"));
        assertEquals("Path .filesync", loaded.unisonIgnores().getLast());
        assertEquals(1, p.unisonIgnores().stream().filter("Path .filesync"::equals).count());
    }

    @Test
    void modeChangeChangesTheFileBytes() throws Exception {
        Path a = tempDir.resolve("a.prf");
        Path b = tempDir.resolve("b.prf");
        withMode(SyncMode.TWO_WAY, List.of()).writeTo(a);
        withMode(SyncMode.MIRROR_TO_BACKUP, List.of()).writeTo(b);
        assertFalse(java.util.Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b)));
    }
}
