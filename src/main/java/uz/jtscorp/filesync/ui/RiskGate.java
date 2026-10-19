package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import uz.jtscorp.filesync.sync.ContentSignals;
import uz.jtscorp.filesync.sync.RiskFlag;
import uz.jtscorp.filesync.sync.SyncAction;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * What must be decided before Apply. Each item needs its own decision: a file can be
 * acknowledged or skipped, an item about the whole run only acknowledged, and a suspicious
 * group acknowledged or skipped as a whole -- skipping is the safe direction.
 */
final class RiskGate {

    static final String EXIT_ONE_KEY = "#unison-exit-1";
    static final String IRREVERSIBLE_KEY = "#irreversible";
    static final String BASELINE_KEY = "#baseline";

    /** Where an item comes from, so its card can say so and act on a click. */
    enum Kind { PLAIN, MASS_CHANGE, BASELINE, CLUSTER, CONTENT }

    /**
     * @param path    null for an item that concerns the whole run rather than one file
     * @param members a group's files; empty for every other item
     * @param finding what a file's bytes say, for a content signal; null otherwise
     */
    record Item(String key, String path, String title, String reason, Kind kind, List<String> members,
                ContentSignals.Finding finding) {
        boolean isFile() {
            return path != null;
        }

        boolean isGroup() {
            return !members.isEmpty();
        }
    }

    private final List<Item> items;
    private final Set<String> acknowledged = new HashSet<>();
    /** Paths left out of this Apply at the gate: flagged files and the files of a group. */
    private final Set<String> skipped = new HashSet<>();

    private RiskGate(List<Item> items) {
        this.items = List.copyOf(items);
    }

    /**
     * @param unisonExitCode   the dry run's normalized code
     * @param planHasConflicts conflicts explain a code 1; without them Unison skipped
     *                         something the plan does not show
     */
    static RiskGate from(List<RiskFlag> risks, int unisonExitCode, boolean planHasConflicts) {
        return from(risks, unisonExitCode, planHasConflicts, 0, 0);
    }

    static RiskGate from(List<RiskFlag> risks, int unisonExitCode, boolean planHasConflicts,
                         int irreversibleDeletes, int irreversibleOverwrites) {
        return from(risks, unisonExitCode, planHasConflicts, irreversibleDeletes, irreversibleOverwrites,
                List.of(), ZoneId.systemDefault());
    }

    static RiskGate from(List<RiskFlag> risks, int unisonExitCode, boolean planHasConflicts,
                         int irreversibleDeletes, int irreversibleOverwrites,
                         List<ChangeClusters.Cluster> clusters, ZoneId zone) {
        return from(risks, unisonExitCode, planHasConflicts, irreversibleDeletes, irreversibleOverwrites,
                clusters, null, zone);
    }

    /**
     * @param irreversibleDeletes    files the sync mode or manifest makes Apply delete
     * @param irreversibleOverwrites files it makes Apply overwrite; no copy is kept of
     *                               either, so this acknowledgement is the last safety net
     * @param clusters               the plan's groups; only a suspicious one becomes an item
     * @param baseline               this profile's normal; with enough history it replaces the
     *                               scanner's fixed mass-change rule. Null leaves that rule as it is
     * @param zone                   for the time a group's reason names
     */
    static RiskGate from(List<RiskFlag> risks, int unisonExitCode, boolean planHasConflicts,
                         int irreversibleDeletes, int irreversibleOverwrites,
                         List<ChangeClusters.Cluster> clusters, Baseline baseline, ZoneId zone) {
        List<Item> items = new ArrayList<>();
        boolean byBaseline = baseline != null && baseline.enough();
        int batchIndex = 0;
        for (RiskFlag flag : risks) {
            if (flag.scope() == RiskFlag.Scope.UNREADABLE) {
                items.add(new Item("#batch-" + batchIndex++, null, Texts.t("gate.unreadable.title"), flag.reason(),
                        Kind.PLAIN, List.of(), null));
            } else if (flag.scope() == RiskFlag.Scope.BATCH && !byBaseline) {
                // The fixed rule, while the profile's normal is still being learned -- and it says so.
                String reason = baseline == null ? flag.reason() : sentences(List.of(flag.reason(),
                        Texts.t("gate.batch.cold", baseline.history().size(), Baseline.HISTORY_MIN)));
                items.add(new Item("#batch-" + batchIndex++, null, Texts.t("gate.batch.title"), reason,
                        Kind.MASS_CHANGE, List.of(), null));
            }
        }
        if (byBaseline && baseline.odd()) {
            items.add(new Item(BASELINE_KEY, null, Texts.t("gate.baseline.title"),
                    Texts.t("gate.baseline.reason", baseline.low(), baseline.high(), baseline.history().size(),
                            baseline.today(), baseline.limit()),
                    Kind.BASELINE, List.of(), null));
        }
        if (unisonExitCode == 1 && !planHasConflicts) {
            items.add(new Item(EXIT_ONE_KEY, null, Texts.t("gate.exit1.title"), Texts.t("gate.exit1.reason"),
                    Kind.PLAIN, List.of(), null));
        }
        if (irreversibleDeletes > 0 || irreversibleOverwrites > 0) {
            items.add(new Item(IRREVERSIBLE_KEY, null, Texts.t("gate.irreversible.title"),
                    Texts.t("gate.irreversible.reason", irreversibleDeletes, irreversibleOverwrites),
                    Kind.PLAIN, List.of(), null));
        }
        for (ChangeClusters.Cluster cluster : clusters) {
            if (cluster.suspicious()) {
                boolean toHdd = cluster.renameDirection() == SyncAction.Direction.LAPTOP_TO_HDD;
                items.add(new Item(cluster.id(), null,
                        Texts.t("gate.group.title", cluster.renames(), cluster.renameExt()),
                        Texts.t(toHdd ? "gate.group.reason.toHdd" : "gate.group.reason.toLaptop",
                                ReviewText.groupName(cluster.prefix()),
                                ReviewText.groupWhen(cluster.first(), cluster.last(), zone)),
                        Kind.CLUSTER, cluster.paths(), null));
            }
        }
        // One item per file, however many flags it has: one decision, every reason in it.
        Map<String, List<RiskFlag>> byFile = risks.stream()
                .filter(flag -> flag.scope() == RiskFlag.Scope.FILE)
                .collect(Collectors.groupingBy(flag -> flag.relativePath().orElseThrow(), TreeMap::new,
                        Collectors.toList()));
        byFile.forEach((path, flags) -> {
            ContentSignals.Finding finding = flags.stream().map(RiskFlag::content).filter(Objects::nonNull)
                    .findFirst().orElse(null);
            String reason = sentences(flags.stream().map(RiskFlag::reason).toList());
            items.add(new Item(path, path, path, reason, finding == null ? Kind.PLAIN : Kind.CONTENT, List.of(),
                    finding));
        });
        return new RiskGate(items);
    }

