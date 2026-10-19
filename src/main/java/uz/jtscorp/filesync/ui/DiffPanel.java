package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import javafx.collections.FXCollections;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

import uz.jtscorp.filesync.sync.ContentSignals;
import uz.jtscorp.filesync.sync.FileDiff;
import uz.jtscorp.filesync.sync.FileDiff.DiffRow;
import uz.jtscorp.filesync.sync.FileDiff.FileFacts;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.ui.CheckResult.FilePair;

import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

/**
 * The detail pane beside the review list: both copies' size and time, then the diff.
 * One instance per session, so the toolbar settings carry over to the next file.
 */
final class DiffPanel {

    private enum View { UNIFIED, SIDE_BY_SIDE }

    private final VBox root = new VBox();

    /** Unified by default: at 440px two columns leave too little of each line. */
    private View view = View.UNIFIED;
    private FileDiff.Whitespace whitespace = FileDiff.Whitespace.KEEP;
    private boolean showWhitespace;

    /** What is on screen, kept so a toolbar change can render it again. */
    private String relativePath;
    private SyncAction action;
    private Path laptopFile;
    private Path hddFile;
    /** Both copies' first bytes, read by the check; the panel never reads them itself. */
    private CheckResult.Heads heads;
    /** What the check found in this file's bytes, or null. */
    private ContentSignals.Finding finding;
    /** The anomaly model's score for this file, or null; and why the model is silent, or null. */
    private Oddity.Mark mark;
    private String modelCold;

    /** The rows as displayed, and for each the changed stretch to mark (null for none). */
    private List<DiffRow> shown = List.of();
    private List<FileDiff.Span> spans = List.of();
    /** Row indices where each difference begins, and which one the arrows last jumped to. */
    private List<Integer> blocks = List.of();
    private int currentBlock = -1;

    DiffPanel() {
        root.getStyleClass().add("detail");
    }

    Region root() {
        return root;
    }

    void clear() {
        relativePath = null;
        root.getChildren().clear();
    }

    /** @param action null for a file the risk scanner flagged but Unison's plan does not list */
    void showFile(String relativePath, SyncAction action, Path laptopFile, Path hddFile,
                  CheckResult.Heads heads, ContentSignals.Finding finding, Oddity.Mark mark, String modelCold) {
        this.finding = finding;
        this.mark = mark;
        this.modelCold = modelCold;
        this.relativePath = relativePath;
        this.action = action;
        this.laptopFile = laptopFile;
        this.hddFile = hddFile;
        this.heads = heads;
        currentBlock = -1;
        render();
    }

    /**
     * A folder about to be deleted: what goes with it, in place of a diff. Virtualized,
     * because one folder can hold thousands of files; no {@code diff} is run.
     */
    void showFolder(String relativePath, SyncAction action, CheckResult.FolderContents folder) {
        this.relativePath = relativePath;
        this.action = action;
        VBox head = new VBox(10, titleBlock());
        head.getStyleClass().add("detail-head");
        Label summary = new Label(ReviewText.folderSummary(folder));
        summary.getStyleClass().add("cmp-size");
        head.getChildren().add(summary);
        addActionLine(head);

        ListView<CheckResult.Item> list = new ListView<>(FXCollections.observableArrayList(folder.files()));
        list.getStyleClass().add("folder-list");
        list.setFocusTraversable(false);
        list.setCellFactory(lv -> new FolderItemCell());
        // An empty folder: the summary above already says so, without JavaFX's own English text.
        list.setPlaceholder(new Region());
        VBox.setVgrow(list, Priority.ALWAYS);
        root.getChildren().setAll(head, list);
    }

    /** Plain text in place of a diff -- used for Unison's raw output. */
    void showText(String title, String text) {
        relativePath = null;
        Label name = new Label(title);
        name.getStyleClass().add("detail-name");
        VBox head = new VBox(name);
        head.getStyleClass().add("detail-head");
        TextArea area = new TextArea(text);
        area.setEditable(false);
        area.getStyleClass().add("log-box");
        VBox.setVgrow(area, Priority.ALWAYS);
        root.getChildren().setAll(head, area);
    }

