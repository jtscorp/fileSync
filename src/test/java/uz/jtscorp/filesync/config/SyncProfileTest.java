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
}
