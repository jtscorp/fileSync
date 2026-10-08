package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.List;

/** Step 4: Apply's progress, then what it did -- or why it stopped. */
public class ApplyView {

    interface Listener {
        void onNewCheck();

        void onBackToSetup();

        /** A merge left {@code path} untouched; the user picked which copy stays. */
        void onKeepSide(String path, boolean laptop);
    }

    @FXML private VBox runningPane;
    @FXML private ProgressBar progress;
    @FXML private Label phaseLabel;
    @FXML private VBox finishedPane;
    @FXML private Label finishedCircle;
    @FXML private Label finishedTitle;
    @FXML private Label finishedSub;
    @FXML private VBox resultRows;
    @FXML private VBox warnCard;
    @FXML private Label warnBody;
    @FXML private VBox unmergedRows;
    @FXML private Button logToggle;
    @FXML private Button backButton;
    @FXML private Button newCheckButton;
    @FXML private TextArea logBox;

    private Listener listener;
    private boolean logOpen;

    @FXML
    private void initialize() {
        showRunning();
    }

    void init(Listener listener) {
        this.listener = listener;
    }

    void showRunning() {
        setShown(runningPane, true);
        setShown(finishedPane, false);
        progress.setProgress(0);
        phaseLabel.setText("");
    }

    void setPhase(String label, double fraction) {
        phaseLabel.setText(label);
        progress.setProgress(fraction);
    }

    /**
     * @param mergeWarning null unless the merge run failed; then the text for the warning card
     * @param unmerged     the files that run left untouched, each offered a side to keep
     */
    void showDone(String subline, List<ApplySummary.Row> rows, String mergeWarning, List<String> unmerged,
                  String log) {
        showFinished("✓", false, Texts.t("apply.done"), subline, log);
        resultRows.getChildren().clear();
        rows.forEach(this::addResultRow);
        unmergedRows.getChildren().clear();
        for (String path : unmerged) {
            Label name = new Label(path);
            name.getStyleClass().addAll("mono", "small");
            name.setMinWidth(0);
            HBox.setHgrow(name, Priority.ALWAYS);
            name.setMaxWidth(Double.MAX_VALUE);
            HBox line = new HBox(8, name, keepButton(path, true), keepButton(path, false));
            line.setAlignment(Pos.CENTER_LEFT);
            // Found again by path when the user's choice has been carried out.
            line.setUserData(path);
            unmergedRows.getChildren().add(line);
        }
        setShown(resultRows, true);
        setShown(warnCard, mergeWarning != null);
        warnBody.setText(mergeWarning);
        setShown(backButton, false);
        setShown(newCheckButton, true);
    }

    private Button keepButton(String path, boolean laptop) {
        Button button = new Button(Texts.t(laptop ? "apply.keepLaptop" : "apply.keepHdd"));
        button.getStyleClass().addAll("btn", "btn-compact");
        button.setMinWidth(Region.USE_PREF_SIZE);
        button.setOnAction(e -> listener.onKeepSide(path, laptop));
        return button;
    }

    /** {@code path} is settled: its buttons go, a result row and the run's output arrive. */
    void kept(String path, ApplySummary.Row row, String output) {
        unmergedRows.getChildren().removeIf(line -> path.equals(line.getUserData()));
        if (unmergedRows.getChildren().isEmpty()) {
            setShown(warnCard, false);
        }
        addResultRow(row);
        logBox.appendText(output);
    }

    /** While a kept side is being applied, nothing here may start a second run. */
    void setBusy(boolean busy) {
        unmergedRows.setDisable(busy);
        newCheckButton.setDisable(busy);
    }

    private void addResultRow(ApplySummary.Row row) {
        Label mark = new Label(switch (row.mark()) {
            case DONE -> "✓";
            case NONE -> "–";
            case WARN -> "!";
        });
        mark.getStyleClass().addAll("mark", row.mark().name().toLowerCase());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label meta = new Label(row.meta());
        meta.getStyleClass().addAll("muted", "small");
        // A long list of file names must not push the row past the card: the meta
        // text shrinks with an ellipsis, the sentence itself is never cut.
        meta.setMinWidth(0);
        meta.setTextOverrun(OverrunStyle.ELLIPSIS);
        HBox.setHgrow(meta, Priority.SOMETIMES);
        if (!row.meta().isEmpty()) {
            meta.setTooltip(new Tooltip(row.meta()));
        }
        Label text = new Label(row.text());
        text.setMinWidth(Region.USE_PREF_SIZE);
        HBox line = new HBox(10, mark, text, spacer, meta);
        line.setAlignment(Pos.CENTER_LEFT);
        line.getStyleClass().add("result-row");
        if (resultRows.getChildren().isEmpty()) {
            line.getStyleClass().add("first");
        }
        resultRows.getChildren().add(line);
    }

    /**
     * Apply stopped. The log, which opens with it, holds the failing run's output followed
     * by whatever the earlier phases printed.
     */
    void showFailed(String message, String log) {
        showFinished("!", true, Texts.t("apply.stopped"), message, log);
        setShown(resultRows, false);
        setShown(warnCard, false);
        setShown(backButton, true);
        setShown(newCheckButton, false);
        logOpen = true;
        renderLog();
    }

    private void showFinished(String glyph, boolean failed, String title, String subline, String log) {
        setShown(runningPane, false);
        setShown(finishedPane, true);
        finishedCircle.setText(glyph);
        finishedCircle.getStyleClass().remove("failed");
        if (failed) {
            finishedCircle.getStyleClass().add("failed");
        }
        finishedTitle.setText(title);
        finishedSub.setText(subline);
        logBox.setText(log);
        logOpen = false;
        renderLog();
    }

    private void renderLog() {
        logToggle.setText(Texts.t(logOpen ? "apply.hideLog" : "apply.showLog"));
        setShown(logBox, logOpen);
    }

    private static void setShown(javafx.scene.Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    @FXML
    private void onToggleLog() {
        logOpen = !logOpen;
        renderLog();
    }

    @FXML
    private void onNewCheck() {
        listener.onNewCheck();
    }

    @FXML
    private void onBack() {
        listener.onBackToSetup();
    }
}
