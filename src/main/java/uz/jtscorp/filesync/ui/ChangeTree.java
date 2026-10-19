package uz.jtscorp.filesync.ui;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * The review list's rows as a folder tree, flattened into the order the list shows them:
 * each folder, then -- while it is open -- its subfolders and its own files, one level
 * deeper. Built from what {@link ChangeList} already filtered and ordered, and free of
 * JavaFX so the shape has a plain test.
 */
final class ChangeTree {

    sealed interface Node permits Folder, Leaf {}

    /**
     * @param path  the folder's full path, which is what its open state is kept by
     * @param name  what the row shows: a merged chain reads {@code src/main/resources}
     * @param files every entry under the folder, however deep and whether open or not, in list order
     */
    record Folder(String path, String name, int depth, boolean open, List<ChangeList.Entry> files)
            implements Node {

        /** The paths a tick on the folder acts on: the rows' own, a move's {@code to}. */
        List<String> paths() {
            return files.stream().map(e -> e.action().relativePath()).toList();
        }

        long count(Predicate<ChangeList.Entry> test) {
            return files.stream().filter(test).count();
        }
    }

    record Leaf(ChangeList.Entry entry, int depth) implements Node {}

    /** A folder's checkbox: every file under it ticked, some, or none. */
    enum Ticks { ALL, SOME, NONE }

    private ChangeTree() {}

    /**
     * @param closed   folder paths the user closed; everything else is open
     * @param sort     the list's order; folders follow it only when it is by path or name
     */
    static List<Node> flatten(List<ChangeList.Entry> entries, Set<String> closed, ChangeList.Sort sort,
                              boolean reversed) {
        Dir root = new Dir("", "");
        for (ChangeList.Entry entry : entries) {
            String[] segments = entry.action().relativePath().split("/");
            Dir dir = root;
            for (int i = 0; i < segments.length - 1; i++) {
                String path = dir.path.isEmpty() ? segments[i] : dir.path + "/" + segments[i];
                dir = dir.dirs.computeIfAbsent(segments[i], name -> new Dir(name, path));
                dir.all.add(entry);
            }
            dir.files.add(entry);
        }
        Comparator<Dir> byName = Comparator.comparing(d -> d.name);
        boolean turned = reversed && (sort == ChangeList.Sort.PATH || sort == ChangeList.Sort.NAME);
        List<Node> nodes = new ArrayList<>();
        walk(root, 0, closed, turned ? byName.reversed() : byName, nodes);
        return nodes;
    }

    private static void walk(Dir dir, int depth, Set<String> closed, Comparator<Dir> order, List<Node> out) {
        for (Dir top : dir.dirs.values().stream().sorted(order).toList()) {
            // A folder with no files of its own and a single subfolder is shown as one row.
            Dir last = top;
            StringBuilder name = new StringBuilder(top.name);
            while (last.files.isEmpty() && last.dirs.size() == 1) {
                last = last.dirs.values().iterator().next();
                name.append('/').append(last.name);
            }
            boolean open = !closed.contains(last.path);
            out.add(new Folder(last.path, name.toString(), depth, open, List.copyOf(last.all)));
            if (open) {
                walk(last, depth + 1, closed, order, out);
            }
        }
        dir.files.forEach(entry -> out.add(new Leaf(entry, depth)));
    }

    static Ticks ticks(List<String> paths, Set<String> ticked) {
        long count = paths.stream().filter(ticked::contains).count();
        if (count == 0) {
            return Ticks.NONE;
        }
        return count == paths.size() ? Ticks.ALL : Ticks.SOME;
    }

    /** {@code all} gets every entry under the folder, in the order they were added. */
    private static final class Dir {
        final String name;
        final String path;
        final Map<String, Dir> dirs = new LinkedHashMap<>();
        final List<ChangeList.Entry> files = new ArrayList<>();
        final List<ChangeList.Entry> all = new ArrayList<>();

        Dir(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }
}
