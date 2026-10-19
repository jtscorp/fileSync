package uz.jtscorp.filesync.sync;

import uz.jtscorp.filesync.sync.PlanInterpreter.Rename;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.SyncAction.Direction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Display only: a delete and a create in the same direction that hold the same bytes read as one rename. */
final class RenameDetector {

    private RenameDetector() {}

    static List<Rename> detect(List<SyncAction> actions, Path laptopRoot, Path hddRoot, IgnoreRules rules) {
        List<Rename> found = new ArrayList<>();
        Set<String> used = new HashSet<>();
        for (Direction direction : List.of(Direction.LAPTOP_TO_HDD, Direction.HDD_TO_LAPTOP)) {
            // The delete's copy survives on the side being written; the new one comes from the other.
            Path target = direction == Direction.LAPTOP_TO_HDD ? hddRoot : laptopRoot;
            Path source = direction == Direction.LAPTOP_TO_HDD ? laptopRoot : hddRoot;
            List<String> deletes = paths(actions, direction, ActionType.DELETED);
            List<String> creates = paths(actions, direction, ActionType.NEW);
            if (deletes.isEmpty() || creates.isEmpty()) {
                continue;
            }
            // Grouped by size (files) or file count (folders), so only plausible pairs are read.
            Map<String, List<String>> createsByShape = new HashMap<>();
            Map<String, Map<String, Manifest.Entry>> trees = new HashMap<>();
            for (String b : creates) {
                String shape = shape(source, b, rules, trees);
                if (shape != null) {
                    createsByShape.computeIfAbsent(shape, k -> new ArrayList<>()).add(b);
                }
            }
            // Quadratic within one size group at worst; it runs in the Check's background task.
            for (String a : deletes) {
                String shape = shape(target, a, rules, trees);
                for (String b : shape == null ? List.<String>of() : createsByShape.getOrDefault(shape, List.of())) {
                    // Each path takes part in one rename at most.
                    if (!used.contains(b) && sameContent(target, a, source, b, trees)) {
                        found.add(new Rename(a, b, direction));
                        used.add(b);
                        break;
                    }
                }
            }
        }
        return found;
    }

    private static List<String> paths(List<SyncAction> actions, Direction direction, ActionType type) {
        return actions.stream().filter(a -> a.direction() == direction && a.actionType() == type)
                .map(SyncAction::relativePath).toList();
    }

    /**
     * "f:size" or "d:fileCount"; null for what cannot take part: a link, an unreadable place, an
     * empty file or a folder with no file in it -- two empty things say nothing about a rename.
     */
    private static String shape(Path root, String path, IgnoreRules rules,
                                Map<String, Map<String, Manifest.Entry>> trees) {
        try {
            BasicFileAttributes attrs = Files.readAttributes(root.resolve(path), BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (attrs.isRegularFile()) {
                return attrs.size() > 0 ? "f:" + attrs.size() : null;
            }
            if (!attrs.isDirectory()) {
                return null;
            }
            Manifest.Walk walk = Manifest.walk(root, path, rules);
            Map<String, Manifest.Entry> inside = new TreeMap<>();
            walk.below(path).forEach((k, v) -> inside.put(k.substring(path.length() + 1), v));
            long files = inside.values().stream().filter(e -> e.type() == 'f').count();
            if (!walk.unreadable().isEmpty() || files == 0) {
                return null;
            }
            trees.put(root + "\0" + path, inside);
            return "d:" + files;
        } catch (IOException e) {
            return null;
        }
    }

    private static boolean sameContent(Path rootA, String a, Path rootB, String b,
                                       Map<String, Map<String, Manifest.Entry>> trees) {
        Map<String, Manifest.Entry> treeA = trees.get(rootA + "\0" + a);
        Map<String, Manifest.Entry> treeB = trees.get(rootB + "\0" + b);
        try {
            if (treeA == null) {
                return Files.mismatch(rootA.resolve(a), rootB.resolve(b)) == -1;
            }
            if (!treeA.equals(treeB)) {
                return false;
            }
            for (Map.Entry<String, Manifest.Entry> e : treeA.entrySet()) {
                Path pa = rootA.resolve(a).resolve(e.getKey());
                Path pb = rootB.resolve(b).resolve(e.getKey());
                boolean same = switch (e.getValue().type()) {
                    case 'f' -> Files.mismatch(pa, pb) == -1;
                    case 'l' -> Files.readSymbolicLink(pa).equals(Files.readSymbolicLink(pb));
                    default -> true;
                };
                if (!same) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }
}
