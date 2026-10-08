package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.StackPane;
import javafx.stage.DirectoryChooser;

import uz.jtscorp.filesync.config.SyncProfile;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Step 1: the profile form. Holds only what is typed; saving and checking belong to
 * {@link MainView}, which hears about them through {@link Listener}.
 */
public class SetupView {

    interface Listener {
        /** Any change to the form -- the current check no longer describes it. */
        void onEdited();

        void onSave();

        void onSaveAndCheck();
    }

    private static final List<String> POPULAR_IGNORE_PATTERNS = List.of(
            "node_modules", ".git", "build", "dist", "target",
            ".idea", ".vscode", "__pycache__", "*.tmp", ".DS_Store");

    @FXML private TextField laptopPathField;
    @FXML private TextField hddPathField;
    @FXML private TextField newIgnorePatternField;
    @FXML private FlowPane chipsPane;
    @FXML private FlowPane suggestionsPane;
    @FXML private StackPane mergeSwitch;
    @FXML private Label syncHintLabel;
    @FXML private Label statusLabel;
    @FXML private Button detailsButton;
    @FXML private Button checkButton;

    private final ObservableList<String> ignorePatterns = FXCollections.observableArrayList();
    private Listener listener;
    private boolean keepMergeBackups;
    private boolean dirty;
    /** Set while {@link #show} fills the form, so loading a profile does not count as editing it. */
    private boolean loading;
    private String error;
    private String errorDetails;

    @FXML
    private void initialize() {
        laptopPathField.textProperty().addListener((obs, was, now) -> edited());
        hddPathField.textProperty().addListener((obs, was, now) -> edited());
        ignorePatterns.addListener((ListChangeListener<String>) change -> {
            renderIgnoreRules();
            edited();
        });
        renderIgnoreRules();
        refreshFooter();
    }

    void init(Listener listener) {
        this.listener = listener;
    }

    /** @param syncHint the line between the folder fields, see {@link ProfileText#syncHint} */
    void show(SyncProfile profile, String syncHint) {
        setSyncHint(syncHint);
        loading = true;
        laptopPathField.setText(profile.laptopRoot().toString());
        hddPathField.setText(profile.hddRoot().toString());
        ignorePatterns.setAll(profile.ignorePatterns());
        keepMergeBackups = profile.keepMergeBackups();
        newIgnorePatternField.clear();
        loading = false;
        dirty = false;
        error = null;
        errorDetails = null;
        renderSwitch();
        refreshFooter();
    }

    void setSyncHint(String syncHint) {
        syncHintLabel.setText(syncHint);
    }

    SyncProfile read(String name) {
        return new SyncProfile(name, Path.of(laptopPathField.getText().strip()),
                Path.of(hddPathField.getText().strip()), List.copyOf(ignorePatterns), keepMergeBackups);
    }

    void markSaved() {
        dirty = false;
        error = null;
        errorDetails = null;
        refreshFooter();
    }

    /**
     * Shows a failure in the footer. Unison's errors carry its whole output, so only the
     * first line fits there; the rest goes behind "Tafsilotlar…".
     */
    void showError(String message) {
        int newline = message.indexOf('\n');
        error = newline < 0 ? message : message.substring(0, newline);
        errorDetails = newline < 0 ? null : message;
        refreshFooter();
    }

    private void edited() {
        if (loading) {
            return;
        }
        dirty = true;
        error = null;
        errorDetails = null;
        refreshFooter();
        listener.onEdited();
    }

    private void refreshFooter() {
        boolean missingRoot = laptopPathField.getText().isBlank() || hddPathField.getText().isBlank();
        statusLabel.setText(error != null ? error
                : Texts.t(missingRoot ? "setup.status.pick" : dirty ? "setup.status.dirty" : "setup.status.saved"));
        statusLabel.getStyleClass().setAll("label", error != null ? "risk-text" : "muted");
        detailsButton.setVisible(errorDetails != null);
        detailsButton.setManaged(errorDetails != null);
        checkButton.setDisable(missingRoot);
    }

    private void renderSwitch() {
        mergeSwitch.getStyleClass().remove("on");
        if (keepMergeBackups) {
            mergeSwitch.getStyleClass().add("on");
        }
    }

