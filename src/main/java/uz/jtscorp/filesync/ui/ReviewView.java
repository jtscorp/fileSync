package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import uz.jtscorp.filesync.config.SyncMode;
import uz.jtscorp.filesync.config.SyncProfile;
import uz.jtscorp.filesync.sync.PlanInterpreter;
import uz.jtscorp.filesync.sync.SyncAction;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntToDoubleFunction;

/**
 * Step 3: the plan, the risks that gate it, and the diff of the selected file. The
 * resolutions and the skipped changes belong to {@link MainView}.
 */
public class ReviewView {

    interface Listener {
        void onBackToSetup();

        void onApply();

        /** The panel was dragged or reset to this width; the caller keeps it for the next launch. */
        void onDetailResized(double width);
    }

    /** The list is one scroll of unlike things; each row type knows how it is drawn. */
    private sealed interface Row {}
    private record StatsRow() implements Row {}
    private record RiskHeadRow() implements Row {}
    private record RiskRow(RiskGate.Item item, boolean last) implements Row {}
    /**
     * {@code first}/{@code last}: where the group's card gets its top and bottom edge.
     * {@code rename} is set when the row stands for a move; {@code action} is then its
     * {@code to} row, and that path is what the row's tick and skip act on. {@code indent}
     * steps it in past the card's padding; {@code name} is what it shows -- the file name in
     * the tree, the path below the group's folder in a group.
     */
    private record ActionRow(SyncAction action, PlanInterpreter.Rename rename, double indent, String name,
                             boolean first, boolean last) implements Row {}
    /**
     * A group of the changes that happened together, or the rest of them ({@code cluster}
     * null). {@code entries} are its rows the filter shows, in the list's order.
     */
    private record GroupRow(String id, ChangeClusters.Cluster cluster, List<ChangeList.Entry> entries,
                            boolean last) implements Row {}
    /** A folder of the tree; {@code conflicts} says which group it belongs to, and so which ticks and state. */
    private record FolderRow(ChangeTree.Folder folder, boolean conflicts, boolean last) implements Row {}
    private record EmptyRow() implements Row {}
    /** The changes group's title, with what narrows and orders its rows. */
    private record ChangesHeadRow() implements Row {}
    /** The top edge of the changes card: select all, and what to do with the ticked rows. */
    private record BulkRow() implements Row {}
    /** The conflicts group's title, with what narrows and orders its rows. */
    private record ConflictsHeadRow() implements Row {}
    /** The top edge of the conflicts card: select all, and which side the ticked rows keep. */
    private record ConflictBulkRow() implements Row {}
    /** The bottom edge of a card when its filter leaves it no row. */
    private record FilterEmptyRow(String text) implements Row {}

    /** The group of the changes that are in no group; it is listed last. */
    private static final String OTHER_GROUP = "#other";
    /** A group's files step in this far past the card's padding: 58 from the card's edge. */
    private static final double GROUP_INDENT = 42;

    /** What the panel floats off the window's right edge: detailHost's right padding. */
    private static final double DETAIL_MARGIN = 14;

    @FXML private ListView<Row> reviewList;
    @FXML private Region detailGrip;
    @FXML private StackPane detailHost;
    @FXML private Label summaryLabel;
    @FXML private Label gateLabel;
    @FXML private Button applyButton;

    private final DiffPanel diffPanel = new DiffPanel();
    /** The width the panel was given; the window may show it narrower without forgetting it. */
    private double detailWanted = Double.NaN;
    /** The width the panel is shown at now. */
    private double detailWidth = PaneWidths.DETAIL;
    private Listener listener;
    private Map<String, Resolution> resolutions;
    private Set<String> skippedChanges;
    /** Changes ticked for the bulk bar; a tick decides nothing by itself. */
    private final Set<String> picked = new HashSet<>();
    private ChangeList.Filter filter = ChangeList.Filter.ALL;
    private ChangeList.Sort sort = ChangeList.Sort.PATH;
    private boolean sortReversed;
    /** Conflicts ticked for their bulk bar; kept apart so neither bar acts on the other's rows. */
    private final Set<String> pickedConflicts = new HashSet<>();
    /**
     * Gate cards of a move's hidden {@code from} path that a rename row's skip settled, so
     * undoing that skip reopens only these and never a decision made on the card itself.
     */
    private final Set<String> renameSkippedFroms = new HashSet<>();
    private ChangeList.ConflictFilter conflictFilter = ChangeList.ConflictFilter.ALL;
    /** Folders closed in each group, kept for the life of the check; every folder starts open. */
    private final Set<String> closedChanges = new HashSet<>();
    private final Set<String> closedConflicts = new HashSet<>();
    /** The changes as their groups rather than their folders. */
    private boolean groupsView;
    /** Groups opened, kept for the life of the check; every group starts closed. */
    private final Set<String> openGroups = new HashSet<>();
    private ChangeList.Sort conflictSort = ChangeList.Sort.PATH;
    private boolean conflictSortReversed;
    private SyncProfile profile;
    private CheckResult result;
    private RiskGate gate;
    /** Whether diff3 is installed; without it a merge cannot run, whatever the file is. */
    private boolean diff3Found;
    private String selectedPath;

    @FXML
    private void initialize() {
        reviewList.setFocusTraversable(false);
        reviewList.setCellFactory(lv -> new RowCell());
        // The panel is a rounded card; without the clip the diff rows' tints would
        // square off its bottom corners.
        Region detail = diffPanel.root();
        Rectangle clip = new Rectangle();
        clip.setArcWidth(40);
        clip.setArcHeight(40);
        clip.widthProperty().bind(detail.widthProperty());
        clip.heightProperty().bind(detail.heightProperty());
        detail.setClip(clip);
        detailHost.getChildren().add(detail);

        besideSidebar().widthProperty().addListener((obs, was, now) -> showDetailWidth());
        // The panel sits on the right, so dragging its edge left widens it.
        PaneGrip.attach(detailGrip, -1, () -> detailWidth, wanted -> {
            detailWanted = PaneWidths.detail(wanted, besideSidebar().getWidth());
            showDetailWidth();
        }, () -> {
            detailWanted = PaneWidths.DETAIL;
            showDetailWidth();
        }, () -> {
            // What was asked for, not what a narrow window shows: a reset is saved as the default.
            if (!Double.isNaN(detailWanted)) {
                listener.onDetailResized(detailWanted);
            }
        });
    }

    /** This step's own root: everything right of the sidebar. */
    private Region besideSidebar() {
        return (Region) reviewList.getParent();
    }

    /** @param wanted a width saved earlier, or NaN for the default */
    void restoreDetailWidth(double wanted) {
        detailWanted = wanted;
        showDetailWidth();
    }

    private void showDetailWidth() {
        detailWidth = PaneWidths.detail(detailWanted, besideSidebar().getWidth());
        double host = detailWidth + DETAIL_MARGIN;
        detailHost.setMinWidth(host);
        detailHost.setPrefWidth(host);
        detailHost.setMaxWidth(host);
    }

    void init(Listener listener, Map<String, Resolution> resolutions, Set<String> skippedChanges) {
        this.listener = listener;
        this.resolutions = resolutions;
        this.skippedChanges = skippedChanges;
    }

