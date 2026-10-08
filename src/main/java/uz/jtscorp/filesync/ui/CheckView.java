package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

/** Step 2: shows which of the Check task's three passes is running. */
public class CheckView {

    /** The three passes of a check; each has a title and a line for what it does while it runs. */
    private static final int STAGES = 3;

    private static String title(int stage) {
        return Texts.t("check.stage" + (stage + 1) + ".title");
    }

    private static String running(int stage) {
        return Texts.t("check.stage" + (stage + 1) + ".sub");
    }

    @FXML private ProgressBar progress;
    @FXML private VBox taskRows;

    private final Label[] icons = new Label[STAGES];
    private final Label[] details = new Label[STAGES];

    @FXML
    private void initialize() {
        for (int i = 0; i < STAGES; i++) {
            icons[i] = new Label();
            Label title = new Label(title(i));
            title.getStyleClass().add("w500");
            details[i] = new Label();
            details[i].getStyleClass().addAll("muted", "small");
            HBox row = new HBox(12, icons[i], new VBox(2, title, details[i]));
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("task-row");
            taskRows.getChildren().add(row);
        }
        reset();
    }

    void reset() {
        for (int i = 0; i < STAGES; i++) {
            show(i, i == 0 ? "running" : null, running(i));
        }
        progress.setProgress(0);
    }

    /** Marks pass {@code index} finished with its result and the next one as running. */
    void stageDone(int index, String doneText) {
        show(index, "done", doneText);
        if (index + 1 < STAGES) {
            show(index + 1, "running", running(index + 1));
        }
        progress.setProgress((index + 1) / (double) STAGES);
    }

    private void show(int index, String state, String detail) {
        icons[index].getStyleClass().setAll("label", "task-icon");
        if (state != null) {
            icons[index].getStyleClass().add(state);
        }
        icons[index].setText("done".equals(state) ? "✓" : "running".equals(state) ? "…" : String.valueOf(index + 1));
        details[index].setText(detail);
    }
}
