package uz.jtscorp.filesync.sync;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RiskScannerTest {

    @TempDir
    Path laptopRoot;
    @TempDir
    Path hddRoot;

    private final RiskScanner scanner = new RiskScanner();

    @Test
    void flagsFileThatDroppedToZeroBytes() throws Exception {
        Files.writeString(laptopRoot.resolve("report.txt"), "");
        Files.writeString(hddRoot.resolve("report.txt"), "important data that must not be lost");

        List<RiskFlag> flags = scanner.scan(laptopRoot, hddRoot, List.of());

        assertTrue(flags.stream().anyMatch(f ->
                f.scope() == RiskFlag.Scope.FILE && f.relativePath().orElseThrow().equals("report.txt")));
    }

    @Test
    void flagsMassChangeWhenMostFilesDiffer() throws Exception {
        for (int i = 0; i < 5; i++) {
            Files.writeString(laptopRoot.resolve("file" + i + ".txt"), "same-" + i);
            Files.writeString(hddRoot.resolve("file" + i + ".txt"), "same-" + i);
        }
        for (int i = 5; i < 60; i++) {
            Files.writeString(laptopRoot.resolve("file" + i + ".txt"), "laptop-" + i);
            Files.writeString(hddRoot.resolve("file" + i + ".txt"), "different-content-" + i + "-xx");
        }

        List<RiskFlag> flags = scanner.scan(laptopRoot, hddRoot, List.of());

        assertTrue(flags.stream().anyMatch(f -> f.scope() == RiskFlag.Scope.BATCH));
    }

    @Test
    void noFlagsForOneSmallChangeAmongMany() throws Exception {
        for (int i = 0; i < 20; i++) {
            Files.writeString(laptopRoot.resolve("file" + i + ".txt"), "same-" + i);
            Files.writeString(hddRoot.resolve("file" + i + ".txt"), "same-" + i);
        }
        Files.writeString(laptopRoot.resolve("file0.txt"), "same-0-edited");

        List<RiskFlag> flags = scanner.scan(laptopRoot, hddRoot, List.of());

        assertTrue(flags.isEmpty());
    }

    @Test
    void ignoresFilesMatchingNameIgnorePattern() throws Exception {
        Files.writeString(laptopRoot.resolve("cache.tmp"), "");
        Files.writeString(hddRoot.resolve("cache.tmp"), "some cached bytes that differ a lot");

        List<RiskFlag> flags = scanner.scan(laptopRoot, hddRoot, List.of("Name *.tmp"));

        assertTrue(flags.isEmpty());
    }

    @Test
    void ignoresEveryFileBeneathADirectoryMatchingNameIgnorePattern() throws Exception {
        // Nested, because Unison's "Name" matches the last component at any depth.
        // Enough files to trip the mass-change flag by themselves if they leak in.
        Path nested = laptopRoot.resolve("src/app/node_modules/pkg");
        Files.createDirectories(nested);
        for (int i = 0; i < 60; i++) {
            Files.writeString(nested.resolve("dep" + i + ".js"), "bytes-" + i);
        }
        Files.writeString(laptopRoot.resolve("real.txt"), "same");
        Files.writeString(hddRoot.resolve("real.txt"), "same");

        RiskScanner.Scan scan = scanner.scanWithTotals(laptopRoot, hddRoot, List.of("Name node_modules"));
        List<RiskFlag> flags = scan.flags();

        assertTrue(flags.isEmpty(), "node_modules subtree leaked into the scan: " + flags);
        // The totals follow the same rules: one file of four bytes, not sixty-one.
        assertEquals(1, scan.laptopFiles());
        assertEquals(4, scan.laptopBytes());
    }

    @Test
    void honoursBelowPathAndRegexIgnoreRulesToo() throws Exception {
        // The UI can emit all four Unison rule types. A type Unison ignores but the
        // scanner still walks raises a mass-change flag on files that are not syncing
        // at all, which teaches the user to click straight through the review gate.
        Path buried = laptopRoot.resolve("var/cache");
        Files.createDirectories(buried);
        for (int i = 0; i < 40; i++) {
            Files.writeString(buried.resolve("blob" + i + ".bin"), "bytes-" + i);
            Files.writeString(laptopRoot.resolve("app" + i + ".log"), "log-" + i);
        }
        Files.writeString(laptopRoot.resolve("real.txt"), "same");
        Files.writeString(hddRoot.resolve("real.txt"), "same");

        List<RiskFlag> flags = scanner.scan(laptopRoot, hddRoot,
                List.of("BelowPath var/cache", "Regex .*\\.log"));

        assertTrue(flags.isEmpty(), "BelowPath/Regex rules leaked into the scan: " + flags);
    }

    @Test
    void reportsABrokenRegexRuleInsteadOfSilentlyDroppingIt() {
        assertThrows(IOException.class,
                () -> scanner.scan(laptopRoot, hddRoot, List.of("Regex *[unclosed")));
    }

    @Test
    void unreadableFolderIsFlaggedInsteadOfFailingTheScan() throws Exception {
        Files.writeString(laptopRoot.resolve("a.txt"), "same");
        Files.writeString(hddRoot.resolve("a.txt"), "same");
        Path locked = Files.createDirectory(hddRoot.resolve("lost+found"));
        Assumptions.assumeTrue(locked.toFile().setReadable(false) && !Files.isReadable(locked),
                "needs a file system and a user that honour permissions");
        try {
            List<RiskFlag> flags = scanner.scan(laptopRoot, hddRoot, List.of());

            assertEquals(1, flags.size());
            assertEquals(RiskFlag.Scope.UNREADABLE, flags.getFirst().scope());
            assertTrue(flags.getFirst().reason().contains("lost+found"));
            assertEquals(List.of(), scanner.scan(laptopRoot, hddRoot, List.of("Name lost+found")));
        } finally {
            locked.toFile().setReadable(true);
        }
    }
}
