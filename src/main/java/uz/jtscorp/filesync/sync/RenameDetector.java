package uz.jtscorp.filesync.sync;

import uz.jtscorp.filesync.sync.PlanInterpreter.Rename;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.SyncAction.Direction;
import uz.jtscorp.filesync.sync.SyncAction.Origin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Collectors;

/** Display only: a delete and a create in the same direction that hold the same bytes read as one rename. */
final class RenameDetector {

    private static final IgnoreRules NO_RULES = new IgnoreRules(List.of(), List.of(), List.of());

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
            Set<String> forced = actions.stream()
                    .filter(x -> x.direction() == direction && x.actionType() == ActionType.DELETED
                            && x.origin() != Origin.UNISON)
                    .map(SyncAction::relativePath).collect(Collectors.toSet());
            // Quadratic within one size group at worst; it runs in the Check's background task.
            for (String a : deletes) {
                if (forced.contains(a) && holdsIgnored(target, a, rules)) {
                    continue;
                }
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

    /**
     * A folder that -force deletes takes its ignored files with it (verified on 2.53.3), yet the
     * rename walk below applies the rules and never sees them. Paired, the row would read as a
     * harmless rename and the folder's full listing would never be shown, so a folder holding an
     * ignored entry, or a part that cannot be read, is left as a plain delete.
     */
    private static boolean holdsIgnored(Path root, String path, IgnoreRules rules) {
        if (!Files.isDirectory(root.resolve(path), LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            Manifest.Walk all = Manifest.walk(root, path, NO_RULES);
            return !all.unreadable().isEmpty() || all.below(path).keySet().stream().anyMatch(rules::matches);
        } catch (IOException e) {
            return true;
        }
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
            // Without the times: the two copies sit on different sides and were written at different moments.
            walk.below(path).forEach((k, v) ->
                    inside.put(k.substring(path.length() + 1), new Manifest.Entry(v.type(), v.size(), 0)));
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
