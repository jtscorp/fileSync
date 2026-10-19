package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import uz.jtscorp.filesync.sync.ContentSignals;
import uz.jtscorp.filesync.sync.FileDiff.FileFacts;
import uz.jtscorp.filesync.sync.PlanInterpreter;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.SyncAction.Direction;
import uz.jtscorp.filesync.ui.CheckResult.FilePair;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Every sentence the review screen computes. Kept apart from the nodes that show them so
 * the wording -- which the user reads to decide what gets overwritten -- has tests.
 */
final class ReviewText {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private ReviewText() {}

    /** @param dir includes its trailing slash, or is empty for a top-level file */
    record PathParts(String dir, String name) {}

    static PathParts splitPath(String path) {
        int slash = path.lastIndexOf('/');
        return new PathParts(path.substring(0, slash + 1), path.substring(slash + 1));
    }

    static String size(FileFacts facts) {
        if (!facts.exists()) {
            return "—";
        }
        return facts.directory() ? Texts.t("review.folder") : SizeFormat.format(facts.size());
    }

    /** "old → new" for the copy that is about to be written; a creation shows one size. */
    static String sizes(SyncAction action, FilePair pair) {
        boolean toHdd = action.direction() == Direction.LAPTOP_TO_HDD;
        FileFacts source = toHdd ? pair.laptop() : pair.hdd();
        FileFacts destination = toHdd ? pair.hdd() : pair.laptop();
        return switch (action.actionType()) {
            case NEW -> size(source);
            case DELETED -> size(destination) + " → —";
            default -> size(destination) + " → " + size(source);
        };
    }

    static String direction(SyncAction action) {
        boolean toHdd = action.direction() == Direction.LAPTOP_TO_HDD;
        if (action.actionType() == ActionType.DELETED) {
            return Texts.t(toHdd ? "dir.hddDelete" : "dir.laptopDelete");
        }
        if (action.actionType() == ActionType.CHANGED && action.origin() == SyncAction.Origin.MODE) {
            // The mode forces this copy over the other one, however it differs.
            return Texts.t(toHdd ? "dir.hddOverwrite" : "dir.laptopOverwrite");
        }
        return Texts.t(toHdd ? "dir.toHdd" : "dir.toLaptop");
    }

    /** What a folder about to be deleted holds: "27 fayl · 120 KB". */
    static String folderSummary(CheckResult.FolderContents folder) {
        return Texts.n("review.folderContents", folder.files().size(), SizeFormat.format(folder.bytes()));
    }

    /**
     * A folder row's line in the changes group: how many files, how many of each kind in
     * the filter chips' order, and how many sit this Apply out. A kind with none is left out.
     */
    static String folderMeta(List<SyncAction> files, Predicate<String> skipped) {
        List<String> parts = new ArrayList<>();
        parts.add(Texts.n("review.tree.files", files.size()));
        for (ChangeList.Filter kind : ChangeList.Filter.values()) {
            long count = files.stream().filter(a -> ChangeList.Filter.of(a) == kind).count();
            if (count > 0) {
                parts.add(Texts.t(treeKey(kind), count));
            }
        }
        long skippedCount = files.stream().filter(a -> skipped.test(a.relativePath())).count();
        if (skippedCount > 0) {
            parts.add(Texts.t("review.tree.skipped", skippedCount));
        }
        return String.join(" · ", parts);
    }

    private static String treeKey(ChangeList.Filter kind) {
        return switch (kind) {
            case TO_HDD -> "review.tree.toHdd";
            case TO_LAPTOP -> "review.tree.toLaptop";
            case DELETES -> "review.tree.deletes";
            case OVERWRITES -> "review.tree.overwrites";
            case ALL, ODD -> throw new IllegalArgumentException("Not a kind: " + kind);
        };
    }