    /** Rebuilds everything, re-running {@code diff}, on any toolbar change. */
    private void render() {
        FileDiff.Comparison comparison = FileDiff.compare(laptopFile, hddFile, whitespace);
        root.getChildren().setAll(header(new FilePair(comparison.left(), comparison.right())));

        if (comparison.note() != null) {
            Label note = new Label(comparison.note());
            note.setWrapText(true);
            note.getStyleClass().add("detail-note");
            root.getChildren().add(note);
            // Without lines to compare, the first bytes are the evidence: %PDF against
            // random bytes, or a page of 00. Not for a copy that could not be read.
            boolean readable = comparison.left().error() == null && comparison.right().error() == null;
            if (readable && ByteRows.shown(heads.laptop(), heads.hdd())) {
                root.getChildren().add(byteView(heads.laptop(), heads.hdd()));
            }
            return;
        }

        buildRows(comparison.rows());
        ListView<Integer> list = new ListView<>(FXCollections.observableArrayList(
                IntStream.range(0, shown.size()).boxed().toList()));
        list.getStyleClass().add("diff-list");
        list.setFocusTraversable(false);
        list.setCellFactory(lv -> new LineCell());
        VBox.setVgrow(list, Priority.ALWAYS);
        root.getChildren().addAll(toolbar(list), list);
    }

    /** Lays the comparison out. In the unified view a changed line is two rows sharing the pair's span. */
    private void buildRows(List<DiffRow> rows) {
        List<DiffRow> display = new ArrayList<>();
        List<FileDiff.Span> marks = new ArrayList<>();
        for (DiffRow row : rows) {
            FileDiff.Span span = row.kind() == DiffRow.Kind.CHANGED
                    ? FileDiff.changedSpan(row.leftText(), row.rightText()) : null;
            if (view == View.UNIFIED && row.kind() == DiffRow.Kind.CHANGED) {
                display.addAll(FileDiff.toUnified(List.of(row)));
                marks.add(span);
                marks.add(span);
            } else {
                display.add(row);
                marks.add(span);
            }
        }
        shown = display;
        spans = marks;
        blocks = FileDiff.differenceBlocks(shown);
    }

    private VBox header(FilePair pair) {
        int newer = ReviewText.newerSide(pair);
        HBox cards = new HBox(8, card(true, pair.laptop(), newer < 0), card(false, pair.hdd(), newer > 0));

        VBox head = new VBox(10, titleBlock(), cards);
        head.getStyleClass().add("detail-head");
        addActionLine(head);
        // A score is shown only with a reason a person can check.
        Oddity.Mark scored = mark != null && !mark.reasons().isEmpty() ? mark : null;
        if (finding != null || scored != null) {
            head.getChildren().add(analysisCard(finding, scored, modelCold));
        } else if (modelCold != null) {
            Label cold = new Label(modelCold);
            cold.setWrapText(true);
            cold.getStyleClass().addAll("faint", "small");
            head.getChildren().add(cold);
        }
        return head;
    }

    /**
     * "Content analysis": what was measured on each copy, the flagged copy's value in risk
     * colour; under it the model's score and why, or why the model is silent.
     */
    private static VBox analysisCard(ContentSignals.Finding finding, Oddity.Mark mark, String modelCold) {
        Label title = new Label(Texts.t("diff.an.title"));
        title.getStyleClass().addAll("w500", "small");
        VBox card = new VBox(8, title);
        card.getStyleClass().addAll("cmp-card", "an-card");
        if (finding != null) {
            card.getChildren().add(signalGrid(finding));
        }
        if (mark != null) {
            card.getChildren().add(scoreRow(mark));
            Label why = analysisLabel(ReviewText.reasons(mark, " · "), "muted", "small");
            why.setWrapText(true);
            card.getChildren().add(why);
        } else if (modelCold != null) {
            Label cold = analysisLabel(modelCold, "faint", "small");
            cold.setWrapText(true);
            card.getChildren().add(cold);
        }
        return card;
    }