    private void renderIgnoreRules() {
        List<Node> chips = new ArrayList<>();
        for (String rule : ignorePatterns) {
            int space = rule.indexOf(' ');
            Label kind = new Label(space < 0 ? "" : rule.substring(0, space));
            kind.getStyleClass().add("chip-kind");
            Label body = new Label(rule.substring(space + 1));
            body.getStyleClass().add("chip-body");
            Button remove = new Button("×");
            remove.getStyleClass().add("chip-x");
            remove.setTooltip(new Tooltip(Texts.t("setup.remove")));
            remove.setOnAction(e -> ignorePatterns.remove(rule));
            HBox chip = new HBox(6, kind, body, remove);
            chip.setAlignment(Pos.CENTER_LEFT);
            chip.getStyleClass().add("chip");
            chips.add(chip);
        }
        chipsPane.getChildren().setAll(chips);

        List<Node> suggestions = new ArrayList<>();
        for (String preset : POPULAR_IGNORE_PATTERNS) {
            if (!ignorePatterns.contains(SyncProfile.normalizeIgnorePattern(preset))) {
                Button add = new Button("+ " + preset);
                // An underscore would otherwise be read as a mnemonic and vanish:
                // node_modules showed as "nodemodules".
                add.setMnemonicParsing(false);
                add.getStyleClass().add("suggest");
                add.setOnAction(e -> addIgnorePattern(preset));
                suggestions.add(add);
            }
        }
        if (!suggestions.isEmpty()) {
            Label title = new Label(Texts.t("setup.quickAdd"));
            title.getStyleClass().addAll("faint", "small");
            suggestions.addFirst(title);
        }
        suggestionsPane.getChildren().setAll(suggestions);
        suggestionsPane.setVisible(!suggestions.isEmpty());
        suggestionsPane.setManaged(!suggestions.isEmpty());
    }

    private void addIgnorePattern(String pattern) {
        String normalized = SyncProfile.normalizeIgnorePattern(pattern);
        if (!normalized.isBlank() && !ignorePatterns.contains(normalized)) {
            ignorePatterns.add(normalized);
        }
    }

    /** Bound to both the "Qo'shish" button and Enter in the pattern field. */
    @FXML
    private void onAddIgnorePattern() {
        addIgnorePattern(newIgnorePatternField.getText().strip());
        newIgnorePatternField.clear();
    }

    @FXML
    private void onBrowseLaptop() {
        browseDirectory(laptopPathField);
    }

    @FXML
    private void onBrowseHdd() {
        browseDirectory(hddPathField);
    }

    private void browseDirectory(TextField target) {
        DirectoryChooser chooser = new DirectoryChooser();
        if (!target.getText().isBlank()) {
            File initial = new File(target.getText());
            if (initial.isDirectory()) {
                chooser.setInitialDirectory(initial);
            }
        }
        File selected = chooser.showDialog(target.getScene().getWindow());
        if (selected != null) {
            target.setText(selected.getAbsolutePath());
        }
    }

    @FXML
    private void onBrowseIgnoreFolder() {
        DirectoryChooser chooser = new DirectoryChooser();
        File initial = new File(!laptopPathField.getText().isBlank()
                ? laptopPathField.getText() : hddPathField.getText());
        if (initial.isDirectory()) {
            chooser.setInitialDirectory(initial);
        }
        File selected = chooser.showDialog(chipsPane.getScene().getWindow());
        if (selected == null) {
            return;
        }
        String relative = relativeToRoot(selected.toPath(), laptopPathField.getText());
        if (relative == null) {
            relative = relativeToRoot(selected.toPath(), hddPathField.getText());
        }
        if (relative == null) {
            new Alert(Alert.AlertType.ERROR, Texts.t("setup.ignoreOutside", selected)).showAndWait();
            return;
        }
        addIgnorePattern("Path " + relative);
    }

    // Null when selected isn't strictly inside rootText (including selected == root
    // itself -- ignoring the whole sync root makes no sense as an ignore rule).
    private static String relativeToRoot(Path selected, String rootText) {
        if (rootText == null || rootText.isBlank()) {
            return null;
        }
        Path root = Path.of(rootText).toAbsolutePath().normalize();
        Path normalizedSelected = selected.toAbsolutePath().normalize();
        if (!normalizedSelected.startsWith(root) || normalizedSelected.equals(root)) {
            return null;
        }
        return root.relativize(normalizedSelected).toString().replace('\\', '/');
    }

    @FXML
    private void onToggleMerge() {
        keepMergeBackups = !keepMergeBackups;
        renderSwitch();
        edited();
    }

    @FXML
    private void onShowDetails() {
        // Unison's full output does not fit an Alert's plain content text, so only the
        // first line goes there and the rest sits in an expandable, scrollable box.
        int newline = errorDetails.indexOf('\n');
        Alert alert = new Alert(Alert.AlertType.ERROR,
                newline < 0 ? errorDetails : errorDetails.substring(0, newline));
        TextArea full = new TextArea(errorDetails);
        full.setEditable(false);
        full.setWrapText(false);
        full.setPrefSize(640, 320);
        full.getStyleClass().add("log-box");
        alert.getDialogPane().setExpandableContent(full);
        alert.getDialogPane().setExpanded(true);
        alert.showAndWait();
    }

    @FXML
    private void onSave() {
        listener.onSave();
    }

    @FXML
    private void onSaveAndCheck() {
        listener.onSaveAndCheck();
    }
}
