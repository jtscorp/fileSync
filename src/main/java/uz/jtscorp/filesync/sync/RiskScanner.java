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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class RiskScanner {

    private static final double SIZE_DROP_THRESHOLD = 0.90;
    private static final double MASS_CHANGE_RATIO_THRESHOLD = 0.20;
    private static final int MASS_CHANGE_COUNT_THRESHOLD = 50;
    /** How many unreadable paths the flag names; the rest are only counted. */
    private static final int UNREADABLE_SHOWN = 5;

    /** @param laptopFiles files in the laptop copy after the ignore rules; {@code laptopBytes} their total size */
    public record Scan(List<RiskFlag> flags, long laptopFiles, long laptopBytes) {}

    public List<RiskFlag> scan(Path laptopRoot, Path hddRoot, List<String> ignorePatterns) throws IOException {
        return scanWithTotals(laptopRoot, hddRoot, ignorePatterns).flags();
    }

    public Scan scanWithTotals(Path laptopRoot, Path hddRoot, List<String> ignorePatterns) throws IOException {
        IgnoreRules rules = IgnoreRules.parse(ignorePatterns);

        List<Path> unreadable = new ArrayList<>();
        Map<String, Long> laptopSizes = collectSizes(laptopRoot, rules, unreadable);
        Map<String, Long> hddSizes = collectSizes(hddRoot, rules, unreadable);

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
                laptopSizes.values().stream().mapToLong(Long::longValue).sum());
    }

    private Map<String, Long> collectSizes(Path root, IgnoreRules rules, List<Path> unreadable) throws IOException {
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

    /** The ignore rule types the UI can produce. Must match what Unison ignores, or the counts drift. */
    private record IgnoreRules(List<Pattern> names, List<String> pathPrefixes, List<Pattern> regexes) {

        static IgnoreRules parse(List<String> ignorePatterns) throws IOException {
            List<Pattern> names = new ArrayList<>();
            List<String> pathPrefixes = new ArrayList<>();
            List<Pattern> regexes = new ArrayList<>();
            for (String rule : ignorePatterns) {
                if (rule.startsWith("Name ")) {
                    names.add(Pattern.compile(globToRegex(rule.substring("Name ".length()).strip())));
                } else if (rule.startsWith("Path ")) {
                    pathPrefixes.add(rule.substring("Path ".length()).strip());
                } else if (rule.startsWith("BelowPath ")) {
                    // "BelowPath x" covers everything under x, "Path x" covers x itself
                    // too -- but x is a directory, which contributes no file size, so the
                    // same prefix match serves both.
                    pathPrefixes.add(rule.substring("BelowPath ".length()).strip());
                } else if (rule.startsWith("Regex ")) {
                    String body = rule.substring("Regex ".length()).strip();
                    try {
                        regexes.add(Pattern.compile(body));
                    } catch (PatternSyntaxException e) {
                        throw new IOException(Texts.t("risk.badRegex", body), e);
                    }
                }
            }
            return new IgnoreRules(names, pathPrefixes, regexes);
        }

        boolean matches(String relativePath) {
            // Called for directories as well as files, hence "last component" rather than
            // "file name" -- Unison's "Name" rule matches the last component at any depth.
            String lastComponent = relativePath.contains("/")
                    ? relativePath.substring(relativePath.lastIndexOf('/') + 1)
                    : relativePath;
            return names.stream().anyMatch(p -> p.matcher(lastComponent).matches())
                    || pathPrefixes.stream().anyMatch(prefix ->
                            relativePath.equals(prefix) || relativePath.startsWith(prefix + "/"))
                    // Unison's "Regex" matches the whole path relative to the root.
                    || regexes.stream().anyMatch(p -> p.matcher(relativePath).matches());
        }
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.' -> regex.append("\\.");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }
}
