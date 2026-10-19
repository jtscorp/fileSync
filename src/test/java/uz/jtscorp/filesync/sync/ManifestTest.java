package uz.jtscorp.filesync.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
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

    /** A previous manifest that belongs to {@code lap} on host "h", the one the capture tests use. */
    private static Manifest previousOf(Path lap, Map<String, Manifest.Entry> entries) {
        return new Manifest("work", lap.toString(), "h", NOW, entries);
    }

    private static Manifest.Entry entry(char type, long size) {
        return new Manifest.Entry(type, size, 1_760_000_000_000L);
    }

    /** What the walk records for {@code p}: its type, size and own (not followed) modified time. */
    private static Manifest.Entry onDisk(Path p) throws IOException {
        BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        char type = attrs.isSymbolicLink() ? 'l' : attrs.isDirectory() ? 'd' : 'f';
        return new Manifest.Entry(type, type == 'f' ? attrs.size() : 0, attrs.lastModifiedTime().toMillis());
    }

    @Test
    void roundTripsHeaderAndEntries(@TempDir Path hdd) throws IOException {
        Manifest m = sample(Map.of("a.txt", entry('f', 12), "dir", entry('d', 0), "ln", entry('l', 0)));
        m.write(hdd);
        assertEquals(Optional.of(m), Manifest.read(hdd));
        assertTrue(Files.readString(Manifest.file(hdd)).startsWith("# filesync-manifest 1\n"));
        assertTrue(Files.readString(Manifest.file(hdd)).contains("\nf\t12\t1760000000000\ta.txt\n"));
    }

    @Test
    void roundTripsPathsWithTabNewlineAndBackslash(@TempDir Path hdd) throws IOException {
        Manifest m = sample(Map.of("a\tb", entry('f', 1), "c\nd", entry('f', 2), "e\\f", entry('f', 3),
                "a\rb", entry('f', 4)));
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
        // The three-column row (no modified time) is malformed too.
        Files.writeString(Manifest.file(hdd), "# filesync-manifest 1\n# profile = p\n# laptopRoot = /l\n"
                + "# host = h\n# lastSync = 2026-10-19T10:15:30Z\nf\t1\ta.txt\n");
        assertTrue(Manifest.read(hdd).isEmpty());
    }

    @Test
    void recordsTheHddModifiedTime(@TempDir Path root) throws IOException {
        Path lap = root.resolve("lap");
        Path hdd = root.resolve("hdd");
        file(lap.resolve("a.txt"), "abc");
        file(hdd.resolve("a.txt"), "xyz");
        Instant hddTime = Instant.parse("2026-01-02T03:04:05Z");
        Files.setLastModifiedTime(lap.resolve("a.txt"), FileTime.from(Instant.parse("2026-05-06T07:08:09Z")));
        Files.setLastModifiedTime(hdd.resolve("a.txt"), FileTime.from(hddTime));

        Manifest m = Manifest.capture(lap, hdd, List.of(), "p", "h", NOW, Optional.empty(), Set.of());

        // Unison does not carry mtimes across, so differing times still count as "same"; the hdd one is kept.
        assertEquals(Map.of("a.txt", new Manifest.Entry('f', 3, hddTime.toEpochMilli())), m.entries());
        m.write(hdd);
        assertEquals(m.entries(), Manifest.read(hdd).orElseThrow().entries());
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

        assertEquals(Map.of("same.txt", onDisk(hdd.resolve("same.txt")), "d", onDisk(hdd.resolve("d"))),
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
        Manifest previous = previousOf(lap, Map.of("uz/ishonch", entry('d', 0),
                "uz/ishonch/A.java", entry('f', 1)));

        Manifest m = Manifest.capture(lap, hdd, List.of(), "p", "h", NOW, Optional.of(previous),
                Set.of("uz/ishonch"));

        assertEquals(previous.entries(), m.entries());
    }

    @Test
    void carriedPathReplacesWalkedIdenticalEntryWithPrevious(@TempDir Path root) throws IOException {
        Path lap = root.resolve("lap");
        Path hdd = root.resolve("hdd");
        file(lap.resolve("c/x.txt"), "abc");
        file(hdd.resolve("c/x.txt"), "abc");
        Manifest previous = previousOf(lap, Map.of("c/x.txt", entry('f', 99)));

        Manifest m = Manifest.capture(lap, hdd, List.of(), "p", "h", NOW, Optional.of(previous), Set.of("c"));

        assertEquals(Map.of("c/x.txt", entry('f', 99)), m.entries());
    }

    @Test
    void foreignPreviousContributesNothing(@TempDir Path root) throws IOException {
        // Another machine's or another folder's record must not become ours: the next Check
        // would read its entries as "deleted on the main side".
        Path lap = root.resolve("lap");
        Path hdd = root.resolve("hdd");
        Files.createDirectories(lap);
        file(hdd.resolve("c/x.txt"), "abc");
        file(lap.resolve("other.txt"), "abc");
        file(hdd.resolve("other.txt"), "abc");
        Map<String, Manifest.Entry> theirs = Map.of("c", entry('d', 0), "c/x.txt", entry('f', 3));

        for (Manifest foreign : List.of(
                new Manifest("work", lap.toString(), "another-host", NOW, theirs),
                new Manifest("work", "/somewhere/else", "h", NOW, theirs))) {
            Manifest m = Manifest.capture(lap, hdd, List.of(), "p", "h", NOW, Optional.of(foreign),
                    Set.of("c"));

            assertEquals(Set.of("other.txt"), m.entries().keySet());
        }
    }

    @Test
    void carriedPathsAreNeverRecordedFromTheWalk(@TempDir Path root) throws IOException {
        Path lap = root.resolve("lap");
        Path hdd = root.resolve("hdd");
        for (Path side : List.of(lap, hdd)) {
            file(side.resolve("c/x.txt"), "abc");
            file(side.resolve("other.txt"), "abc");
        }

        Manifest m = Manifest.capture(lap, hdd, List.of(), "p", "h", NOW, Optional.empty(), Set.of("c"));

        assertEquals(Set.of("other.txt"), m.entries().keySet());
    }

    @Test
    void hddRootThatIsASymlinkIsCapturedNormally(@TempDir Path root) throws IOException {
        Path lap = root.resolve("lap");
        Path real = root.resolve("real-hdd");
        file(lap.resolve("d/a.txt"), "abc");
        file(real.resolve("d/a.txt"), "abc");
        Path hdd = Files.createSymbolicLink(root.resolve("hdd"), real);

        Manifest m = Manifest.capture(lap, hdd, List.of(), "p", "h", NOW, Optional.empty(), Set.of());

        assertEquals(Map.of("d", onDisk(real.resolve("d")), "d/a.txt", onDisk(real.resolve("d/a.txt"))),
                m.entries());
    }

    @Test
    void backslashInNameIsNotTurnedIntoSlash(@TempDir Path root) throws IOException {
        Path lap = root.resolve("lap");
        Path hdd = root.resolve("hdd");
        file(lap.resolve("e\\f"), "x");
        file(hdd.resolve("e\\f"), "x");

        Manifest m = Manifest.capture(lap, hdd, List.of(), "p", "h", NOW, Optional.empty(), Set.of());

        assertEquals(Set.of("e\\f"), m.entries().keySet());
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
