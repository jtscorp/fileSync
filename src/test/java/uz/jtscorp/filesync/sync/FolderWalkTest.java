package uz.jtscorp.filesync.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class FolderWalkTest {

    private static void write(Path root, String rel, int size) throws IOException {
        Path file = root.resolve(rel);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[size]);
    }

    @Test
    void listsFilesBelowTheFolderSortedAndRelativeToTheRoot(@TempDir Path root) throws IOException {
        write(root, "old/b.txt", 2);
        write(root, "old/a/z.txt", 3);
        write(root, "old/a/c.txt", 1);
        write(root, "other/x.txt", 5);

        assertEquals(List.of(
                new FolderWalk.Item("old/a/c.txt", 1, false),
                new FolderWalk.Item("old/a/z.txt", 3, false),
                new FolderWalk.Item("old/b.txt", 2, false)),
                FolderWalk.contents(root, "old", List.of()));
    }

    @Test
    void ignoredFilesAreListedAndMarked(@TempDir Path root) throws IOException {
        // Unison deletes them together with the folder, so they must be on the list.
        write(root, "old/keep.txt", 1);
        write(root, "old/node_modules/lib.js", 4);
        write(root, "old/x.tmp", 2);

        assertEquals(List.of(
                new FolderWalk.Item("old/keep.txt", 1, false),
                new FolderWalk.Item("old/node_modules/lib.js", 4, true),
                new FolderWalk.Item("old/x.tmp", 2, true)),
                FolderWalk.contents(root, "old", List.of("Name node_modules", "Name *.tmp")));
    }

    @Test
    void skipsTheManifestFolder(@TempDir Path root) throws IOException {
        write(root, ".filesync/manifest.tsv", 7);
        write(root, "a.txt", 1);

        assertEquals(List.of(new FolderWalk.Item("a.txt", 1, false)),
                FolderWalk.contents(root, "", List.of()));
    }

    @Test
    void skipsAnUnreadableSubfolderAndListsTheRest(@TempDir Path root) throws IOException {
        assumeFalse("root".equals(System.getProperty("user.name")));
        write(root, "old/a.txt", 1);
        write(root, "old/locked/hidden.txt", 9);
        write(root, "old/z.txt", 2);
        Path locked = root.resolve("old/locked");
        Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("---------"));
        try {
            assertEquals(List.of(
                    new FolderWalk.Item("old/a.txt", 1, false),
                    new FolderWalk.Item("old/z.txt", 2, false)),
                    FolderWalk.contents(root, "old", List.of()));
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void doesNotFollowLinks(@TempDir Path root) throws IOException {
        write(root, "elsewhere/big.bin", 100);
        Files.createDirectories(root.resolve("old"));
        Files.createSymbolicLink(root.resolve("old/link"), root.resolve("elsewhere"));

        assertEquals(List.of(new FolderWalk.Item("old/link", 0, false)),
                FolderWalk.contents(root, "old", List.of()));
    }
}