    void show(SyncProfile profile, CheckResult result, RiskGate gate, boolean diff3Found) {
        this.diff3Found = diff3Found;
        this.profile = profile;
        this.result = result;
        this.gate = gate;
        picked.clear();
        pickedConflicts.clear();
        renameSkippedFroms.clear();
        closedChanges.clear();
        closedConflicts.clear();
        groupsView = false;
        openGroups.clear();
        filter = ChangeList.Filter.ALL;
        conflictFilter = ChangeList.ConflictFilter.ALL;
        rebuildRows();

        // What most needs a look comes up first: a risky file, else a conflict.
        String first = gate.items().stream().filter(RiskGate.Item::isFile).map(RiskGate.Item::path).findFirst()
                .or(() -> shownConflicts().stream().map(SyncAction::relativePath).findFirst())
                .or(() -> shownChanges().stream().map(e -> e.action().relativePath()).findFirst())
                .orElse(null);
        if (first != null) {
            select(first);
        } else {
            selectedPath = null;
            diffPanel.clear();
            refresh();
        }
    }

    /** The changes group as the filter and the order leave it; a recognised move is one entry. */
    private List<ChangeList.Entry> shownChanges() {
        return ChangeList.shownEntries(result.changes(), result.renames(), result::factsFor, filter, sort,
                sortReversed, result.marks());
    }

    /** The model is off until the profile has enough history; its chip and order are hidden then. */
    private boolean modelOn() {
        return result.baseline().enough();
    }

    /** The model's mark is not shown where something louder already speaks for the file. */
    private boolean showsOdd(String path) {
        Oddity.Mark mark = result.marks().get(path);
        if (mark == null || !mark.odd() || gate.isFileItem(path)) {
            return false;
        }
        // A suspicious group's line speaks once for all its files.
        return gate.items().stream().noneMatch(i -> i.kind() == RiskGate.Kind.CLUSTER && i.members().contains(path));
    }

    private static List<String> paths(List<ChangeList.Entry> entries) {
        return entries.stream().map(e -> e.action().relativePath()).toList();
    }

    /** A skipped conflict is left alone whatever was picked for it, so it counts as undecided. */
    private Resolution decision(String path) {
        return isSkipped(path) ? Resolution.SKIP : resolutions.getOrDefault(path, Resolution.SKIP);
    }

    /** The conflicts group as its filter and order leave it. */
    private List<SyncAction> shownConflicts() {
        return ChangeList.shownConflicts(result.conflicts(), result::factsFor, this::decision,
                conflictFilter, conflictSort, conflictSortReversed);
    }

    /**
     * A decision moves a conflict between filters; under any filter but "all" its row
     * leaves the list, which is what turns "undecided" into a queue.
     */
    private void afterResolution(String path) {
        if (conflictFilter != ChangeList.ConflictFilter.ALL) {
            rebuildRows();
        }
        if (path == null || path.equals(selectedPath)) {
            refresh();
        } else {
            select(path);
        }
    }

    /** The rows themselves change only with a new check, a filter or an order, or a decision under a conflict filter. */
    private void rebuildRows() {
        List<Row> rows = new ArrayList<>();
        rows.add(new StatsRow());
        List<RiskGate.Item> items = gate.items();
        if (!items.isEmpty()) {
            rows.add(new RiskHeadRow());
            for (int i = 0; i < items.size(); i++) {
                rows.add(new RiskRow(items.get(i), i == items.size() - 1));
            }
        }
        List<SyncAction> conflicts = result.conflicts();
        if (!conflicts.isEmpty()) {
            rows.add(new ConflictsHeadRow());
            rows.add(new ConflictBulkRow());
            addCard(rows, treeRows(ChangeTree.flatten(shownConflicts().stream().map(a -> new ChangeList.Entry(a, null))
                    .toList(), closedConflicts, conflictSort, conflictSortReversed), true),
                    Texts.t("review.conflictFilterEmpty"));
        }
        if (!result.changes().isEmpty()) {
            rows.add(new ChangesHeadRow());
            rows.add(new BulkRow());
            addCard(rows, groupsView ? groupRows(shownChanges())
                            : treeRows(ChangeTree.flatten(shownChanges(), closedChanges, sort, sortReversed), false),
                    Texts.t("review.filterEmpty"));
        }
        if (result.actions().isEmpty()) {
            rows.add(new EmptyRow());
        }
        reviewList.getItems().setAll(rows);
    }

    /** The bulk bar is the card's top edge, so no row is ever the first. */
    private static void addCard(List<Row> rows, List<Row> card, String emptyText) {
        rows.addAll(card);
        if (card.isEmpty()) {
            rows.add(new FilterEmptyRow(emptyText));
        }
    }

