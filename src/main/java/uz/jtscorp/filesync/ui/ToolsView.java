package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import uz.jtscorp.filesync.sync.ToolCheck;

/** The "required programs" screen: what was found, and how to install what is missing. */
public class ToolsView {

    interface Listener {
        void onRecheck();

        void onContinue();
    }

    @FXML private VBox toolRows;
    @FXML private Label footerLabel;
    @FXML private Button recheckButton;
    @FXML private Button continueButton;

    private Listener listener;
    private ToolCheck.Report report;
    /** Which system's instructions are open; starts on the one the app runs on. */
    private ToolGuide.Os os = ToolGuide.Os.from(System.getProperty("os.name"));
    /** The text last copied, so its button can say so until something else is copied. */
    private String copied;

    void init(Listener listener) {
        this.listener = listener;
    }

    void show(ToolCheck.Report report) {
        this.report = report;
        copied = null;
        setChecking(false);
        render();
    }

    /** Between a click on "Qayta tekshirish" and its answer. */
    void setChecking(boolean checking) {
        recheckButton.setDisable(checking);
        recheckButton.setText(Texts.t(checking ? "tools.checking" : "tools.recheck"));
    }

    private void render() {
        toolRows.getChildren().clear();
        for (ToolCheck.Status status : report.statuses()) {
            VBox row = new VBox(10, head(status));
            row.getStyleClass().add("tool-row");
            if (toolRows.getChildren().isEmpty()) {
                row.getStyleClass().add("first");
            }
            if (!status.found()) {
                row.getChildren().add(instructions(status.tool()));
            }
            toolRows.getChildren().add(row);
        }
        footerLabel.setText(ToolGuide.footerLine(report));
        // Without Unison there is nothing to continue to.
        continueButton.setDisable(!report.found(ToolCheck.Tool.UNISON));
    }

    private static Label label(String text, String... styleClasses) {
        Label label = new Label(text);
        label.getStyleClass().addAll(styleClasses);
        return label;
    }

    private HBox head(ToolCheck.Status status) {
        Label icon = label(status.found() ? "✓" : "!", "task-icon", status.found() ? "done" : "missing");
        Label version = status.found()
                ? label(status.version() == null ? Texts.t("tools.found") : status.version(), "mono", "muted", "small")
                : label(Texts.t("tools.missing"), "warn-text", "small", "w500");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(12, icon, label(status.tool().command(), "mono", "w500"), version, spacer,
                label(ToolGuide.purpose(status.tool()), "muted", "small"));
        head.setAlignment(Pos.CENTER_LEFT);
        return head;
    }

    private VBox instructions(ToolCheck.Tool tool) {
        ToolGuide.Guide guide = ToolGuide.guide(tool, os);

        HBox systems = new HBox();
        systems.getStyleClass().add("seg");
        for (ToolGuide.Os option : ToolGuide.Os.values()) {
            Button button = new Button(option.label());
            button.getStyleClass().add("seg-opt");
            if (option == os) {
                button.getStyleClass().addAll("on", "neutral");
            }
            button.setOnAction(e -> {
                os = option;
                render();
            });
            systems.getChildren().add(button);
        }
        // The segment hugs its three labels instead of stretching across the card.
        HBox systemsLine = new HBox(systems);

        Label code = label(guide.copyText(), "code-box");
        code.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(code, Priority.ALWAYS);
        Button copy = new Button(Texts.t(guide.copyText().equals(copied) ? "tools.copied" : "tools.copy"));
        copy.getStyleClass().addAll("btn", "btn-compact");
        copy.setMinWidth(Region.USE_PREF_SIZE);
        copy.setOnAction(e -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(guide.copyText());
            Clipboard.getSystemClipboard().setContent(content);
            copied = guide.copyText();
            render();
        });
        HBox codeLine = new HBox(8, code, copy);
        codeLine.setAlignment(Pos.CENTER_LEFT);

        VBox box = new VBox(8, label(ToolGuide.consequence(tool), "muted"), systemsLine, codeLine);
        for (String step : guide.steps()) {
            Label line = label(step, "muted", "small");
            line.setWrapText(true);
            box.getChildren().add(line);
        }
        box.getStyleClass().add("tool-guide");
        return box;
    }

    @FXML
    private void onRecheck() {
        listener.onRecheck();
    }

    @FXML
    private void onContinue() {
        listener.onContinue();
    }
}
