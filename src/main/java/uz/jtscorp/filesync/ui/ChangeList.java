package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import uz.jtscorp.filesync.sync.FileDiff.FileFacts;
import uz.jtscorp.filesync.sync.PlanInterpreter;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.ui.CheckResult.FilePair;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.function.Function;

/**
 * Which of a plan's changes and conflicts the review screen lists, and in what order. Free
 * of JavaFX so the filters and the four orders have a plain test.
 */
final class ChangeList {

    enum Filter {
        ALL("filter.all"), TO_HDD("filter.hdd"), TO_LAPTOP("filter.laptop"), DELETES("filter.deletes"),
        OVERWRITES("filter.overwrites");

        private final String key;

        Filter(String key) {
            this.key = key;
        }

        String label() {
            return Texts.t(key);
        }

        boolean matches(SyncAction action) {
            return this == ALL || this == of(action);
        }

        /** A deletion is its own kind whichever side it lands on: it is what a reader looks for. */
        static Filter of(SyncAction action) {
            if (action.actionType() == SyncAction.ActionType.DELETED) {
                return DELETES;
            }
            if (action.actionType() == SyncAction.ActionType.CHANGED && action.origin() == SyncAction.Origin.MODE) {
                // Forced over a different copy: a reader wants these apart from ordinary updates.
                return OVERWRITES;
            }
            return action.direction() == SyncAction.Direction.LAPTOP_TO_HDD ? TO_HDD : TO_LAPTOP;
        }
    }

    /** A conflict has no direction until one is picked, so its filter is what was decided. */
    enum ConflictFilter {
        ALL("filter.all"), UNDECIDED("filter.undecided"), LAPTOP("side.laptop"), HDD("side.hdd"),
        MERGE("resolution.merge");

        private final String key;

        ConflictFilter(String key) {
            this.key = key;
        }

        String label() {
            return Texts.t(key);
        }

        boolean matches(Resolution decision) {
            return this == ALL || this == of(decision);
        }

        static ConflictFilter of(Resolution decision) {
            return switch (decision) {
                case SKIP -> UNDECIDED;
                case LAPTOP -> LAPTOP;
                case HDD -> HDD;
                case MERGE -> MERGE;
            };
        }
    }

    enum Sort {
        PATH("sort.path"), NAME("sort.name"), SIZE("sort.size"), DATE("sort.date");

        private final String key;

        Sort(String key) {
            this.key = key;
        }

        /** The arrow marks the order in use; a second click on it turns the order around. */
        String label(boolean active, boolean reversed) {
            String label = Texts.t(key);
            return active ? label + (reversed ? " ↑" : " ↓") : label;
        }
    }

    private ChangeList() {}

    static long count(List<SyncAction> changes, Filter filter) {
        return changes.stream().filter(filter::matches).count();
    }

    static long count(List<SyncAction> conflicts, Function<String, Resolution> decision,
                      ConflictFilter filter) {
        return conflicts.stream().filter(a -> filter.matches(decision.apply(a.relativePath()))).count();
    }

    /** {@link #shownEntries} with no moves: one filter and order, so its tests cover the live path. */
    static List<SyncAction> shown(List<SyncAction> changes, Function<String, FilePair> facts,
                                  Filter filter, Sort sort, boolean reversed) {
        return shownEntries(changes, List.of(), facts, filter, sort, reversed).stream().map(Entry::action).toList();
    }

    /** A list entry: one change, or a recognised move shown as a single line. */
    record Entry(SyncAction action, PlanInterpreter.Rename rename) {}

    /**
     * Like {@link #shown} but a rename is one entry: its {@code to} row stands for it and the
     * {@code from} row is hidden. It sorts and filters as that {@code to} row.
     */
    static List<Entry> shownEntries(List<SyncAction> changes, List<PlanInterpreter.Rename> renames,
                                    Function<String, FilePair> facts, Filter filter, Sort sort,
                                    boolean reversed) {
        Set<String> hidden = renames.stream().map(PlanInterpreter.Rename::from).collect(Collectors.toSet());
        Map<String, PlanInterpreter.Rename> byTarget = renames.stream()
                .collect(Collectors.toMap(PlanInterpreter.Rename::to, r -> r, (a, b) -> a));
        Comparator<SyncAction> order = order(facts, sort, reversed);
        return changes.stream()
                .filter(a -> !hidden.contains(a.relativePath()))
                .filter(filter::matches)
                .map(a -> new Entry(a, byTarget.get(a.relativePath())))
                .sorted(Comparator.comparing(Entry::action, order))
                .toList();
    }

    /** @param decision what was picked for a conflict's path; {@code SKIP} when nothing was */
    static List<SyncAction> shownConflicts(List<SyncAction> conflicts, Function<String, FilePair> facts,
                                           Function<String, Resolution> decision, ConflictFilter filter,
                                           Sort sort, boolean reversed) {
        return conflicts.stream().filter(a -> filter.matches(decision.apply(a.relativePath())))
                .sorted(order(facts, sort, reversed)).toList();
    }

    /**
     * Paths and names run A to Z, sizes largest first, dates newest first; {@code reversed}
     * turns that around. A row's size and date are the larger and the later of its two copies.
     */
    private static Comparator<SyncAction> order(Function<String, FilePair> facts, Sort sort, boolean reversed) {
        Comparator<SyncAction> order = switch (sort) {
            case PATH -> Comparator.comparing(SyncAction::relativePath);
            case NAME -> Comparator.comparing(a -> ReviewText.splitPath(a.relativePath()).name());
            case SIZE -> Comparator.comparingLong(
                    (SyncAction a) -> size(facts.apply(a.relativePath()))).reversed();
            case DATE -> Comparator.comparing(
                    (SyncAction a) -> modified(facts.apply(a.relativePath()))).reversed();
        };
        return reversed ? order.reversed() : order;
    }

    private static long size(FilePair pair) {
        return Math.max(pair.laptop().size(), pair.hdd().size());
    }

    private static Instant modified(FilePair pair) {
        Instant laptop = modified(pair.laptop());
        Instant hdd = modified(pair.hdd());
        return laptop.isAfter(hdd) ? laptop : hdd;
    }

    private static Instant modified(FileFacts facts) {
        return facts.modified() == null ? Instant.MIN : facts.modified();
    }
}
