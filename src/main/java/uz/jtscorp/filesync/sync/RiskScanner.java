package uz.jtscorp.filesync.sync;

import uz.jtscorp.filesync.Texts;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RiskScanner {

    private static final double SIZE_DROP_THRESHOLD = 0.90;
    private static final double MASS_CHANGE_RATIO_THRESHOLD = 0.20;
    private static final int MASS_CHANGE_COUNT_THRESHOLD = 50;
    /** How many unreadable paths the flag names; the rest are only counted. */
    private static final int UNREADABLE_SHOWN = 5;

    /**
     * @param laptopFiles files in the laptop copy after the ignore rules; {@code laptopBytes} their total size
     * @param laptopTree  what the laptop copy looks like, for the anomaly model; {@code hddTree} the same for the HDD
     */
    public record Scan(List<RiskFlag> flags, long laptopFiles, long laptopBytes, TreeProfile laptopTree,
                       TreeProfile hddTree) {}

    public List<RiskFlag> scan(Path laptopRoot, Path hddRoot, List<String> ignorePatterns) throws IOException {
        return scanWithTotals(laptopRoot, hddRoot, ignorePatterns).flags();
    }

    public Scan scanWithTotals(Path laptopRoot, Path hddRoot, List<String> ignorePatterns) throws IOException {
        IgnoreRules rules = IgnoreRules.parse(ignorePatterns);

        List<Path> unreadable = new ArrayList<>();
        TreeProfile laptopTree = new TreeProfile();
        TreeProfile hddTree = new TreeProfile();
        Map<String, Long> laptopSizes = collectSizes(laptopRoot, rules, unreadable, laptopTree);
        Map<String, Long> hddSizes = collectSizes(hddRoot, rules, unreadable, hddTree);

        Set<String> allPaths = new HashSet<>();
        allPaths.addAll(laptopSizes.keySet());
        allPaths.addAll(hddSizes.keySet());

        List<RiskFlag> flags = new ArrayList<>();
        int changedCount = 0;

        for (String relativePath : allPaths) {
            Long laptopSize = laptopSizes.get(relativePath);
            Long hddSize = hddSizes.get(relativePath);

            if (laptopSize == null || hddSize == null) {
                changedCount++;
                continue;
            }
            if (!laptopSize.equals(hddSize)) {
                changedCount++;
                long larger = Math.max(laptopSize, hddSize);
                long smaller = Math.min(laptopSize, hddSize);
                if (larger > 0 && (smaller == 0 || (double) (larger - smaller) / larger >= SIZE_DROP_THRESHOLD)) {
                    flags.add(RiskFlag.forFile(relativePath,
                            Texts.t("risk.shrunk", larger, smaller)));
                }
            }
        }

        int totalScanned = allPaths.size();
        boolean overRatio = totalScanned > 0 && (double) changedCount / totalScanned > MASS_CHANGE_RATIO_THRESHOLD;
        boolean overCount = changedCount > MASS_CHANGE_COUNT_THRESHOLD;
        if (overRatio || overCount) {
            flags.add(RiskFlag.forBatch(
                    Texts.t("risk.mass", changedCount, totalScanned)));
        }
        if (!unreadable.isEmpty()) {
            String shown = String.join(", ", unreadable.stream().limit(UNREADABLE_SHOWN).map(Path::toString).toList());
            flags.add(RiskFlag.forUnreadable(Texts.t("risk.unreadable", unreadable.size(),
                    unreadable.size() > UNREADABLE_SHOWN ? shown + ", …" : shown)));
        }

        return new Scan(flags, laptopSizes.size(),
                laptopSizes.values().stream().mapToLong(Long::longValue).sum(), laptopTree, hddTree);
    }

    /** @param tree filled along the way: the walk is the one chance to see every file */
    private Map<String, Long> collectSizes(Path root, IgnoreRules rules, List<Path> unreadable, TreeProfile tree)
            throws IOException {
        Map<String, Long> sizes = new HashMap<>();
        if (!Files.isDirectory(root)) {
            return sizes;
        }
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                // Like Unison, an ignored directory drops its whole subtree.
                if (dir.equals(root)) {
                    return FileVisitResult.CONTINUE;
                }
                String relativePath = root.relativize(dir).toString().replace('\\', '/');
                return rules.matches(relativePath)
                        ? FileVisitResult.SKIP_SUBTREE
                        : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                String relativePath = root.relativize(file).toString().replace('\\', '/');
                if (!rules.matches(relativePath)) {
                    sizes.put(relativePath, attrs.size());
                    tree.add(relativePath, attrs.size(), attrs.lastModifiedTime().toInstant());
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException e) {
                // The default rethrows, and one folder the user may not open (lost+found at
                // the top of an ext4 drive) would then fail the whole check. A directory
                // that cannot be opened lands here before preVisitDirectory sees it, so the
                // ignore rules are asked here too.
                if (file.equals(root) || !rules.matches(root.relativize(file).toString().replace('\\', '/'))) {
                    unreadable.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });
        return sizes;
    }
}