    /** A folder row's line in the conflicts group: a conflict has no kind until it is decided. */
    static String conflictFolderMeta(int files, long undecided) {
        String count = Texts.n("review.tree.files", files);
        return undecided == 0 ? count : count + " · " + Texts.t("review.tree.undecided", undecided);
    }

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd.MM");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");

    /** When a group happened: "07.10, 02:14 da" for one minute, "06.10, 11:34–11:40" for a span. */
    static String groupWhen(Instant first, Instant last, ZoneId zone) {
        String day = DAY.withZone(zone).format(first);
        String from = CLOCK.withZone(zone).format(first);
        String to = CLOCK.withZone(zone).format(last);
        return from.equals(to) ? Texts.t("review.group.when.at", day, from)
                : Texts.t("review.group.when.range", day, from, to);
    }

    /** A group is named by its common folder; files with none in common are under the root. */
    static String groupName(String prefix) {
        return prefix.isEmpty() ? "/" : prefix;
    }

    /**
     * What a group is made of: up to three kinds by count, each with its extension
     * ("64 .class → Zaxira"), then the total size of the larger copies.
     */
    static String groupMakeup(List<SyncAction> actions, Function<String, FilePair> facts) {
        record Part(ChangeList.Filter kind, String ext) {}
        Map<Part, Long> counts = actions.stream().collect(Collectors.groupingBy(
                a -> new Part(ChangeList.Filter.of(a), extension(a.relativePath())), LinkedHashMap::new,
                Collectors.counting()));
        List<String> parts = new ArrayList<>(counts.entrySet().stream()
                .sorted(Map.Entry.<Part, Long>comparingByValue().reversed())
                .limit(3)
                .map(e -> Texts.t(treeKey(e.getKey().kind()),
                        e.getKey().ext().isEmpty() ? e.getValue().toString() : e.getValue() + " " + e.getKey().ext()))
                .toList());
        parts.add(SizeFormat.format(totalSize(actions, facts)));
        return String.join(" · ", parts);
    }

    /** The larger copy of each file, as the size order counts it. */
    static long totalSize(List<SyncAction> actions, Function<String, FilePair> facts) {
        return actions.stream().map(a -> facts.apply(a.relativePath())).filter(Objects::nonNull)
                .mapToLong(pair -> Math.max(pair.laptop().size(), pair.hdd().size())).sum();
    }

    static String groupCount(int files, long skipped) {
        String count = Texts.n("review.tree.files", files);
        return skipped == 0 ? count : count + " · " + Texts.t("review.tree.skipped", skipped);
    }

    private static String extension(String path) {
        String name = splitPath(path).name();
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot);
    }

    /** Two decimals, with the comma uz and ru write and the point en writes: "4,61". */
    static String decimal(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        return Texts.language() == Texts.Language.EN ? text : text.replace('.', ',');
    }

    /** The gate's reason for a content signal: what the bad copy is, and which copy it would replace. */
    static String signalReason(ContentSignals.Finding finding) {
        String bad = Texts.t(finding.laptopBad() ? "side.laptop" : "side.hdd");
        String good = Texts.t(finding.laptopBad() ? "side.hdd" : "side.laptop");
        return switch (finding.signal()) {
            case ENTROPY -> Texts.t("sig.entropy", bad, good, decimal(finding.goodEntropy()),
                    decimal(finding.badEntropy()));
            case MAGIC -> Texts.t("sig.magic", bad, good, finding.ext(), finding.ext().toUpperCase(Locale.ROOT),
                    hex(finding.badHead()));
            case ZEROS -> Texts.t("sig.zeros", bad, good, SizeFormat.format(finding.size()));
        };
    }

    /** A content signal in a few words, beside the path on its card and under its row. */
    static String signalShort(ContentSignals.Finding finding) {
        return switch (finding.signal()) {
            case ENTROPY -> Texts.t("sig.short.entropy", decimal(finding.goodEntropy()), decimal(finding.badEntropy()));
            case MAGIC -> Texts.t("sig.short.magic", finding.ext(), finding.ext().toUpperCase(Locale.ROOT));
            case ZEROS -> Texts.t("sig.short.zeros");
        };
    }

    /** One row of the detail panel's analysis card: what was measured, and its value on each side. */
    record AnalysisRow(String label, String laptop, String hdd) {}

    static AnalysisRow analysis(ContentSignals.Finding finding) {
        String label;
        String bad;
        String good;
        switch (finding.signal()) {
            case ENTROPY -> {
                label = Texts.t("diff.an.entropy");
                bad = decimal(finding.badEntropy());
                good = decimal(finding.goodEntropy());
            }
            case MAGIC -> {
                label = Texts.t("diff.an.magic");
                bad = headText(finding.badHead());
                good = headText(finding.goodHead());
            }
            default -> {
                label = Texts.t("diff.an.zeros");
                bad = Math.round(finding.badZeroShare() * 100) + "%";
                good = Math.round(finding.goodZeroShare() * 100) + "%";
            }
        }
        return finding.laptopBad() ? new AnalysisRow(label, bad, good) : new AnalysisRow(label, good, bad);
    }

    /** A header as a reader recognises it: "%PDF-1.7" when it is text, "d4 1f 8a 3c" when it is not. */
    private static String headText(byte[] head) {
        for (byte b : head) {
            if (b < 0x20 || b > 0x7e) {
                return hex(head);
            }
        }
        return new String(head, StandardCharsets.US_ASCII);
    }

    /** The first four bytes in hex: enough to tell one header from another. */
    private static String hex(byte[] bytes) {
        List<String> cells = new ArrayList<>();
        for (int i = 0; i < Math.min(4, bytes.length); i++) {
            cells.add(String.format("%02x", bytes[i] & 0xff));
        }
        return String.join(" ", cells);
    }

    /** The check's fourth stage when it is done: "3 ta kontent belgisi · 2 ta guruh". */
    static String analysisDone(int signals, int groups) {
        return Texts.n("check.signals", signals) + " · " + Texts.n("check.groups", groups);
    }

    /** The same with the model's count, while the model is on: "· 4 ta g'ayrioddiy". */
    static String analysisDone(int signals, int groups, long odd) {
        return analysisDone(signals, groups) + " · " + Texts.n("check.odd", odd);
    }

    /** One of the model's reasons as a person can check it: "kechasi 03:41 da o'zgargan". */
    static String reason(Oddity.Reason reason) {
        return switch (reason.why()) {
            case NIGHT -> Texts.t("why.night", reason.value());
            case STALE -> Texts.n("why.stale", Long.parseLong(reason.value()));
            case RARE_EXT -> Texts.t("why.rareExt", reason.value());
            case PAIR -> Texts.t("why.pair");
        };
    }

    static String reasons(Oddity.Mark mark, String separator) {
        return String.join(separator, mark.reasons().stream().map(ReviewText::reason).toList());
    }

    /** A marked change's line under its row: "◆ G'ayrioddiy 0,68 · kechasi 03:41 da o'zgargan, 6 yil tegilmagan edi". */
    static String oddLine(Oddity.Mark mark) {
        return Texts.t("review.odd.line", decimal(mark.score()), reasons(mark, ", "));
    }

    /** Why the model says nothing yet: "... 10 ta tekshiruvdan keyin yoqiladi (hozir 4 ta)". */
    static String modelCold(int checks) {
        return Texts.t("diff.an.cold", Baseline.HISTORY_MIN, checks);
    }

    static String rename(PlanInterpreter.Rename rename) {
        return Texts.t("review.rename", rename.from(), rename.to());
    }

    static String actionLine(SyncAction action) {
        String line = Texts.t(action.direction() == Direction.LAPTOP_TO_HDD ? "action.toHdd" : "action.toLaptop");
        if (action.actionType() == ActionType.DELETED) {
            return Texts.t("action.delete", line);
        }
        boolean overwrite = action.actionType() == ActionType.CHANGED && action.origin() == SyncAction.Origin.MODE;
        return overwrite ? Texts.t("action.overwrite", line) : line;
    }

    /**
     * The size drop in a few characters. Follows the plan's direction when there is one;
     * a conflict or a file Unison did not list has none, so it reads larger → smaller.
     */
    static String riskShort(SyncAction action, FilePair pair) {
        if (action != null && action.actionType() == ActionType.CHANGED) {
            return sizes(action, pair);
        }
        boolean laptopLarger = pair.laptop().size() >= pair.hdd().size();
        return size(laptopLarger ? pair.laptop() : pair.hdd()) + " → "
                + size(laptopLarger ? pair.hdd() : pair.laptop());
    }

    static String statsLabel(long toHdd, long toLaptop) {
        return Texts.t("review.stat.changes", toHdd, toLaptop);
    }

    /** What will happen to a risky item, shown beside it. */
    static String riskState(boolean acknowledged, boolean skipped) {
        return Texts.t(acknowledged ? "risk.state.ack" : skipped ? "risk.state.skip" : "risk.state.pending");
    }

    /** Stands in for the direction on a row the user left out of this Apply. */
    static String skippedDirection() {
        return Texts.t("dir.skipped");
    }

    /** The bulk bar's line: an invitation until something is ticked, then the count. */
    static String bulkText(int picked) {
        return picked == 0 ? Texts.t("review.selectAll") : Texts.n("review.picked", picked);
    }

    static String applySummary(int changes, int resolved, int untouched) {
        StringBuilder summary = new StringBuilder(Texts.n("summary.changes", changes));
        if (resolved > 0) {
            summary.append(" · ").append(Texts.n("summary.resolved", resolved));
        }
        if (untouched > 0) {
            summary.append(" · ").append(Texts.n("summary.untouched", untouched));
        }
        return summary.toString();
    }

    static String gateText(int pending) {
        return pending == 0 ? "" : Texts.n("gate.pending", pending);
    }

    static String riskTitle(int pending) {
        return pending == 0 ? Texts.t("risk.title.done") : Texts.n("risk.title.pending", pending);
    }

    /** Null when merging is available; otherwise why it is not. */
    static String mergeTooltip(boolean bothAreText, boolean profileKeepsBackups, boolean diff3Found) {
        if (!diff3Found) {
            // Unison would run diff3, fail to start it and leave the conflict as it was.
            return Texts.t("merge.noDiff3");
        }
        if (!bothAreText) {
            return Texts.t("merge.textOnly");
        }
        return profileKeepsBackups ? null : Texts.t("merge.off");
    }

    static String time(FileFacts facts, ZoneId zone) {
        return facts.modified() == null ? "—" : TIME.withZone(zone).format(facts.modified());
    }

    /** -1 when the laptop copy is newer, 1 when the HDD copy is, 0 when there is nothing to compare. */
    static int newerSide(FilePair pair) {
        if (pair.laptop().modified() == null || pair.hdd().modified() == null) {
            return 0;
        }
        return -Integer.signum(pair.laptop().modified().compareTo(pair.hdd().modified()));
    }

    static String typeLetter(ActionType type) {
        return switch (type) {
            case NEW -> "N";
            case CHANGED -> "M";
            case DELETED -> "D";
            case CONFLICT -> "C";
        };
    }
}