    /** "G'ayrioddiylik", a bar filled to the score, and the score: warn from ODD up, ok below. */
    private static HBox scoreRow(Oddity.Mark mark) {
        Region fill = new Region();
        fill.getStyleClass().addAll("score-fill", mark.score() >= Oddity.ODD ? "odd" : "usual");
        Region track = new Region();
        track.getStyleClass().add("score-track");
        StackPane bar = new StackPane(track, fill);
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        fill.maxWidthProperty().bind(bar.widthProperty().multiply(mark.score()));
        bar.setMinHeight(6);
        bar.setMaxHeight(6);
        HBox.setHgrow(bar, Priority.ALWAYS);
        HBox row = new HBox(10, analysisLabel(Texts.t("diff.an.score"), "muted", "small"), bar,
                analysisLabel(ReviewText.decimal(mark.score()), "an-value"));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private static GridPane signalGrid(ContentSignals.Finding finding) {
        ReviewText.AnalysisRow row = ReviewText.analysis(finding);
        GridPane grid = new GridPane();
        grid.setHgap(12);
        grid.setVgap(4);
        grid.add(analysisLabel(Texts.t("side.laptop"), "lap-text", "w500", "small"), 1, 0);
        grid.add(analysisLabel(Texts.t("side.hdd"), "hdd-text", "w500", "small"), 2, 0);
        grid.add(analysisLabel(row.label(), "muted", "small"), 0, 1);
        grid.add(analysisLabel(row.laptop(), "an-value", finding.laptopBad() ? "an-bad" : "an-good"), 1, 1);
        grid.add(analysisLabel(row.hdd(), "an-value", finding.laptopBad() ? "an-good" : "an-bad"), 2, 1);
        ColumnConstraints label = new ColumnConstraints();
        label.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().add(label);
        return grid;
    }

    private static Label analysisLabel(String text, String... styleClasses) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }

    /** The badge, the name and the full path of what is on screen. */
    private VBox titleBlock() {
        ReviewText.PathParts parts = ReviewText.splitPath(relativePath);
        HBox title = new HBox(8);
        title.setAlignment(Pos.CENTER_LEFT);
        if (action != null) {
            title.getChildren().add(badge(action.actionType()));
        }
        Label name = new Label(parts.name());
        name.getStyleClass().add("detail-name");
        title.getChildren().add(name);

        Label path = new Label(relativePath);
        path.getStyleClass().addAll("muted", "small");
        return new VBox(2, title, path);
    }

    private void addActionLine(VBox head) {
        if (action != null && action.actionType() != SyncAction.ActionType.CONFLICT) {
            Label line = new Label(ReviewText.actionLine(action));
            line.getStyleClass().addAll("muted", "small");
            head.getChildren().add(line);
        }
    }

    static Label badge(SyncAction.ActionType type) {
        Label badge = new Label(ReviewText.typeLetter(type));
        badge.getStyleClass().addAll("badge", "badge-" + ReviewText.typeLetter(type).toLowerCase());
        return badge;
    }

    private static VBox card(boolean laptop, FileFacts facts, boolean newer) {
        Label sideLabel = new Label(Texts.t(laptop ? "side.laptop" : "side.hdd"));
        sideLabel.getStyleClass().addAll(laptop ? "lap-text" : "hdd-text", "w500", "small");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label newerLabel = new Label(newer ? Texts.t("diff.newer") : "");
        newerLabel.getStyleClass().add("newer");
        Label size = new Label(facts.error() != null ? Texts.t("diff.unreadable")
                : facts.exists() ? ReviewText.size(facts) : Texts.t("diff.absent"));
        size.getStyleClass().add("cmp-size");
        Label time = new Label(ReviewText.time(facts, ZoneId.systemDefault()));
        time.getStyleClass().addAll("faint", "small");
        VBox card = new VBox(2, new HBox(sideLabel, spacer, newerLabel), size, time);
        card.getStyleClass().add("cmp-card");
        HBox.setHgrow(card, Priority.ALWAYS);
        card.setMaxWidth(Double.MAX_VALUE);
        return card;
    }

    /** "File start · first 32 bytes": one block per copy, the bytes that differ marked in both. */
    private static VBox byteView(byte[] laptop, byte[] hdd) {
        Label title = new Label(Texts.t("diff.hex.title", FileDiff.HEAD_BYTES));
        title.getStyleClass().addAll("w500", "small");
        HBox head = new HBox(8, title);
        head.setAlignment(Pos.BASELINE_LEFT);
        if (ByteRows.compared(laptop, hdd)) {
            Label hint = new Label(Texts.t("diff.hex.hint"));
            hint.getStyleClass().addAll("faint", "small");
            head.getChildren().add(hint);
        }
        VBox view = new VBox(10, head, byteSide(true, laptop, hdd), byteSide(false, hdd, laptop));
        view.getStyleClass().add("hex-view");
        return view;
    }