    private static List<Row> treeRows(List<ChangeTree.Node> nodes, boolean conflicts) {
        List<Row> card = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            boolean last = i == nodes.size() - 1;
            card.add(switch (nodes.get(i)) {
                case ChangeTree.Folder folder -> new FolderRow(folder, conflicts, last);
                case ChangeTree.Leaf leaf -> new ActionRow(leaf.entry().action(), leaf.entry().rename(),
                        leaf.depth() * INDENT_STEP, ReviewText.splitPath(leaf.entry().action().relativePath()).name(),
                        false, last);
            });
        }
        return card;
    }

    /**
     * The changes as the groups they happened in, largest first, then the ones that changed
     * on their own. Each group keeps the list's filter and order for its rows.
     */
    private List<Row> groupRows(List<ChangeList.Entry> shown) {
        List<Row> card = new ArrayList<>();
        Set<String> grouped = new HashSet<>();
        for (ChangeClusters.Cluster cluster : result.clusters()) {
            Set<String> members = new HashSet<>(cluster.paths());
            grouped.addAll(members);
            addGroup(card, cluster.id(), cluster,
                    shown.stream().filter(e -> members.contains(e.action().relativePath())).toList());
        }
        addGroup(card, OTHER_GROUP, null,
                shown.stream().filter(e -> !grouped.contains(e.action().relativePath())).toList());
        if (!card.isEmpty()) {
            card.set(card.size() - 1, switch (card.getLast()) {
                case GroupRow r -> new GroupRow(r.id(), r.cluster(), r.entries(), true);
                case ActionRow r -> new ActionRow(r.action(), r.rename(), r.indent(), r.name(), r.first(), true);
                default -> card.getLast();
            });
        }
        return card;
    }

    private void addGroup(List<Row> card, String id, ChangeClusters.Cluster cluster, List<ChangeList.Entry> entries) {
        if (entries.isEmpty()) {
            return;
        }
        card.add(new GroupRow(id, cluster, entries, false));
        if (openGroups.contains(id)) {
            // The group row carries the folder; each file shows only what lies below it.
            String prefix = cluster == null || cluster.prefix().isEmpty() ? "" : cluster.prefix() + "/";
            for (ChangeList.Entry entry : entries) {
                String path = entry.action().relativePath();
                card.add(new ActionRow(entry.action(), entry.rename(), GROUP_INDENT,
                        path.startsWith(prefix) ? path.substring(prefix.length()) : path, false, false));
            }
        }
    }

    /** Switches the changes to their groups and opens this one -- where a group card or a mass change points. */
    private void showGroup(String id) {
        groupsView = true;
        openGroups.add(id);
        rebuildRows();
        List<Row> rows = reviewList.getItems();
        for (int i = 0; i < rows.size(); i++) {
            if (rows.get(i) instanceof GroupRow group && group.id().equals(id)) {
                reviewList.scrollTo(i);
                break;
            }
        }
    }

    void clear() {
        profile = null;
        result = null;
        gate = null;
        selectedPath = null;
        picked.clear();
        pickedConflicts.clear();
        renameSkippedFroms.clear();
        closedChanges.clear();
        closedConflicts.clear();
        openGroups.clear();
        reviewList.setItems(FXCollections.observableArrayList());
        diffPanel.clear();
        applyButton.setDisable(true);
        summaryLabel.setText("");
        gateLabel.setText("");
    }

    private void select(String path) {
        if (result == null) {
            // A click can land on a stale cell in the pulse after clear().
            return;
        }
        selectedPath = path;
        CheckResult.FolderContents folder = result.folders().get(path);
        if (folder != null) {
            // A folder has no diff; what goes with it is the thing to look at.
            diffPanel.showFolder(path, result.actionFor(path).orElse(null), folder);
        } else {
            SyncAction action = result.actionFor(path).orElse(null);
            boolean scorable = action != null && action.actionType() != SyncAction.ActionType.CONFLICT
                    && action.actionType() != SyncAction.ActionType.DELETED;
            diffPanel.showFile(path, action,
                    profile.laptopRoot().resolve(path), profile.hddRoot().resolve(path), result.headsFor(path),
                    gate.item(path).map(RiskGate.Item::finding).orElse(null), result.marks().get(path),
                    scorable && !modelOn() ? ReviewText.modelCold(result.baseline().history().size()) : null);
        }
        refresh();
    }

    /** Left out of this Apply: a risky file at the gate, an ordinary change in the list. */
    private boolean isSkipped(String path) {
        return gate.isSkipped(path) || skippedChanges.contains(path);
    }

    /** A move's {@code from} row is hidden and goes with its {@code to} row's skip. */
    private boolean isLeftOut(String path) {
        return isSkipped(path) || result.renames().stream()
                .anyMatch(r -> r.from().equals(path) && isSkipped(r.to()));
    }

    /**
     * A file the scanner flagged keeps its skip in the gate, where it also counts as decided.
     * A move's row is its {@code to} path; skipping it settles a gate card for the hidden
     * {@code from} path too, which would otherwise hold Apply back with no row to show it.
     */
    private void setSkipped(String path, boolean value) {
        skipOne(path, value);
        for (PlanInterpreter.Rename rename : result.renames()) {
            if (rename.to().equals(path)) {
                cascadeToFrom(rename.from(), value);
            }
        }
    }

    /**
     * Only a card still waiting is settled, and only such a card is reopened: an acknowledged
     * or already skipped {@code from} stays as the user left it. The Apply does not depend on
     * this -- {@link ApplyPlan#skipped} leaves both halves of a skipped move out by itself.
     */
    private void cascadeToFrom(String from, boolean skip) {
        boolean waiting = gate.isPendingFile(from)
                || (gate.keepsSkip(from) && !gate.isFileItem(from) && !gate.isSkipped(from));
        if (skip && waiting) {
            gate.setPathSkipped(from, true);
            renameSkippedFroms.add(from);
        } else if (!skip && renameSkippedFroms.remove(from)) {
            gate.setPathSkipped(from, false);
        }
    }

    /** A flagged file or a file of a suspicious group keeps its skip in the gate, where it counts as a decision. */
    private void skipOne(String path, boolean value) {
        if (gate.keepsSkip(path)) {
            gate.setPathSkipped(path, value);
        } else if (value) {
            skippedChanges.add(path);
        } else {
            skippedChanges.remove(path);
        }
    }

    /** A skipped file is left alone whatever was picked for it, so it resolves nothing. */
    private int resolvedCount() {
        return (int) result.conflicts().stream()
                .filter(a -> !gate.isSkipped(a.relativePath()))
                .filter(a -> resolutions.getOrDefault(a.relativePath(), Resolution.SKIP) != Resolution.SKIP)
                .count();
    }

    /** Redraws the rows from state and brings the footer and the Apply gate in line with it. */
    private void refresh() {
        if (result == null) {
            return;
        }
        reviewList.refresh();
        int conflicts = result.conflicts().size();
        int resolved = resolvedCount();
        int moving = (int) result.changes().stream().filter(a -> !isLeftOut(a.relativePath())).count();
        summaryLabel.setText(ReviewText.applySummary(moving, resolved, conflicts - resolved));
        gateLabel.setText(ReviewText.gateText(gate.pendingCount()));
        applyButton.setDisable(!gate.allAcknowledged());
    }

    @FXML
    private void onBack() {
        listener.onBackToSetup();
    }

    @FXML
    private void onApply() {
        // The button is disabled while the gate is closed; checked again because this is
        // the one place where a stale enabled state would let a risky Apply through.
        if (gate != null && gate.allAcknowledged()) {
            listener.onApply();
        }
    }

    /**
     * Cells are recycled, so every handler captures the path it acts on rather than
     * asking the cell for its current item.
     */
    private class RowCell extends ListCell<Row> {
        RowCell() {
            // A cell as wide as its widest label would scroll the list sideways and push
            // the conflict choices out of view; at zero it takes the list's own width.
            setPrefWidth(0);
        }

        @Override
        protected void updateItem(Row row, boolean empty) {
            super.updateItem(row, empty);
            if (empty || row == null || result == null) {
                setGraphic(null);
                return;
            }
            setGraphic(switch (row) {
                case StatsRow r -> stats();
                case RiskHeadRow r -> riskHead();
                case RiskRow r -> riskItem(r.item(), r.last());
                case ActionRow r -> actionRow(r);
                case FolderRow r -> folderRow(r);
                case GroupRow r -> groupRow(r);
                case EmptyRow r -> emptyRow();
                case ChangesHeadRow r -> changesHead();
                case BulkRow r -> bulkRow();
                case ConflictsHeadRow r -> conflictsHead();
                case ConflictBulkRow r -> conflictBulkRow();
                case FilterEmptyRow r -> filterEmptyRow(r.text());
            });
        }
    }

    private static Label label(String text, String... styleClasses) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }

    private static Region spacer() {
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        return spacer;
    }

    /** More ticks than this leave no gap between them at the default window size. */
    private static final int TICKS = 18;

    private Node stats() {
        int total = result.actions().size();
        int changes = result.changes().size();
        long toHdd = result.changes().stream()
                .filter(a -> a.direction() == SyncAction.Direction.LAPTOP_TO_HDD).count();
        int conflicts = result.conflicts().size();
        int resolved = resolvedCount();
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.add(statCard(String.valueOf(changes), ReviewText.statsLabel(toHdd, changes - toHdd),
                "acc", share(changes, total)), 0, 0);
        grid.add(statCard(String.valueOf(conflicts), Texts.t("review.stat.conflict"), "warn", share(conflicts, total)), 1, 0);
        grid.add(statCard(String.valueOf(gate.fileCount()), Texts.t("review.stat.risky"), "risk",
                share(gate.fileCount(), total)), 2, 0);
        // Nothing to resolve reads as a full bar, not an empty one.
        grid.add(statCard(resolved + "/" + conflicts, Texts.t("review.stat.resolved"), "ok",
                conflicts == 0 ? 1 : share(resolved, conflicts)), 3, 0);
        for (int i = 0; i < 4; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(25);
            grid.getColumnConstraints().add(column);
        }
        VBox box = new VBox(10, grid);
        box.getStyleClass().add("stats-row");
        if (!result.manifestFound() && result.mode() == SyncMode.TWO_WAY) {
            // The backup's manifest is what tells a file deleted on the main side from one that
            // is new on the backup; without it the plan cannot, and the reader should know.
            Label note = label(Texts.t("review.noManifest"), "muted", "small");
            note.setWrapText(true);
            box.getChildren().add(note);
        }
        return box;
    }

    /** A flagged file need not be in the plan, so the share is capped at the whole bar. */
    private static double share(long part, long whole) {
        return whole <= 0 ? 0 : Math.min(1, part / (double) whole);
    }

    /** @param tone the dot's and the filled ticks' colour; {@code share} is how much of the bar is filled */
    private static Node statCard(String value, String text, String tone, double share) {
        Region dot = new Region();
        dot.getStyleClass().addAll("dot", "stat-dot", "dot-" + tone);
        Label label = label(text, "stat-label");
        label.setWrapText(true);
        HBox head = new HBox(8, dot, label);
        head.getStyleClass().add("stat-head");
        // The label wraps to a different number of lines in each card; the growing head
        // keeps the values and the bars on one line across the four of them.
        VBox.setVgrow(head, Priority.ALWAYS);

        HBox ticks = new HBox();
        ticks.getStyleClass().add("ticks");
        long filled = Math.round(share * TICKS);
        for (int i = 0; i < TICKS; i++) {
            if (i > 0) {
                ticks.getChildren().add(spacer());
            }
            Region tick = new Region();
            tick.getStyleClass().add("tick");
            if (i < filled) {
                tick.getStyleClass().add(tone);
            }
            ticks.getChildren().add(tick);
        }
        VBox card = new VBox(6, head, label(value, "stat-value"), ticks);
        card.getStyleClass().add("stat-card");
        return card;
    }

    private Node riskHead() {
        HBox head = new HBox(10, label("!", "risk-bang"),
                label(ReviewText.riskTitle(gate.pendingCount()), "card-title"));
        head.setAlignment(Pos.CENTER_LEFT);
        head.getStyleClass().add("risk-head");
        return head;
    }

    /**
     * A gate card. The whole card is clickable: a file's selects that file, a group's opens
     * the group, a mass change opens the largest group, the exit-1 card shows Unison's
     * output; the rest have nothing to show.
     */
    private Node riskItem(RiskGate.Item item, boolean last) {
        boolean acknowledged = gate.isAcknowledged(item.key());
        boolean skipped = item.isGroup() ? gate.isGroupSkipped(item.key()) : gate.isSkipped(item.key());
        boolean exitOne = RiskGate.EXIT_ONE_KEY.equals(item.key());

        Label title = label(item.title(), item.isFile() || exitOne ? "risk-path" : "w500");
        title.setWrapText(true);
        Label shortLabel = item.isFile() ? label(riskShortFor(item.path()), "risk-tag") : null;
        HBox right = new HBox(10);
        right.setAlignment(Pos.CENTER_RIGHT);
        String tag = switch (item.kind()) {
            case MASS_CHANGE -> "gate.kind.fixed";
            case BASELINE -> "gate.kind.baseline";
            case CLUSTER -> "gate.kind.cluster";
            case CONTENT -> "gate.kind.content";
            default -> null;
        };
        if (tag != null) {
            right.getChildren().add(fixed(label(Texts.t(tag), "kind-tag")));
        }
        right.getChildren().add(fixed(label(ReviewText.riskState(acknowledged, skipped), "muted", "small")));
        CardHead top = new CardHead(title, shortLabel, right);

        Label reason = label(item.reason(), "muted");
        reason.setWrapText(true);

        Button acknowledge = new Button(Texts.t("review.ack"), label(acknowledged ? "✓" : "", "check-glyph"));
        acknowledge.getStyleClass().add("toggle-btn");
        if (acknowledged) {
            acknowledge.getStyleClass().add("on");
        }
        acknowledge.setOnAction(e -> {
            gate.setAcknowledged(item.key(), !acknowledged);
            afterDecision(item);
        });
        // The buttons go to the next line rather than lose their words in a narrow card.
        FlowPane decisions = new FlowPane(8, 8, ownClick(acknowledge));
        if (item.isFile()) {
            // Only a file can sit a run out; a mass change is the run.
            Button skip = new Button(Texts.t("review.skip"));
            skip.getStyleClass().add("toggle-btn");
            if (skipped) {
                skip.getStyleClass().add("on");
            }
            skip.setOnAction(e -> {
                gate.setSkipped(item.key(), !skipped);
                afterDecision(item);
            });
            decisions.getChildren().add(ownClick(skip));
        } else if (item.isGroup()) {
            // Skipping is the safe direction, so a whole group may sit this run out at once.
            Button skipGroup = new Button(Texts.t("review.group.skip"));
            skipGroup.getStyleClass().add("toggle-btn");
            if (skipped) {
                skipGroup.getStyleClass().add("on");
            }
            skipGroup.setOnAction(e -> {
                gate.setGroupSkipped(item.key(), !skipped);
                afterDecision(item);
            });
            Label show = label(Texts.t("review.group.show"), "link-text");
            show.setOnMouseClicked(e -> {
                e.consume();
                showGroup(item.key());
            });
            decisions.getChildren().addAll(ownClick(skipGroup), show);
            decisions.setAlignment(Pos.CENTER_LEFT);
        }

        VBox inner = new VBox(8, top, reason, decisions);
        if (item.kind() == RiskGate.Kind.BASELINE) {
            inner.getChildren().add(2, baselineChart(result.baseline()));
        }
        inner.getStyleClass().add("risk-inner");
        if (item.isFile()) {
            inner.getStyleClass().add("clickable");
            inner.setOnMouseClicked(e -> select(item.path()));
            if (item.path().equals(selectedPath)) {
                inner.getStyleClass().add("selected");
            }
        } else if (item.isGroup()) {
            inner.getStyleClass().add("clickable");
            inner.setOnMouseClicked(e -> showGroup(item.key()));
        } else if ((item.kind() == RiskGate.Kind.MASS_CHANGE || item.kind() == RiskGate.Kind.BASELINE)
                && !result.clusters().isEmpty()) {
            // No single file to show; the largest group is where most of the change is.
            String largest = result.clusters().getFirst().id();
            inner.getStyleClass().add("clickable");
            inner.setOnMouseClicked(e -> showGroup(largest));
        } else if (exitOne) {
            inner.getStyleClass().add("clickable");
            // Unison's raw output is the only place that says what was skipped.
            inner.setOnMouseClicked(e -> {
                selectedPath = null;
                diffPanel.showText(Texts.t("review.unisonOutput"), result.rawOutput());
                reviewList.refresh();
            });
        }
        VBox outer = new VBox(inner);
        outer.getStyleClass().add("risk-item");
        if (last) {
            outer.getStyleClass().add("last");
        }
        return outer;
    }

    /**
     * A risk card's first line: the title, the short label after it, and the kind tag and
     * state at the right edge. When they do not fit on one line the title takes a line of its
     * own -- wrapping only if it alone is wider than the card -- and the rest goes under it.
     */
    private static final class CardHead extends Region {
        private static final double GAP = 10;
        private static final double LINE_GAP = 4;
        private final Label title;
        private final Label shortLabel;
        private final Region right;

        CardHead(Label title, Label shortLabel, Region right) {
            this.title = title;
            this.shortLabel = shortLabel;
            this.right = right;
            getChildren().add(title);
            if (shortLabel != null) {
                getChildren().add(shortLabel);
            }
            getChildren().add(right);
        }

        @Override
        public Orientation getContentBias() {
            return Orientation.HORIZONTAL;
        }

        private double shortWidth() {
            return shortLabel == null ? 0 : shortLabel.prefWidth(-1) + GAP;
        }

        private boolean oneLine(double width) {
            return title.prefWidth(-1) + shortWidth() + GAP + right.prefWidth(-1) <= width;
        }

        @Override
        protected double computePrefWidth(double height) {
            return title.prefWidth(-1) + shortWidth() + GAP + right.prefWidth(-1);
        }

        @Override
        protected double computeMinWidth(double height) {
            return 0;
        }

        @Override
        protected double computePrefHeight(double width) {
            double rest = Math.max(shortLabel == null ? 0 : shortLabel.prefHeight(-1), right.prefHeight(-1));
            if (width < 0 || oneLine(width)) {
                return Math.max(title.prefHeight(-1), rest);
            }
            return title.prefHeight(width) + LINE_GAP + rest;
        }

        @Override
        protected void layoutChildren() {
            double width = getWidth();
            double rightWidth = right.prefWidth(-1);
            double rightHeight = right.prefHeight(-1);
            if (oneLine(width)) {
                double titleWidth = title.prefWidth(-1);
                double height = Math.max(title.prefHeight(-1), rightHeight);
                title.resizeRelocate(0, 0, titleWidth, title.prefHeight(-1));
                if (shortLabel != null) {
                    shortLabel.resizeRelocate(titleWidth + GAP, 0, shortLabel.prefWidth(-1), shortLabel.prefHeight(-1));
                }
                right.resizeRelocate(width - rightWidth, (height - rightHeight) / 2, rightWidth, rightHeight);
                return;
            }
            double titleHeight = title.prefHeight(width);
            title.resizeRelocate(0, 0, width, titleHeight);
            double y = titleHeight + LINE_GAP;
            if (shortLabel != null) {
                shortLabel.resizeRelocate(0, y, shortLabel.prefWidth(-1), shortLabel.prefHeight(-1));
            }
            right.resizeRelocate(Math.max(0, width - rightWidth), y, rightWidth, rightHeight);
        }
    }

    /** A flagged file's few words: what its bytes say when they say something, else its size drop. */
    private String riskShortFor(String path) {
        return gate.item(path).map(RiskGate.Item::finding).map(ReviewText::signalShort)
                .orElseGet(() -> ReviewText.riskShort(result.actionFor(path).orElse(null), result.factsFor(path)));
    }

    /** How tall the baseline chart is; a value's bar is this share of the scale's top. */
    private static final double CHART_HEIGHT = 56;

    /**
     * The past syncs as bars, today's last and in risk colour; the usual range shaded behind
     * them and this profile's limit dashed across.
     */
    private static VBox baselineChart(Baseline baseline) {
        double top = baseline.chartTop();
        IntToDoubleFunction y = value -> Math.round(CHART_HEIGHT * value / top);

        HBox bars = new HBox(3);
        bars.setAlignment(Pos.BOTTOM_LEFT);
        bars.setFillHeight(false);
        for (int value : baseline.history()) {
            String state = value > baseline.limit() ? "over" : null;
            bars.getChildren().add(chartBar(Math.max(2, y.applyAsDouble(value)), state));
        }
        bars.getChildren().add(chartBar(y.applyAsDouble(baseline.today()), "today"));

        Region band = new Region();
        band.getStyleClass().add("baseline-band");
        double bandHeight = Math.max(2, y.applyAsDouble(baseline.high()) - y.applyAsDouble(baseline.low()));
        band.setMinHeight(bandHeight);
        band.setMaxHeight(bandHeight);
        StackPane.setAlignment(band, Pos.BOTTOM_CENTER);
        StackPane.setMargin(band, new Insets(0, 0, y.applyAsDouble(baseline.low()), 0));

        Region limit = new Region();
        limit.getStyleClass().add("baseline-limit");
        limit.setMinHeight(2);
        limit.setMaxHeight(2);
        StackPane.setAlignment(limit, Pos.BOTTOM_CENTER);
        StackPane.setMargin(limit, new Insets(0, 0, y.applyAsDouble(baseline.limit()), 0));

        StackPane chart = new StackPane(band, bars, limit);
        chart.setMinHeight(CHART_HEIGHT);
        chart.setPrefHeight(CHART_HEIGHT);
        chart.setMaxHeight(CHART_HEIGHT);
        chart.setMinWidth(0);

        HBox legend = new HBox(10, label(Texts.t("review.chart.from", baseline.history().size())), spacer(),
                label("▬ " + Texts.t("review.chart.band")), label("┄ " + Texts.t("review.chart.limit")), spacer(),
                label(Texts.t("review.chart.today")));
        legend.getStyleClass().add("baseline-legend");
        return new VBox(4, chart, legend);
    }

    private static Region chartBar(double height, String state) {
        Region bar = new Region();
        bar.getStyleClass().add("baseline-bar");
        if (state != null) {
            bar.getStyleClass().add(state);
        }
        bar.setMinHeight(height);
        bar.setPrefHeight(height);
        bar.setMaxHeight(height);
        bar.setMinWidth(1);
        bar.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(bar, Priority.ALWAYS);
        return bar;
    }

    /** A button's click is its own decision and must not also select the card it sits on. */
    private static Button ownClick(Button button) {
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.addEventHandler(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
        return button;
    }

    private void afterDecision(RiskGate.Item item) {
        // A click on the card is the user's own decision; a rename's undo must not reopen it.
        renameSkippedFroms.remove(item.key());
        // Same file already shown: re-running diff would change nothing. A skip at the gate
        // can move a conflict between filters, so this goes the way a resolution does.
        afterResolution(item.isFile() ? item.path() : null);
    }

    private Node changesHead() {
        List<SyncAction> changes = result.changes();
        HBox views = new HBox(viewOption(Texts.t("review.view.tree"), false),
                viewOption(Texts.t("review.view.groups"), true));
        views.getStyleClass().addAll("seg", "sort-seg");
        Tooltip.install(views, new Tooltip(Texts.t("review.view.title")));
        // Wraps rather than cuts: the orders move to their own line when the list is narrow.
        FlowPane top = new FlowPane(10, 6, label(Texts.t("review.changes", changes.size()), "group-title"), views,
                sortButtons(sortOptions(modelOn()), sort, sortReversed, option -> {
                    sortReversed = option == sort && !sortReversed;
                    sort = option;
                    rebuildRows();
                }));
        top.setAlignment(Pos.CENTER_LEFT);

        // On their own line, and wrapping like the conflicts' chips: a row wider than the list
        // would also widen the whole head, and the orders above would be cut instead of wrapped.
        FlowPane filters = new FlowPane(4, 4);
        for (ChangeList.Filter option : ChangeList.Filter.values()) {
            if (option == ChangeList.Filter.ODD && !modelOn()) {
                continue;
            }
            filters.getChildren().add(filterChip(option.label(), ChangeList.count(changes, option, result.marks()),
                    tone(option), option == filter, () -> {
                        filter = option;
                        rebuildRows();
                    }));
        }
        VBox head = new VBox(8, top, filters);
        head.getStyleClass().add("group-head");
        return head;
    }

    private Button viewOption(String text, boolean groups) {
        Button button = new Button(text);
        button.getStyleClass().add("seg-opt");
        if (groups == groupsView) {
            button.getStyleClass().addAll("on", "neutral");
        }
        button.setOnAction(e -> {
            groupsView = groups;
            rebuildRows();
        });
        return button;
    }

    private Node conflictsHead() {
        List<SyncAction> conflicts = result.conflicts();
        HBox top = new HBox(10, label(Texts.t("review.conflicts", conflicts.size()), "group-title"), spacer(),
                sortButtons(sortOptions(false), conflictSort, conflictSortReversed, option -> {
                    conflictSortReversed = option == conflictSort && !conflictSortReversed;
                    conflictSort = option;
                    rebuildRows();
                }));
        top.setAlignment(Pos.CENTER_LEFT);

        // Five chips do not fit the list's width in one line; they wrap rather than shrink.
        FlowPane filters = new FlowPane(4, 4);
        for (ChangeList.ConflictFilter option : ChangeList.ConflictFilter.values()) {
            filters.getChildren().add(filterChip(option.label(),
                    ChangeList.count(conflicts, this::decision, option), tone(option),
                    option == conflictFilter, () -> {
                        conflictFilter = option;
                        rebuildRows();
                    }));
        }
        VBox head = new VBox(8, top, label(Texts.t("review.conflictsHint"), "faint", "small"), filters);
        head.getStyleClass().add("group-head");
        return head;
    }

    /** The arrow marks the order in use; a second click on it turns the order around. */
    /** The model's order only where the model scored something: changes, while it is on. */
    private static List<ChangeList.Sort> sortOptions(boolean anomaly) {
        return Arrays.stream(ChangeList.Sort.values())
                .filter(option -> anomaly || option != ChangeList.Sort.ANOMALY).toList();
    }

    private static HBox sortButtons(List<ChangeList.Sort> options, ChangeList.Sort current, boolean reversed,
                                    Consumer<ChangeList.Sort> onPick) {
        HBox sorts = new HBox();
        sorts.getStyleClass().addAll("seg", "sort-seg");
        for (ChangeList.Sort option : options) {
            Button button = new Button(option.label(option == current, reversed));
            button.getStyleClass().add("seg-opt");
            if (option == current) {
                button.getStyleClass().addAll("on", "neutral");
            }
            button.setOnAction(e -> onPick.accept(option));
            sorts.getChildren().add(button);
        }
        return sorts;
    }

    private static Button filterChip(String text, long count, String tone, boolean on, Runnable onPick) {
        Region dot = new Region();
        dot.getStyleClass().addAll("dot", "dot-" + tone);
        HBox content = new HBox(6, dot, label(text, "filter-name"), label(String.valueOf(count), "filter-count"));
        content.setAlignment(Pos.CENTER_LEFT);
        Button button = new Button(null, content);
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.getStyleClass().addAll("filter", tone);
        if (on) {
            button.getStyleClass().add("on");
        }
        button.setOnAction(e -> onPick.run());
        return button;
    }

    /** The colour a kind of change is drawn in: its filter's dot and its row's direction. */
    private static String tone(ChangeList.Filter kind) {
        return switch (kind) {
            case ALL -> "faint";
            case TO_HDD -> "hdd";
            case TO_LAPTOP -> "lap";
            case DELETES, OVERWRITES -> "risk";
            // Warn is the model's colour throughout; risk is kept for the gate.
            case ODD -> "warn";
        };
    }

    /** Undecided is drawn as a conflict is; each side in its own colour. */
    private static String tone(ChangeList.ConflictFilter kind) {
        return switch (kind) {
            case ALL -> "faint";
            case UNDECIDED -> "warn";
            case LAPTOP -> "lap";
            case HDD -> "hdd";
            case MERGE -> "acc";
        };
    }

    /** The ticked rows still on screen; a tick the filter has hidden is not acted on. */
    private static List<String> pickedShown(List<String> shown, Set<String> ticks) {
        return shown.stream().filter(ticks::contains).toList();
    }

    private static Label checkBox(String mark) {
        Label box = label(mark, "pick");
        if (!mark.isEmpty()) {
            box.getStyleClass().add("on");
        }
        return box;
    }

    private Node bulkRow() {
        return bulkBar(paths(shownChanges()), picked, ticked -> {
            Button skip = bulkButton(Texts.t("review.skip"), "btn");
            skip.setOnAction(e -> {
                ticked.forEach(path -> setSkipped(path, true));
                picked.clear();
                refresh();
            });
            Button apply = bulkButton(Texts.t("review.bulkApply"), "btn");
            apply.setOnAction(e -> {
                // Only undoes a skip. A flagged file goes back to waiting for its own
                // decision at the gate: one click here must not acknowledge twenty of them.
                ticked.forEach(path -> setSkipped(path, false));
                picked.clear();
                refresh();
            });
            return List.of(skip, apply);
        });
    }

    /**
     * Merge is not offered here: it is allowed per file, and a bulk merge would have to pass
     * over the files it may not touch without saying so.
     */
    private Node conflictBulkRow() {
        return bulkBar(shownConflicts().stream().map(SyncAction::relativePath).toList(), pickedConflicts,
                ticked -> List.of(
                        keepButton(Texts.t("review.bulkKeepLaptop"), ticked, Resolution.LAPTOP),
                        keepButton(Texts.t("review.bulkKeepHdd"), ticked, Resolution.HDD),
                        keepButton(Resolution.SKIP.label(), ticked, Resolution.SKIP)));
    }

    private Button keepButton(String text, List<String> ticked, Resolution resolution) {
        Button button = bulkButton(text, "btn");
        button.setOnAction(e -> {
            ticked.forEach(path -> resolutions.put(path, resolution));
            pickedConflicts.clear();
            afterResolution(null);
        });
        return button;
    }

    /** @param actions the buttons for the ticked rows still on screen; drawn only while there are some */
    private HBox bulkBar(List<String> shown, Set<String> ticks, Function<List<String>, List<Button>> actions) {
        List<String> ticked = pickedShown(shown, ticks);
        boolean all = !shown.isEmpty() && ticked.size() == shown.size();

        Label box = checkBox(all ? "✓" : ticked.isEmpty() ? "" : "–");
        Tooltip.install(box, new Tooltip(Texts.t("review.selectAll")));
        box.setOnMouseClicked(e -> {
            if (all) {
                shown.forEach(ticks::remove);
            } else {
                ticks.addAll(shown);
            }
            refresh();
        });
        Label text = label(ReviewText.bulkText(ticked.size()), "small", ticked.isEmpty() ? "muted" : "w500");
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        text.setMaxWidth(Double.MAX_VALUE);

        HBox row = new HBox(10, box, text);
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().add("bulk-row");
        if (!ticked.isEmpty()) {
            row.getStyleClass().add("picked");
            row.getChildren().addAll(actions.apply(ticked));
            // A glyph, not the words: the buttons and the count share one narrow row.
            Button cancel = bulkButton("×", "btn-ghost");
            cancel.setTooltip(new Tooltip(Texts.t("review.clearPicked")));
            cancel.setOnAction(e -> {
                ticks.clear();
                refresh();
            });
            row.getChildren().add(cancel);
        }
        return row;
    }

    private static Button bulkButton(String text, String kind) {
        Button button = new Button(text);
        button.getStyleClass().addAll(kind, "bulk-btn");
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private Node filterEmptyRow(String text) {
        HBox row = new HBox(label(text, "muted"));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("row", "last", "plain");
        return row;
    }

    private Node emptyRow() {
        return label(Texts.t("review.empty"), "empty-row");
    }

    /** Every tree level steps in by this much; the card's own padding is the first 16. */
    private static final double INDENT_STEP = 22;
    /**
     * Where the lines under a row start -- a conflict's choice, the details: past the
     * checkbox and the badge, at the name.
     */
    private static final double DETAILS_INSET = 44;

    /** In a narrow row the name gives way, never this label. */
    private static Label fixed(Label label) {
        label.setMinWidth(Region.USE_PREF_SIZE);
        return label;
    }

    private static Region indent(double width) {
        Region indent = new Region();
        indent.setMinWidth(width);
        indent.setMaxWidth(width);
        return indent;
    }

    private Node actionRow(ActionRow item) {
        SyncAction action = item.action();
        String path = action.relativePath();
        // The folder or group row above carries the rest of the path.
        Label name = label(item.name(), "path-name");
        name.setMinWidth(0);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);

        boolean conflict = action.actionType() == SyncAction.ActionType.CONFLICT;
        HBox line = new HBox(10, indent(item.indent()));
        Set<String> ticks = conflict ? pickedConflicts : picked;
        Label box = checkBox(ticks.contains(path) ? "✓" : "");
        box.setOnMouseClicked(e -> {
            // A tick is not a look at the file: the row under it stays unselected.
            e.consume();
            if (!ticks.remove(path)) {
                ticks.add(path);
            }
            refresh();
        });
        line.getChildren().addAll(box, DiffPanel.badge(action.actionType()), name);
        line.setAlignment(Pos.CENTER_LEFT);

        boolean skipped = isSkipped(path);
        HBox choice = null;
        if (conflict) {
            Node picker = resolutionPicker(path);
            // Skipping wins over any choice here, so the choice must not look live.
            picker.setDisable(skipped);
            // On its own line under the name: beside it, the choices left the name no room.
            choice = new HBox(picker);
            choice.getStyleClass().add("row-choice");
            choice.setPadding(new Insets(2, 0, 2, item.indent() + DETAILS_INSET));
        } else {
            Label direction = skipped ? label(ReviewText.skippedDirection(), "dir-text", "dir-skip")
                    : label(ReviewText.direction(action), "dir-text", "dir-" + tone(ChangeList.Filter.of(action)));
            VBox right = new VBox(2, fixed(direction),
                    fixed(label(ReviewText.sizes(action, result.factsFor(path)), "sizes")));
            right.getStyleClass().add("right-col");
            line.getChildren().add(right);
        }

        VBox row = new VBox(line);
        if (choice != null) {
            row.getChildren().add(choice);
        }
        row.getStyleClass().add("row");
        if (item.first()) {
            row.getStyleClass().add("first");
        }
        if (item.last()) {
            row.getStyleClass().add("last");
        }
        if (path.equals(selectedPath)) {
            row.getStyleClass().add("selected");
        }
        if (skipped) {
            row.getStyleClass().add("skipped");
        }
        row.setOnMouseClicked(e -> select(path));

        List<Label> details = new ArrayList<>();
        String flagged = pendingFlag(path, item.rename());
        if (flagged != null) {
            // Under the row rather than beside the name, where it hid the name in a narrow list.
            details.add(label("⚠ " + riskShortFor(flagged), "risk-tag"));
        }
        if (showsOdd(path)) {
            details.add(label(ReviewText.oddLine(result.marks().get(path)), "odd-line"));
        }
        details.addAll(rowDetails(path, item.rename()));
        if (!details.isEmpty()) {
            // Wrapped, not cut: the row keeps the list's width and a note is read to decide.
            details.forEach(detail -> detail.setWrapText(true));
            VBox detailBox = new VBox(2);
            detailBox.getChildren().addAll(details);
            detailBox.getStyleClass().add("row-details");
            detailBox.setPadding(new Insets(2, 0, 2, item.indent() + DETAILS_INSET));
            row.getChildren().add(detailBox);
        }
        return row;
    }

    /**
     * A folder of the tree: a click opens or closes it and selects nothing. Its tick and its
     * counts cover every file under it that the filter shows, open or closed.
     */
    private Node folderRow(FolderRow item) {
        ChangeTree.Folder folder = item.folder();
        String path = folder.path();
        List<String> paths = folder.paths();
        Set<String> closed = item.conflicts() ? closedConflicts : closedChanges;

        HBox line = new HBox(10, indent(folder.depth() * INDENT_STEP));
        if (!item.conflicts()) {
            ChangeTree.Ticks ticks = ChangeTree.ticks(paths, picked);
            Label box = checkBox(switch (ticks) {
                case ALL -> "✓";
                case SOME -> "–";
                case NONE -> "";
            });
            box.setOnMouseClicked(e -> {
                e.consume();
                if (ticks == ChangeTree.Ticks.ALL) {
                    paths.forEach(picked::remove);
                } else {
                    picked.addAll(paths);
                }
                refresh();
            });
            line.getChildren().add(box);
        }
        Region icon = new Region();
        icon.getStyleClass().add("folder-icon");
        HBox glyphs = new HBox(2, label(folder.open() ? "▾" : "▸", "caret"), icon);
        glyphs.setAlignment(Pos.CENTER);
        glyphs.getStyleClass().add("folder-glyphs");

        Label name = label(folder.name(), "folder-name");
        name.setMinWidth(0);
        name.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(name, Priority.ALWAYS);
        line.getChildren().addAll(glyphs, name);
        if (!folder.open()) {
            // Closed, the folder is the only place that can say a file inside still waits.
            long waiting = folder.count(e -> pendingFlag(e.action().relativePath(), e.rename()) != null);
            if (waiting > 0) {
                line.getChildren().add(fixed(label("⚠ " + waiting, "risk-tag")));
            }
        }
        List<SyncAction> actions = folder.files().stream().map(ChangeList.Entry::action).toList();
        String meta = item.conflicts()
                ? ReviewText.conflictFolderMeta(actions.size(),
                        folder.count(e -> decision(e.action().relativePath()) == Resolution.SKIP))
                : ReviewText.folderMeta(actions, this::isSkipped);
        line.getChildren().add(fixed(label(meta, "folder-meta")));
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().addAll("row", "folder-row");
        Tooltip.install(line, new Tooltip(Texts.t("review.tree.toggle")));
        if (item.last()) {
            line.getStyleClass().add("last");
        }
        if (folder.count(e -> !isSkipped(e.action().relativePath())) == 0) {
            line.getStyleClass().add("skipped");
        }
        line.setOnMouseClicked(e -> {
            if (!closed.remove(path)) {
                closed.add(path);
            }
            rebuildRows();
        });
        return line;
    }

    /**
     * A group of changes: its folder and when it happened, what it is made of, and -- for a
     * suspicious one -- what the gate says about it. A click opens or closes it; its tick and
     * counts cover the group's rows the filter shows, open or closed.
     */
    private Node groupRow(GroupRow item) {
        String id = item.id();
        ChangeClusters.Cluster cluster = item.cluster();
        List<String> paths = paths(item.entries());
        List<SyncAction> actions = item.entries().stream().map(ChangeList.Entry::action).toList();
        boolean open = openGroups.contains(id);

        ChangeTree.Ticks ticks = ChangeTree.ticks(paths, picked);
        Label box = checkBox(switch (ticks) {
            case ALL -> "✓";
            case SOME -> "–";
            case NONE -> "";
        });
        box.setOnMouseClicked(e -> {
            e.consume();
            if (ticks == ChangeTree.Ticks.ALL) {
                paths.forEach(picked::remove);
            } else {
                picked.addAll(paths);
            }
            refresh();
        });
        Region icon = new Region();
        icon.getStyleClass().add("stack-icon");
        HBox glyphs = new HBox(2, label(open ? "▾" : "▸", "caret"), icon);
        glyphs.setAlignment(Pos.CENTER);
        glyphs.getStyleClass().add("folder-glyphs");

        Label name = label(cluster == null ? Texts.t("review.group.other") : ReviewText.groupName(cluster.prefix()),
                "folder-name");
        name.setMinWidth(0);
        Label when = label(cluster == null ? Texts.t("review.group.otherHint")
                : ReviewText.groupWhen(cluster.first(), cluster.last(), ZoneId.systemDefault()), "faint", "small");
        when.setMinWidth(0);
        HBox head = new HBox(8, name, when);
        head.setAlignment(Pos.BASELINE_LEFT);
        head.setMinWidth(0);
        Label makeup = label(cluster == null ? SizeFormat.format(ReviewText.totalSize(actions, result::factsFor))
                : ReviewText.groupMakeup(actions, result::factsFor), "folder-meta");
        makeup.setMinWidth(0);
        VBox text = new VBox(2, head, makeup);
        text.setMinWidth(0);
        HBox.setHgrow(text, Priority.ALWAYS);
        boolean waiting = false;
        if (cluster != null && cluster.suspicious()) {
            RiskGate.Item gateItem = gate.item(id).orElseThrow();
            waiting = !gate.isAcknowledged(id) && !gate.isGroupSkipped(id);
            Label warning = label("⚠ " + gateItem.title(), "small", waiting ? "risk-tag" : "muted");
            warning.setMinWidth(0);
            text.getChildren().add(warning);
        } else {
            long odd = paths.stream().filter(this::showsOdd).count();
            if (odd > 0) {
                text.getChildren().add(label(Texts.t("review.odd.tag", odd), "odd-line"));
            }
        }

        long skippedCount = paths.stream().filter(this::isSkipped).count();
        HBox row = new HBox(10, box, glyphs, text,
                fixed(label(ReviewText.groupCount(paths.size(), skippedCount), "folder-meta")));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("row", "group-row");
        Tooltip.install(row, new Tooltip(Texts.t("review.group.toggle")));
        if (item.last()) {
            row.getStyleClass().add("last");
        }
        if (waiting) {
            row.getStyleClass().add("suspicious");
        }
        if (skippedCount == paths.size()) {
            row.getStyleClass().add("skipped");
        }
        row.setOnMouseClicked(e -> {
            if (!openGroups.remove(id)) {
                openGroups.add(id);
            }
            rebuildRows();
        });
        return row;
    }

    /** The path whose gate card still waits, if any; a move's hidden {@code from} half counts as its own. */
    private String pendingFlag(String path, PlanInterpreter.Rename rename) {
        if (gate.isPendingFile(path)) {
            return path;
        }
        return rename != null && gate.isPendingFile(rename.from()) ? rename.from() : null;
    }

    /** The muted lines under a row's path: what a move was, what a folder holds, the plan's note. */
    private List<Label> rowDetails(String path, PlanInterpreter.Rename rename) {
        List<Label> lines = new ArrayList<>();
        if (rename != null) {
            lines.add(label(ReviewText.rename(rename), "muted", "small"));
        }
        CheckResult.FolderContents folder = result.folders().get(path);
        if (folder != null) {
            lines.add(label(ReviewText.folderSummary(folder), "muted", "small"));
        }
        // A move's from row is hidden, so its note is shown here or nowhere.
        List<String> notePaths = rename == null ? List.of(path) : List.of(rename.from(), path);
        for (String notePath : notePaths) {
            String note = result.notes().get(notePath);
            if (note != null) {
                lines.add(label(note, "muted", "small"));
            }
        }
        return lines;
    }

    private Node resolutionPicker(String path) {
        Resolution current = resolutions.getOrDefault(path, Resolution.SKIP);
        // Merge needs the profile's backups switched on and two text files. diff3 would
        // treat a .docx as lines and, whenever the two sides' edits happen not to
        // overlap, exit 0 and corrupt *both* copies.
        String mergeBlocked = ReviewText.mergeTooltip(
                result.mergeablePaths().contains(path), profile.keepMergeBackups(), diff3Found);

        HBox picker = new HBox();
        picker.getStyleClass().add("seg");
        picker.setMinWidth(Region.USE_PREF_SIZE);
        for (Resolution option : Resolution.values()) {
            Button button = new Button(option.label());
            button.getStyleClass().add("seg-opt");
            // In a narrow row the path gives way, never a choice's label.
            button.setMinWidth(Region.USE_PREF_SIZE);
            if (option == current) {
                button.getStyleClass().add("on");
                if (option == Resolution.SKIP) {
                    button.getStyleClass().add("neutral");
                }
            }
            if (option == Resolution.MERGE && mergeBlocked != null) {
                // A disabled button shows no tooltip, so the reason hangs on a wrapper.
                button.setDisable(true);
                StackPane wrapper = new StackPane(button);
                Tooltip.install(wrapper, new Tooltip(mergeBlocked));
                picker.getChildren().add(wrapper);
                continue;
            }
            button.setOnAction(e -> {
                resolutions.put(path, option);
                afterResolution(path);
            });
            picker.getChildren().add(button);
        }
        return picker;
    }
}
