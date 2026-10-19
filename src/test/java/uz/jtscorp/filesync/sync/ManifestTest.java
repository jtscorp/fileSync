package uz.jtscorp.filesync.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class ManifestTest {

    private static final Instant NOW = Instant.parse("2026-10-19T10:15:30Z");

    private static Manifest sample(Map<String, Manifest.Entry> entries) {
        return new Manifest("work", "/home/u/docs", "laptop", NOW, entries);
    }

    @Test
    void roundTripsHeaderAndEntries(@TempDir Path hdd) throws IOException {
        Manifest m = sample(Map.of("a.txt", new Manifest.Entry('f', 12), "dir", new Manifest.Entry('d', 0),
                "ln", new Manifest.Entry('l', 0)));
        m.write(hdd);
        assertEquals(Optional.of(m), Manifest.read(hdd));
        assertTrue(Files.readString(Manifest.file(hdd)).startsWith("# filesync-manifest 1\n"));
    }

    @Test
    void roundTripsPathsWithTabNewlineAndBackslash(@TempDir Path hdd) throws IOException {
        Manifest m = sample(Map.of("a\tb", new Manifest.Entry('f', 1), "c\nd", new Manifest.Entry('f', 2),
                "e\\f", new Manifest.Entry('f', 3)));
        m.write(hdd);
        assertEquals(m.entries(), Manifest.read(hdd).orElseThrow().entries());
    }

    @Test
    void readIsEmptyWhenMissingOrMalformed(@TempDir Path hdd) throws IOException {
        assertTrue(Manifest.read(hdd).isEmpty());
        Files.createDirectories(Manifest.file(hdd).getParent());
        Files.writeString(Manifest.file(hdd), "# something else\n");
        assertTrue(Manifest.read(hdd).isEmpty());
        Files.writeString(Manifest.file(hdd), "# filesync-manifest 1\n# profile = p\n# laptopRoot = /l\n"
                + "# host = h\n# lastSync = 2026-10-19T10:15:30Z\nx\t1\n");
        assertTrue(Manifest.read(hdd).isEmpty());
    }

    private static void file(Path p, String content) throws IOException {
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    @Test
    void captureKeepsOnlyPathsSameOnBothSides(@TempDir Path root) throws IOException {
        Path lap = root.resolve("lap");
        Path hdd = root.resolve("hdd");
        file(lap.resolve("same.txt"), "abc");
        file(hdd.resolve("same.txt"), "abc");
        file(lap.resolve("diff.txt"), "abc");
        file(hdd.resolve("diff.txt"), "abcd");
        file(lap.resolve("only-laptop.txt"), "x");
        Files.createDirectories(lap.resolve("d"));
        Files.createDirectories(hdd.resolve("d"));
        file(lap.resolve("t"), "x");
        Files.createDirectories(hdd.resolve("t"));

        Manifest m = Manifest.capture(lap, hdd, List.of(), "p", "h", NOW, Optional.empty(), Set.of());

        assertEquals(Map.of("same.txt", new Manifest.Entry('f', 3), "d", new Manifest.Entry('d', 0)),
                m.entries());
    }

    @Test
    void captureObeysIgnoreRulesAndSkipsTheFilesyncFolder(@TempDir Path root) throws IOException {
        Path lap = root.resolve("lap");
        Path hdd = root.resolve("hdd");
        for (Path side : List.of(lap, hdd)) {
            file(side.resolve("build/out.bin"), "x");
            file(side.resolve(".filesync/manifest.tsv"), "x");
            file(side.resolve("keep.txt"), "x");
        }

        Manifest m = Manifest.capture(lap, hdd, List.of("Name build"), "p", "h", NOW,
                Optional.empty(), Set.of());

        assertEquals(Set.of("keep.txt"), m.entries().keySet());
    }

    @Test
    void captureCarriesOverSkippedPaths(@TempDir Path root) throws IOException {
        Path lap = root.resolve("lap");
        Path hdd = root.resolve("hdd");
        Files.createDirectories(lap);
        file(hdd.resolve("uz/ishonch/A.java"), "x");
        Manifest previous = sample(Map.of("uz/ishonch", new Manifest.Entry('d', 0),
                "uz/ishonch/A.java", new Manifest.Entry('f', 1)));

        Manifest m = Manifest.capture(lap, hdd, List.of(), "p", "h", NOW, Optional.of(previous),
                Set.of("uz/ishonch"));

        assertEquals(previous.entries(), m.entries());
    }

    @Test
    void failedWriteLeavesNoTmpAndThrows(@TempDir Path hdd) throws IOException {
        assumeFalse("root".equals(System.getProperty("user.name")));
        Path folder = Files.createDirectories(hdd.resolve(".filesync"));
        Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("r-xr-xr-x"));
        try {
            assertThrows(IOException.class, () -> sample(Map.of()).write(hdd));
            try (var list = Files.list(folder)) {
                assertEquals(0, list.count());
            }
        } finally {
            Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void belongsToComparesRootAndHost() {
        Manifest m = sample(Map.of());
        assertTrue(m.belongsTo("/home/u/docs", "laptop"));
        assertFalse(m.belongsTo("/home/u/docs", "other"));
        assertFalse(m.belongsTo("/elsewhere", "laptop"));
    }
}
