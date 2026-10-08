package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Rectangle;

import uz.jtscorp.filesync.config.SyncProfile;
import uz.jtscorp.filesync.sync.SyncAction;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 3: the plan, the risks that gate it, and the diff of the selected file. The
 * resolutions and the skipped changes belong to {@link MainView}.
 */
public class ReviewView {

    interface Listener {
        void onBackToSetup();

        void onApply();
    }

    /** The list is one scroll of unlike things; each row type knows how it is drawn. */
    private sealed interface Row {}
    private record StatsRow() implements Row {}
    private record RiskHeadRow() implements Row {}
    private record RiskRow(RiskGate.Item item, boolean last) implements Row {}
    private record GroupRow(String title, String hint) implements Row {}
    /** {@code first}/{@code last}: where the group's card gets its top and bottom edge. */
    private record ActionRow(SyncAction action, boolean first, boolean last) implements Row {}
    private record EmptyRow() implements Row {}
    /** The changes group's title, with what narrows and orders its rows. */
    private record ChangesHeadRow() implements Row {}
    /** The top edge of the changes card: select all, and what to do with the ticked rows. */
    private record BulkRow() implements Row {}
    /** The bottom edge of the changes card when the filter leaves it no row. */
    private record FilterEmptyRow() implements Row {}

    /** The detail pane's width in review-view.fxml. */
    private static final double DETAIL_MIN_WIDTH = 454;

    @FXML private ListView<Row> reviewList;
    @FXML private StackPane detailHost;
    @FXML private Label summaryLabel;
    @FXML private Label gateLabel;
    @FXML private Button applyButton;

    private final DiffPanel diffPanel = new DiffPanel();
    private Listener listener;
    private Map<String, Resolution> resolutions;
    private Set<String> skippedChanges;
    /** Changes ticked for the bulk bar; a tick decides nothing by itself. */
    private final Set<String> picked = new HashSet<>();
    private ChangeList.Filter filter = ChangeList.Filter.ALL;
    private ChangeList.Sort sort = ChangeList.Sort.PATH;
    private boolean sortReversed;
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