    private static VBox byteSide(boolean laptop, byte[] bytes, byte[] other) {
        Label side = new Label(Texts.t(laptop ? "side.laptop" : "side.hdd"));
        side.getStyleClass().addAll(laptop ? "lap-text" : "hdd-text", "w500", "small");
        VBox block = new VBox(3, side);
        if (bytes == null || bytes.length == 0) {
            Label none = new Label(Texts.t(bytes == null ? "diff.absent" : "diff.hex.empty"));
            none.getStyleClass().addAll("muted", "small");
            block.getChildren().add(none);
            return block;
        }
        for (ByteRows.Row row : ByteRows.of(bytes, other)) {
            Label offset = new Label(row.offset());
            offset.getStyleClass().add("hex-offset");
            HBox cells = new HBox(3);
            HBox characters = new HBox();
            for (ByteRows.Cell cell : row.cells()) {
                cells.getChildren().add(hexLabel(cell.hex(), "hex-cell", cell.differs()));
                characters.getChildren().add(hexLabel(cell.character(), "hex-char", cell.differs()));
            }
            HBox line = new HBox(8, offset, cells, characters);
            line.setAlignment(Pos.CENTER_LEFT);
            block.getChildren().add(line);
        }
        return block;
    }

    private static Label hexLabel(String text, String styleClass, boolean differs) {
        Label label = new Label(text);
        label.getStyleClass().add(styleClass);
        if (differs) {
            label.getStyleClass().add("differs");
        }
        return label;
    }

