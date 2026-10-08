package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import uz.jtscorp.filesync.sync.FileDiff.FileFacts;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.ui.CheckResult.FilePair;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.function.Function;

/**
 * Which of a plan's changes the review screen lists, and in what order. Free of JavaFX so
 * the filter and the four orders have a plain test.
 */
final class ChangeList {

    enum Filter {
        ALL("filter.all"), TO_HDD("filter.hdd"), TO_LAPTOP("filter.laptop"), DELETES("filter.deletes");

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
            return action.direction() == SyncAction.Direction.LAPTOP_TO_HDD ? TO_HDD : TO_LAPTOP;
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

    /**
     * Paths and names run A to Z, sizes largest first, dates newest first; {@code reversed}
     * turns that around. A row's size and date are the larger and the later of its two copies.
     */
    static List<SyncAction> shown(List<SyncAction> changes, Function<String, FilePair> facts,
                                  Filter filter, Sort sort, boolean reversed) {
        Comparator<SyncAction> order = switch (sort) {
            case PATH -> Comparator.comparing(SyncAction::relativePath);
            case NAME -> Comparator.comparing(a -> ReviewText.splitPath(a.relativePath()).name());
            case SIZE -> Comparator.comparingLong(
                    (SyncAction a) -> size(facts.apply(a.relativePath()))).reversed();
            case DATE -> Comparator.comparing(
                    (SyncAction a) -> modified(facts.apply(a.relativePath()))).reversed();
        };
        return changes.stream().filter(filter::matches)
                .sorted(reversed ? order.reversed() : order).toList();
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