    /** One reason after another; a reason that does not end a sentence gets the full stop it lacks. */
    static String sentences(List<String> reasons) {
        StringBuilder text = new StringBuilder();
        for (String reason : reasons) {
            if (!text.isEmpty()) {
                char last = text.charAt(text.length() - 1);
                text.append(last == '.' || last == '!' || last == '?' ? " " : ". ");
            }
            text.append(reason.strip());
        }
        return text.toString();
    }

    List<Item> items() {
        return items;
    }

    Optional<Item> item(String key) {
        return items.stream().filter(item -> item.key().equals(key)).findFirst();
    }

    long fileCount() {
        return items.stream().filter(Item::isFile).count();
    }

    boolean isAcknowledged(String key) {
        return acknowledged.contains(key);
    }

    /**
     * Acknowledging a group also brings its files back into the Apply -- but a flagged file
     * among them then waits for its own decision: one click never acknowledges two items.
     */
    void setAcknowledged(String key, boolean value) {
        Item item = item(key).orElseThrow(() -> new IllegalArgumentException("No such gate item: " + key));
        if (value) {
            acknowledged.add(key);
            if (item.isFile()) {
                skipped.remove(key);
            }
            item.members().forEach(skipped::remove);
        } else {
            acknowledged.remove(key);
        }
    }

    /** Whether this path is left out of the Apply at the gate. */
    boolean isSkipped(String path) {
        return skipped.contains(path);
    }

    /** Only a file can be left out of a run; a mass change is not something to skip past. */
    void setSkipped(String key, boolean value) {
        if (items.stream().noneMatch(item -> item.isFile() && item.key().equals(key))) {
            throw new IllegalArgumentException("Not a file gate item: " + key);
        }
        skipFile(key, value);
    }

    /** Skipping a group leaves every file in it out, the flagged ones included. */
    void setGroupSkipped(String key, boolean value) {
        Item group = item(key).filter(Item::isGroup)
                .orElseThrow(() -> new IllegalArgumentException("Not a group gate item: " + key));
        if (value) {
            acknowledged.remove(key);
        }
        group.members().forEach(path -> skipFile(path, value));
    }

    boolean isGroupSkipped(String key) {
        return item(key).filter(Item::isGroup).map(group -> skipped.containsAll(group.members())).orElse(false);
    }

    /** Whether this path's skip is the gate's to keep: a flagged file, or a file of a group. */
    boolean keepsSkip(String path) {
        return isFileItem(path) || items.stream().anyMatch(item -> item.members().contains(path));
    }

    /** One file left out or brought back, from the list rather than from its card. */
    void setPathSkipped(String path, boolean value) {
        if (!keepsSkip(path)) {
            throw new IllegalArgumentException("Not a gate path: " + path);
        }
        skipFile(path, value);
    }

    /** A flagged file is applied or skipped, never both. */
    private void skipFile(String path, boolean value) {
        if (value) {
            skipped.add(path);
            acknowledged.remove(path);
        } else {
            skipped.remove(path);
        }
    }

    /** The files Apply must not touch: flagged files in the order they are listed, then the groups' files. */
    List<String> skippedPaths() {
        Set<String> paths = new LinkedHashSet<>();
        items.stream().filter(Item::isFile).map(Item::key).filter(skipped::contains).forEach(paths::add);
        items.stream().flatMap(item -> item.members().stream()).filter(skipped::contains).forEach(paths::add);
        return List.copyOf(paths);
    }

    /** Whether the scanner flagged this very file -- its skip is then the gate's to keep. */
    boolean isFileItem(String path) {
        return items.stream().anyMatch(item -> path.equals(item.path()));
    }

    private boolean isDecided(Item item) {
        if (acknowledged.contains(item.key())) {
            return true;
        }
        if (item.isFile()) {
            return skipped.contains(item.path());
        }
        return item.isGroup() && skipped.containsAll(item.members());
    }

    int pendingCount() {
        return (int) items.stream().filter(item -> !isDecided(item)).count();
    }

    /** Every item decided: acknowledged, or -- for a file or a group -- skipped. */
    boolean allAcknowledged() {
        return pendingCount() == 0;
    }

    /** Whether this path still carries an undecided risk -- what the inline ⚠ tag shows. */
    boolean isPendingFile(String path) {
        return isFileItem(path) && !acknowledged.contains(path) && !skipped.contains(path);
    }
}