    private HBox toolbar(ListView<Integer> list) {
        HBox viewPicker = new HBox(
                viewOption(Texts.t("diff.unified"), View.UNIFIED),
                viewOption(Texts.t("diff.sideBySide"), View.SIDE_BY_SIDE));
        viewPicker.getStyleClass().add("seg");

        Button whitespaceButton = new Button(switch (whitespace) {
            case KEEP -> Texts.t("diff.ws.keep");
            case TRIM -> Texts.t("diff.ws.trim");
            case IGNORE -> Texts.t("diff.ws.ignore");
        });
        whitespaceButton.getStyleClass().addAll("btn", "btn-small");
        // The label is the one thing in this row that may be cut short, so the tooltip
        // repeats it in full: the current mode must stay readable.
        whitespaceButton.setTooltip(new Tooltip(whitespaceButton.getText()));
        whitespaceButton.setOnAction(e -> {
            FileDiff.Whitespace[] modes = FileDiff.Whitespace.values();
            whitespace = modes[(whitespace.ordinal() + 1) % modes.length];
            currentBlock = -1;
            render();
        });

        Button showWhitespaceButton = new Button("·");
        showWhitespaceButton.getStyleClass().addAll("btn", "btn-small");
        if (showWhitespace) {
            showWhitespaceButton.getStyleClass().add("on");
        }
        showWhitespaceButton.setTooltip(new Tooltip(Texts.t("diff.showWs")));
        showWhitespaceButton.setOnAction(e -> {
            showWhitespace = !showWhitespace;
            render();
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label count = new Label(countText());
        count.getStyleClass().addAll("muted", "small");

        HBox toolbar = new HBox(6, viewPicker, whitespaceButton, showWhitespaceButton, spacer, count,
                arrow("↑", Texts.t("diff.prev"), -1, list, count), arrow("↓", Texts.t("diff.next"), 1, list, count));
        // The pane is narrow: when the row does not fit, only the whitespace button's
        // label gives way, never the layout choice, the count or the arrows.
        for (Node child : toolbar.getChildren()) {
            if (child != whitespaceButton && child != spacer) {
                ((Region) child).setMinWidth(Region.USE_PREF_SIZE);
            }
        }
        viewPicker.getChildren().forEach(option -> ((Region) option).setMinWidth(Region.USE_PREF_SIZE));
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.getStyleClass().add("diff-toolbar");
        return toolbar;
    }

    private Button viewOption(String label, View option) {
        Button button = new Button(label);
        button.getStyleClass().add("seg-opt");
        if (view == option) {
            button.getStyleClass().addAll("on", "neutral");
        }
        button.setOnAction(e -> {
            view = option;
            currentBlock = -1;
            render();
        });
        return button;
    }

    private String countText() {
        if (blocks.isEmpty()) {
            return Texts.t("diff.none");
        }
        return currentBlock >= 0 ? Texts.t("diff.countAt", currentBlock + 1, blocks.size())
                : Texts.n("diff.count", blocks.size());
    }

    /** Steps to the next/previous difference, wrapping around at either end. */
    private Button arrow(String glyph, String tooltip, int step, ListView<Integer> list, Label count) {
        Button button = new Button(glyph);
        button.getStyleClass().addAll("btn", "btn-small");
        button.setTooltip(new Tooltip(tooltip));
        button.setDisable(blocks.isEmpty());
        button.setOnAction(e -> {
            currentBlock = currentBlock < 0
                    ? (step > 0 ? 0 : blocks.size() - 1)
                    : Math.floorMod(currentBlock + step, blocks.size());
            list.scrollTo(Math.max(0, blocks.get(currentBlock) - 3));
            list.refresh();
            count.setText(countText());
        });
        return button;
    }

    /** Makes spaces and tabs visible when asked; otherwise a tab is four columns. */
    private String visible(String text) {
        return showWhitespace ? text.replace(' ', '·').replace("\t", "→   ") : text.replace("\t", "    ");
    }

    /** One file inside a folder about to be deleted; an ignored one goes with the folder too. */
    private static final class FolderItemCell extends ListCell<CheckResult.Item> {

        @Override
        protected void updateItem(CheckResult.Item item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                return;
            }
            Label path = new Label(item.path());
            path.setMinWidth(0);
            HBox.setHgrow(path, Priority.ALWAYS);
            path.setMaxWidth(Double.MAX_VALUE);
            HBox line = new HBox(8, path);
            if (item.ignored()) {
                Label tag = new Label(Texts.t("review.folder.ignored"));
                tag.getStyleClass().addAll("folder-ignored", "faint", "small");
                tag.setMinWidth(Region.USE_PREF_SIZE);
                line.getChildren().add(tag);
            }
            Label size = new Label(SizeFormat.format(item.size()));
            size.getStyleClass().addAll("muted", "small");
            size.setMinWidth(Region.USE_PREF_SIZE);
            line.getChildren().add(size);
            line.setAlignment(Pos.CENTER_LEFT);
            line.getStyleClass().add("folder-item");
            setGraphic(line);
        }
    }

    private class LineCell extends ListCell<Integer> {

        @Override
        protected void updateItem(Integer index, boolean empty) {
            super.updateItem(index, empty);
            if (empty || index == null) {
                setGraphic(null);
                return;
            }
            DiffRow row = shown.get(index);
            FileDiff.Span span = spans.get(index);
            HBox line = new HBox();
            line.setAlignment(Pos.CENTER_LEFT);
            line.getStyleClass().add("diff-row");
            switch (row.kind()) {
                case CHANGED -> line.getStyleClass().add("changed");
                case LEFT_ONLY -> line.getStyleClass().add("left");
                case RIGHT_ONLY -> line.getStyleClass().add("right");
                case SAME -> { }
            }
            if (currentBlock >= 0 && blocks.get(currentBlock).equals(index)) {
                line.getStyleClass().add("current");
            }

            if (view == View.UNIFIED) {
                Label sign = new Label(row.kind() == DiffRow.Kind.LEFT_ONLY ? "−"
                        : row.kind() == DiffRow.Kind.RIGHT_ONLY ? "+" : "");
                sign.getStyleClass().addAll("sign", row.kind() == DiffRow.Kind.LEFT_ONLY ? "minus" : "plus");
                boolean leftSide = row.leftText() != null;
                line.getChildren().addAll(number(row.leftNumber()), number(row.rightNumber()), sign,
                        text(leftSide ? row.leftText() : row.rightText(), span, leftSide, -1));
            } else {
                line.getChildren().addAll(
                        number(row.leftNumber()), text(row.leftText(), span, true, 170),
                        number(row.rightNumber()), text(row.rightText(), span, false, 170));
            }
            setGraphic(line);
        }

        private Label number(Integer value) {
            Label label = new Label(value == null ? "" : String.valueOf(value));
            label.getStyleClass().add("ln");
            return label;
        }

        /** @param width fixed column width, or -1 to take the line's natural width */
        private HBox text(String value, FileDiff.Span span, boolean leftSide, double width) {
            HBox box = new HBox();
            box.setAlignment(Pos.CENTER_LEFT);
            if (width > 0) {
                box.setMinWidth(width);
                box.setPrefWidth(width);
                box.setMaxWidth(width);
            }
            if (value == null) {
                return box;
            }
            if (span == null) {
                box.getChildren().add(new Label(visible(value)));
                return box;
            }
            int end = leftSide ? span.leftEnd() : span.rightEnd();
            Label changed = new Label(visible(value.substring(span.start(), end)));
            changed.getStyleClass().add("hl");
            box.getChildren().addAll(new Label(visible(value.substring(0, span.start()))), changed,
                    new Label(visible(value.substring(end))));
            return box;
        }
    }
}
