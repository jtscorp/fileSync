package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import uz.jtscorp.filesync.sync.RiskFlag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * What must be decided before Apply. Each item needs its own decision: a file can be
 * acknowledged or skipped, an item about the whole run only acknowledged.
 */
final class RiskGate {

    static final String EXIT_ONE_KEY = "#unison-exit-1";

    /** @param path null for an item that concerns the whole run rather than one file */
    record Item(String key, String path, String title, String reason) {
        boolean isFile() {
            return path != null;
        }
    }

    private final List<Item> items;
    private final Set<String> acknowledged = new HashSet<>();
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
        List<Item> items = new ArrayList<>();
        int batchIndex = 0;
        for (RiskFlag flag : risks) {
            if (flag.scope() != RiskFlag.Scope.FILE) {
                String title = Texts.t(flag.scope() == RiskFlag.Scope.UNREADABLE
                        ? "gate.unreadable.title" : "gate.batch.title");
                items.add(new Item("#batch-" + batchIndex++, null, title, flag.reason()));
            }
        }
        if (unisonExitCode == 1 && !planHasConflicts) {
            items.add(new Item(EXIT_ONE_KEY, null, Texts.t("gate.exit1.title"),
                    Texts.t("gate.exit1.reason")));
        }
        risks.stream()
                .filter(flag -> flag.scope() == RiskFlag.Scope.FILE)
                .sorted(Comparator.comparing(flag -> flag.relativePath().orElseThrow()))
                .forEach(flag -> {
                    String path = flag.relativePath().orElseThrow();
                    items.add(new Item(path, path, path, flag.reason()));
                });
        return new RiskGate(items);
    }

    List<Item> items() {
        return items;
    }

    long fileCount() {
        return items.stream().filter(Item::isFile).count();
    }

    boolean isAcknowledged(String key) {
        return acknowledged.contains(key);
    }

    void setAcknowledged(String key, boolean value) {
        if (items.stream().noneMatch(item -> item.key().equals(key))) {
            throw new IllegalArgumentException("No such gate item: " + key);
        }
        if (value) {
            acknowledged.add(key);
            skipped.remove(key);
        } else {
            acknowledged.remove(key);
        }
    }

    boolean isSkipped(String key) {
        return skipped.contains(key);
    }

    /** Only a file can be left out of a run; a mass change is not something to skip past. */
    void setSkipped(String key, boolean value) {
        if (items.stream().noneMatch(item -> item.isFile() && item.key().equals(key))) {
            throw new IllegalArgumentException("Not a file gate item: " + key);
        }
        if (value) {
            skipped.add(key);
            acknowledged.remove(key);
        } else {
            skipped.remove(key);
        }
    }

    /** The files Apply must not touch, in the order they are listed. */
    List<String> skippedPaths() {
        return items.stream().map(Item::key).filter(skipped::contains).toList();
    }

    /** Whether the scanner flagged this very file -- its skip is then the gate's to keep. */
    boolean isFileItem(String path) {
        return items.stream().anyMatch(item -> path.equals(item.path()));
    }

    int pendingCount() {
        return items.size() - acknowledged.size() - skipped.size();
    }

    /** Every item decided: acknowledged, or -- for a file -- skipped. */
    boolean allAcknowledged() {
        return pendingCount() == 0;
    }

    /** Whether this path still carries an undecided risk -- what the inline ⚠ tag shows. */
    boolean isPendingFile(String path) {
        return isFileItem(path) && !acknowledged.contains(path) && !skipped.contains(path);
    }
}
