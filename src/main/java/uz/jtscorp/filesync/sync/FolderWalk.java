package uz.jtscorp.filesync.sync;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.StringJoiner;

/**
 * What a folder that Apply is about to delete or overwrite holds, for the review screen.
 * The ignore rules are not applied, only marked: Unison deletes ignored files together with
 * their folder (verified on 2.53.3), so leaving them off the list would hide a deletion.
 */
public final class FolderWalk {

    /** @param path relative to the walked root, '/'-separated; a link has size 0 */
    public record Item(String path, long size, boolean ignored) {}

    private static final String FOLDER = ".filesync";

    private FolderWalk() {}

    /**
     * Every file and link under {@code root/relativeDir}, sorted by path. Links are not
     * followed; a part that cannot be read is left out rather than failing the check.
     */
    public static List<Item> contents(Path root, String relativeDir, List<String> ignores) throws IOException {
        IgnoreRules rules = IgnoreRules.parse(ignores);
        Path start = relativeDir.isEmpty() ? root : root.resolve(relativeDir);
        List<Item> items = new ArrayList<>();
        Files.walkFileTree(start, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return relative(root, dir).equals(FOLDER) ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String rel = relative(root, file);
                items.add(new Item(rel, attrs.isSymbolicLink() ? 0 : attrs.size(), ignored(rel, rules)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) {
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                // The default rethrows a listing that broke off midway; the rest still counts.
                return FileVisitResult.CONTINUE;
            }
        });
        items.sort(Comparator.comparing(Item::path));
        return List.copyOf(items);
    }

    /** An ignored folder covers everything below it, as it does for Unison. */
    private static boolean ignored(String path, IgnoreRules rules) {
        for (int i = path.indexOf('/'); i >= 0; i = path.indexOf('/', i + 1)) {
            if (rules.matches(path.substring(0, i))) {
                return true;
            }
        }
        return rules.matches(path);
    }

    private static String relative(Path root, Path p) {
        // Join the name elements: a backslash is an ordinary character in a Linux file name.
        StringJoiner joined = new StringJoiner("/");
        root.relativize(p).forEach(part -> joined.add(part.toString()));
        return joined.toString();
    }
}