        // The strip on the panel's left edge that drags it wider. Built here, not in
        // FXML, and the width is forgotten with the window.
        Region grip = new Region();
        grip.getStyleClass().add("detail-grip");
        grip.setMaxWidth(8);
        StackPane.setAlignment(grip, Pos.CENTER_LEFT);
        grip.setOnMouseDragged(e ->
                setDetailWidth(detailHost.localToScene(detailHost.getWidth(), 0).getX() - e.getSceneX()));
        detailHost.getChildren().add(grip);
        ((Region) detailHost.getParent()).widthProperty()
                .addListener((obs, was, now) -> setDetailWidth(detailHost.getPrefWidth()));
    }

    /** Never narrower than the FXML's width, never more than half the screen: the list keeps its conflict choices. */
    private void setDetailWidth(double wanted) {
        double limit = Math.max(DETAIL_MIN_WIDTH, ((Region) detailHost.getParent()).getWidth() / 2);
        double width = Math.max(DETAIL_MIN_WIDTH, Math.min(wanted, limit));
        detailHost.setMinWidth(width);
        detailHost.setPrefWidth(width);
        detailHost.setMaxWidth(width);
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
        filter = ChangeList.Filter.ALL;
        rebuildRows();

        // What most needs a look comes up first: a risky file, else a conflict.
        String first = gate.items().stream().filter(RiskGate.Item::isFile).map(RiskGate.Item::path).findFirst()
                .or(() -> result.conflicts().stream().map(SyncAction::relativePath).findFirst())
                .or(() -> shownChanges().stream().map(SyncAction::relativePath).findFirst())
                .orElse(null);
        if (first != null) {
            select(first);
        } else {
            selectedPath = null;
            diffPanel.clear();
            refresh();
        }
    }

    /** The changes group as the filter and the order leave it. */
    private List<SyncAction> shownChanges() {
        return ChangeList.shown(result.changes(), result::factsFor, filter, sort, sortReversed);
    }

    /** The rows themselves change only with a new check, the filter or the order. */
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
            rows.add(new GroupRow(Texts.t("review.conflicts", conflicts.size()),
                    Texts.t("review.conflictsHint")));
            addGroup(rows, conflicts);
        }
        if (!result.changes().isEmpty()) {
            rows.add(new ChangesHeadRow());
            rows.add(new BulkRow());
            List<SyncAction> shown = shownChanges();
            // The bulk bar is the card's top edge, so no change row is ever the first.
            for (int i = 0; i < shown.size(); i++) {
                rows.add(new ActionRow(shown.get(i), false, i == shown.size() - 1));
            }
            if (shown.isEmpty()) {
                rows.add(new FilterEmptyRow());
            }
        }
        if (result.actions().isEmpty()) {
            rows.add(new EmptyRow());
        }
        reviewList.getItems().setAll(rows);
    }

    private static void addGroup(List<Row> rows, List<SyncAction> actions) {
        for (int i = 0; i < actions.size(); i++) {
            rows.add(new ActionRow(actions.get(i), i == 0, i == actions.size() - 1));
        }
    }

    void clear() {
        profile = null;
        result = null;
        gate = null;
        selectedPath = null;
        picked.clear();
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
        diffPanel.showFile(path, result.actionFor(path).orElse(null),
                profile.laptopRoot().resolve(path), profile.hddRoot().resolve(path));
        refresh();
    }

    /** Left out of this Apply: a risky file at the gate, an ordinary change in the list. */
    private boolean isSkipped(String path) {
        return gate.isSkipped(path) || skippedChanges.contains(path);
    }

    /** A file the scanner flagged keeps its skip in the gate, where it also counts as decided. */
    private void setSkipped(String path, boolean value) {
        if (gate.isFileItem(path)) {
            gate.setSkipped(path, value);
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
        int moving = (int) result.changes().stream().filter(a -> !isSkipped(a.relativePath())).count();
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
                case GroupRow r -> group(r);
                case ActionRow r -> actionRow(r);
                case EmptyRow r -> emptyRow();
                case ChangesHeadRow r -> changesHead();
                case BulkRow r -> bulkRow();
                case FilterEmptyRow r -> filterEmptyRow();
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
        VBox box = new VBox(grid);
        box.getStyleClass().add("stats-row");
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

    private Node riskItem(RiskGate.Item item, boolean last) {
        boolean acknowledged = gate.isAcknowledged(item.key());
        boolean skipped = gate.isSkipped(item.key());

        Label title = label(item.title(), item.isFile() || RiskGate.EXIT_ONE_KEY.equals(item.key())
                ? "risk-path" : "w500");
        HBox top = new HBox(10, title);
        top.setAlignment(Pos.CENTER_LEFT);
        if (item.isFile()) {
            title.setOnMouseClicked(e -> select(item.path()));
            top.getChildren().add(label(ReviewText.riskShort(
                    result.actionFor(item.path()).orElse(null), result.factsFor(item.path())), "risk-tag"));
        } else if (RiskGate.EXIT_ONE_KEY.equals(item.key())) {
            // Unison's raw output is the only place that says what was skipped.
            title.setOnMouseClicked(e -> {
                selectedPath = null;
                diffPanel.showText(Texts.t("review.unisonOutput"), result.rawOutput());
                reviewList.refresh();
            });
        }
        top.getChildren().addAll(spacer(),
                label(ReviewText.riskState(acknowledged, skipped), "muted", "small"));

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
        HBox decisions = new HBox(8, acknowledge);
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
            decisions.getChildren().add(skip);
        }

        VBox inner = new VBox(8, top, reason, decisions);
        inner.getStyleClass().add("risk-inner");
        VBox outer = new VBox(inner);
        outer.getStyleClass().add("risk-item");
        if (last) {
            outer.getStyleClass().add("last");
        }
        return outer;
    }

    private void afterDecision(RiskGate.Item item) {
        // Same file already shown: re-running diff would change nothing.
        if (!item.isFile() || item.path().equals(selectedPath)) {
            refresh();
        } else {
            select(item.path());
        }
    }

    private Node group(GroupRow row) {
        HBox head = new HBox(10, label(row.title(), "group-title"), label(row.hint(), "faint", "small"));
        head.setAlignment(Pos.BASELINE_LEFT);
        head.getStyleClass().add("group-head");
        return head;
    }

    private Node changesHead() {
        List<SyncAction> changes = result.changes();
        HBox sorts = new HBox();
        sorts.getStyleClass().addAll("seg", "sort-seg");
        for (ChangeList.Sort option : ChangeList.Sort.values()) {
            Button button = new Button(option.label(option == sort, sortReversed));
            button.getStyleClass().add("seg-opt");
            if (option == sort) {
                button.getStyleClass().addAll("on", "neutral");
            }
            button.setOnAction(e -> {
                sortReversed = option == sort && !sortReversed;
                sort = option;
                rebuildRows();
            });
            sorts.getChildren().add(button);
        }
        HBox top = new HBox(10, label(Texts.t("review.changes", changes.size()), "group-title"), spacer(), sorts);
        top.setAlignment(Pos.CENTER_LEFT);

        // On their own line: beside the title and the orders they do not fit the list's width.
        HBox filters = new HBox(4);
        for (ChangeList.Filter option : ChangeList.Filter.values()) {
            Region dot = new Region();
            dot.getStyleClass().addAll("dot", "dot-" + tone(option));
            HBox content = new HBox(6, dot, label(option.label(), "filter-name"),
                    label(String.valueOf(ChangeList.count(changes, option)), "filter-count"));
            content.setAlignment(Pos.CENTER_LEFT);
            Button button = new Button(null, content);
            button.getStyleClass().addAll("filter", tone(option));
            if (option == filter) {
                button.getStyleClass().add("on");
            }
            button.setOnAction(e -> {
                filter = option;
                rebuildRows();
            });
            filters.getChildren().add(button);
        }
        VBox head = new VBox(8, top, filters);
        head.getStyleClass().add("group-head");
        return head;
    }

    /** The colour a kind of change is drawn in: its filter's dot and its row's direction. */
    private static String tone(ChangeList.Filter kind) {
        return switch (kind) {
            case ALL -> "faint";
            case TO_HDD -> "hdd";
            case TO_LAPTOP -> "lap";
            case DELETES -> "risk";
        };
    }

    /** The ticked rows still on screen; a tick the filter has hidden is not acted on. */
    private List<String> pickedShown() {
        return shownChanges().stream().map(SyncAction::relativePath).filter(picked::contains).toList();
    }

    private static Label checkBox(String mark) {
        Label box = label(mark, "pick");
        if (!mark.isEmpty()) {
            box.getStyleClass().add("on");
        }
        return box;
    }

    private Node bulkRow() {
        List<String> shown = shownChanges().stream().map(SyncAction::relativePath).toList();
        List<String> ticked = pickedShown();
        boolean all = !shown.isEmpty() && ticked.size() == shown.size();

        Label box = checkBox(all ? "✓" : ticked.isEmpty() ? "" : "–");
        Tooltip.install(box, new Tooltip(Texts.t("review.selectAll")));
        box.setOnMouseClicked(e -> {
            if (all) {
                shown.forEach(picked::remove);
            } else {
                picked.addAll(shown);
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
            // A glyph, not the words: the three buttons and the count share one narrow row.
            Button cancel = bulkButton("×", "btn-ghost");
            cancel.setTooltip(new Tooltip(Texts.t("review.clearPicked")));
            cancel.setOnAction(e -> {
                picked.clear();
                refresh();
            });
            row.getChildren().addAll(skip, apply, cancel);
        }
        return row;
    }

    private static Button bulkButton(String text, String kind) {
        Button button = new Button(text);
        button.getStyleClass().addAll(kind, "bulk-btn");
        button.setMinWidth(Region.USE_PREF_SIZE);
        return button;
    }

    private Node filterEmptyRow() {
        HBox row = new HBox(label(Texts.t("review.filterEmpty"), "muted"));
        row.setAlignment(Pos.CENTER_LEFT);
        row.getStyleClass().addAll("row", "last", "plain");
        return row;
    }

    private Node emptyRow() {
        return label(Texts.t("review.empty"), "empty-row");
    }

    private Node actionRow(ActionRow item) {
        SyncAction action = item.action();
        String path = action.relativePath();
        ReviewText.PathParts parts = ReviewText.splitPath(path);

        HBox name = new HBox(label(parts.dir(), "path-dir"), label(parts.name(), "path-name"));
        name.setAlignment(Pos.CENTER_LEFT);
        name.setMinWidth(0);
        HBox.setHgrow(name, Priority.ALWAYS);

        boolean conflict = action.actionType() == SyncAction.ActionType.CONFLICT;
        HBox row = new HBox(10);
        if (!conflict) {
            Label box = checkBox(picked.contains(path) ? "✓" : "");
            box.setOnMouseClicked(e -> {
                // A tick is not a look at the file: the row under it stays unselected.
                e.consume();
                if (!picked.remove(path)) {
                    picked.add(path);
                }
                refresh();
            });
            row.getChildren().add(box);
        }
        row.getChildren().addAll(DiffPanel.badge(action.actionType()), name);
        row.setAlignment(Pos.CENTER_LEFT);
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
        boolean skipped = isSkipped(path);
        if (skipped) {
            row.getStyleClass().add("skipped");
        }
        row.setOnMouseClicked(e -> select(path));

        if (gate.isPendingFile(path)) {
            row.getChildren().add(label("⚠ " + ReviewText.riskShort(action, result.factsFor(path)), "risk-tag"));
        }
        if (conflict) {
            Node picker = resolutionPicker(path);
            // Skipping wins over any choice here, so the choice must not look live.
            picker.setDisable(skipped);
            row.getChildren().add(picker);
        } else {
            row.getChildren().addAll(
                    label(ReviewText.sizes(action, result.factsFor(path)), "sizes"),
                    skipped ? label(ReviewText.skippedDirection(), "dir-text", "dir-skip")
                            : label(ReviewText.direction(action), "dir-text",
                                    "dir-" + tone(ChangeList.Filter.of(action))));
        }
        return row;
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
                if (path.equals(selectedPath)) {
                    refresh();
                } else {
                    select(path);
                }
            });
            picker.getChildren().add(button);
        }
        return picker;
    }
}
