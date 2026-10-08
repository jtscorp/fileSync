package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.text.Font;
import javafx.util.Duration;

import uz.jtscorp.filesync.config.AppSettings;
import uz.jtscorp.filesync.config.ProfileStats;
import uz.jtscorp.filesync.config.ProfileStore;
import uz.jtscorp.filesync.config.SyncProfile;
import uz.jtscorp.filesync.sync.FileDiff;
import uz.jtscorp.filesync.sync.RiskFlag;
import uz.jtscorp.filesync.sync.RiskScanner;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.sync.SyncPlanParser;
import uz.jtscorp.filesync.sync.ToolCheck;
import uz.jtscorp.filesync.sync.UnisonRunner;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.UnaryOperator;

/**
 * Controller for {@code main-view.fxml}: the sidebar, the stepper and the step on
 * screen. The step views only draw and report clicks; the Check and Apply tasks run here.
 */
public class MainView {

    private static final List<String> FONTS = List.of(
            "Outfit-Regular.ttf", "Outfit-Medium.ttf", "Outfit-SemiBold.ttf",
            "IBMPlexMono-Regular.ttf", "IBMPlexMono-Medium.ttf");

    private final ProfileStore profileStore;
    private final AppSettings settings;
    /** Rebuilds the window in the language just chosen, opening on the named profile. */
    private final Consumer<String> reload;
    private final MenuButton languageButton = new MenuButton();
    private final UnisonRunner unisonRunner = new UnisonRunner();
    private final RiskScanner riskScanner = new RiskScanner();
    private final ToolCheck toolCheck = new ToolCheck();

    @FXML private ListView<SyncProfile> profileListView;
    @FXML private Button newProfileButton;
    @FXML private Label unisonVersionLabel;
    @FXML private Label toolsWarning;
    @FXML private HBox hddStatus;
    @FXML private Region hddDot;
    @FXML private Label hddLabel;
    @FXML private BorderPane mainPane;
    @FXML private Label profileTitle;
    @FXML private HBox stepper;
    @FXML private Button step1Button;
    @FXML private Button step2Button;
    @FXML private Button step3Button;
    @FXML private Button step4Button;

    // fx:include injects the included root as <id> and its controller as <id>Controller.
    @FXML private Parent setupView;
    @FXML private Parent checkView;
    @FXML private Parent reviewView;
    @FXML private Parent applyView;
    @FXML private SetupView setupViewController;
    @FXML private CheckView checkViewController;
    @FXML private ReviewView reviewViewController;
    @FXML private ApplyView applyViewController;
    @FXML private Parent toolsView;
    @FXML private ToolsView toolsViewController;

    private SyncProfile currentProfile;
    private int step = 1;
    /** Whether step 4 has something to show; it stays reachable until the next check. */
    private boolean applyShown;
    /** Set while the profile list is rebuilt, so reselecting the same profile is not a switch. */
    private boolean refreshingProfiles;
    /** Null until the first probe of the external programs has answered. */
    private ToolCheck.Report tools;
    private boolean toolsShown;
    /** A Check or Apply is running; the tools screen must not cover its progress. */
    private boolean busy;

    private final Map<String, Resolution> conflictResolutions = new HashMap<>();
    /** Ordinary changes left out of this Apply in the review list; risky files' skips live in the gate. */
    private final Set<String> skippedChanges = new HashSet<>();
    /** Conflicts whose two copies both look like text; probed once per check, off the UI thread. */
    private final Set<String> mergeablePaths = new HashSet<>();
    private CheckResult lastCheck;
    private byte[] lastCheckedPrfHash;
    private RiskGate gate;

    /** Last sync time and size per profile name, reread with the profile list. */
    private final Map<String, ProfileStats> profileStats = new HashMap<>();

    /** The HDD folder the sidebar's "connected" line is about. Probed off the UI thread. */
    private volatile Path watchedHddRoot;
    private final ScheduledExecutorService hddWatch = Executors.newSingleThreadScheduledExecutor(task -> {
        Thread thread = new Thread(task, "hdd-watch");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * Apply's output plus, when a phase failed without invalidating the rest, why.
     *
     * @param unmerged the merge paths whose two copies still differ after the merge run
     */
    private record ApplyResult(String output, String warning, int batchExitCode, List<String> unmerged) {}

    /**
     * The files a merge left untouched. Lets "keep this side" run without a new check,
     * for these paths only and while the .prf is unchanged.
     */
    private record MergeLeftover(SyncProfile profile, byte[] prfHash, Set<String> unmerged) {}

    private MergeLeftover mergeLeftover;

    /** Exit code 1 means Unison skipped items, which the risk gate shows; 2 and above is a failure. */
    private static String requireSuccess(UnisonRunner.Result result) throws IOException {
        if (result.exitCode() >= 2) {
            throw new IOException(Texts.t("error.unisonFailed", result.exitCode()) + "\n" + result.output());
        }
        return result.output();
    }

    /** Same, for a later phase: the earlier phases' output is kept in the error. */
    private static String requireSuccess(UnisonRunner.Result result, CharSequence earlierOutput)
            throws IOException {
        try {
            return requireSuccess(result);
        } catch (IOException e) {
            if (earlierOutput.isEmpty()) {
                throw e;
            }
            throw new IOException(e.getMessage() + "\n\n" + Texts.t("error.earlierOutput") + "\n" + earlierOutput, e);
        }
    }

    /**
     * The paths whose two copies are not byte-identical: what a merge run did not settle.
     * A copy that cannot be read counts as unsettled rather than as merged.
     */
    private static List<String> stillDifferent(SyncProfile profile, List<String> paths) {
        List<String> different = new ArrayList<>();
        for (String path : paths) {
            try {
                if (Files.mismatch(profile.laptopRoot().resolve(path), profile.hddRoot().resolve(path)) != -1) {
                    different.add(path);
                }
            } catch (IOException e) {
                different.add(path);
            }
        }
        return different;
    }

    private static boolean isConfigured(SyncProfile profile) {
        return !profile.laptopRoot().toString().isBlank() && !profile.hddRoot().toString().isBlank();
    }

    private static byte[] hashFile(Path file) throws IOException {
        try {
            return java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 mavjud emas", e);
        }
    }

    /**
     * Returns a user-facing error, or {@code null} if the roots are usable. A blank root
     * is checked explicitly: {@code Path.of("")} is the working directory, which exists.
     */
    private static String validateRoots(SyncProfile profile) {
        if (profile.laptopRoot().toString().isBlank() || profile.hddRoot().toString().isBlank()) {
            return Texts.t("roots.pick");
        }
        if (profile.laptopRoot().equals(profile.hddRoot())) {
            return Texts.t("roots.same", profile.laptopRoot());
        }
        if (!Files.isDirectory(profile.laptopRoot())) {
            return Texts.t("roots.laptopMissing", profile.laptopRoot());
        }
        if (!Files.isDirectory(profile.hddRoot())) {
            return Texts.t("roots.hddMissing", profile.hddRoot());
        }
        return null;
    }

    public MainView(ProfileStore profileStore, AppSettings settings, Consumer<String> reload) {
        this.profileStore = profileStore;
        this.settings = settings;
        this.reload = reload;
    }

    /** @param selectProfile the profile to open on, or null for none */
    public Scene createScene(String selectProfile) {
        for (String font : FONTS) {
            // A missing font only costs the look; the app still works on the fallback.
            try (InputStream in = MainView.class.getResourceAsStream("fonts/" + font)) {
                if (in != null) {
                    Font.loadFont(in, 13);
                }
            } catch (IOException e) {
                // Same reasoning: an unreadable font file is a cosmetic loss, not a failure.
            }
        }
        FXMLLoader loader = new FXMLLoader(MainView.class.getResource("main-view.fxml"));
        // This instance already holds the ProfileStore, so it is the controller rather
        // than something FXMLLoader constructs for us.
        loader.setController(this);
        loader.setResources(Texts.bundle());
        Parent root;
        try {
            root = loader.load();
        } catch (IOException e) {
            // A missing or malformed FXML is a broken build, not a runtime condition the
            // caller could do anything about.
            throw new UncheckedIOException("main-view.fxml yuklanmadi", e);
        }
        refreshProfileList();
        profileListView.getItems().stream().filter(p -> p.name().equals(selectProfile)).findFirst()
                .ifPresent(p -> profileListView.getSelectionModel().select(p));

        Scene scene = new Scene(root, 1180, 760);
        scene.getStylesheets().add(Objects.requireNonNull(MainView.class.getResource("filesync.css")).toExternalForm());
        return scene;
    }

    /** Runs once, after FXMLLoader has injected every {@code fx:id} above. */
    @FXML
    private void initialize() {
        profileListView.setCellFactory(lv -> new ProfileCell());
        profileListView.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> {
            if (!refreshingProfiles) {
                onProfileSelected(now);
            }
        });

        setupViewController.init(new SetupView.Listener() {
            @Override
            public void onEdited() {
                // The plan on the review screen was made for the saved profile, not for
                // what is being typed now.
                clearCheckResult();
                updateStepper();
            }

            @Override
            public void onSave() {
                saveProfile();
            }

            @Override
            public void onSaveAndCheck() {
                saveAndCheck();
            }
        });
        reviewViewController.init(new ReviewView.Listener() {
            @Override
            public void onBackToSetup() {
                goTo(1);
            }

            @Override
            public void onApply() {
                startApply();
            }
        }, conflictResolutions, skippedChanges);
        applyViewController.init(new ApplyView.Listener() {
            @Override
            public void onNewCheck() {
                applyShown = false;
                mergeLeftover = null;
                goTo(1);
            }

            @Override
            public void onBackToSetup() {
                applyShown = false;
                mergeLeftover = null;
                goTo(1);
            }

            @Override
            public void onKeepSide(String path, boolean laptop) {
                keepSide(path, laptop);
            }
        });

        toolsViewController.init(new ToolsView.Listener() {
            @Override
            public void onRecheck() {
                toolsViewController.setChecking(true);
                checkTools(false);
            }

            @Override
            public void onContinue() {
                showTools(false);
            }
        });
        unisonVersionLabel.setOnMouseClicked(e -> showTools(true));
        toolsWarning.setOnMouseClicked(e -> showTools(true));
        toolsWarning.setVisible(false);
        toolsWarning.setManaged(false);

        buildLanguageButton();
        showTools(false);
        goTo(1);
        watchHdd(null);
        hddWatch.scheduleWithFixedDelay(this::probeHdd, 5, 5, TimeUnit.SECONDS);
        checkTools(true);
    }

    private static ImageView flag(Texts.Language language) {
        ImageView view = new ImageView(
                new Image(MainView.class.getResourceAsStream("flags/" + language.code() + ".png")));
        view.setFitWidth(22);
        view.setFitHeight(15);
        return view;
    }

    /** Built here, not in the FXML: a MenuButton there needs its own native-image reflection entries. */
    private void buildLanguageButton() {
        languageButton.setGraphic(flag(Texts.language()));
        languageButton.getStyleClass().add("lang-button");
        languageButton.setTooltip(new Tooltip(Texts.t("main.language")));
        for (Texts.Language option : Texts.Language.values()) {
            MenuItem item = new MenuItem(option.label(), flag(option));
            item.setOnAction(e -> changeLanguage(option));
            languageButton.getItems().add(item);
        }
        // Floats in the window's top right corner, over whichever screen is showing --
        // the header's right end is empty, and the tools screen has no header at all.
        StackPane content = (StackPane) mainPane.getParent();
        StackPane.setAlignment(languageButton, Pos.TOP_RIGHT);
        StackPane.setMargin(languageButton, new Insets(18, 16, 0, 0));
        content.getChildren().add(languageButton);
    }

    /**
     * The check on screen, if any, is dropped with the old views: its risk reasons and
     * notes were written in the old language. Unsaved edits to the form go the same way.
     */
    private void changeLanguage(Texts.Language chosen) {
        if (busy || chosen == Texts.language()) {
            return;
        }
        Texts.use(chosen);
        try {
            settings.saveLanguage(chosen.code());
        } catch (IOException e) {
            // The choice still holds for this session; only the next launch forgets it.
        }
        hddWatch.shutdownNow();
        reload.accept(currentProfile == null ? null : currentProfile.name());
    }

    /**
     * Probes the external programs off the UI thread -- each probe starts a process.
     *
     * @param openIfMissing on launch a missing program opens the tools screen by itself;
     *                      a recheck from that screen only redraws it
     */
    private void checkTools(boolean openIfMissing) {
        Thread probe = new Thread(() -> {
            ToolCheck.Report report = toolCheck.run();
            Platform.runLater(() -> {
                tools = report;
                String version = report.version(ToolCheck.Tool.UNISON);
                unisonVersionLabel.setText(!report.found(ToolCheck.Tool.UNISON) ? Texts.t("main.unisonMissing")
                        : version == null ? "unison" : "unison " + version);
                String missing = ToolGuide.missingLine(report);
                toolsWarning.setText(missing);
                toolsWarning.setVisible(!missing.isEmpty());
                toolsWarning.setManaged(!missing.isEmpty());
                toolsViewController.show(report);
                if (openIfMissing && !report.missing().isEmpty()) {
                    showTools(true);
                }
            });
        }, "tool-check");
        probe.setDaemon(true);
        probe.start();
    }

    /** The tools screen sits over the steps; the steps show only with a profile selected. */
    private void showTools(boolean shown) {
        if (shown && (busy || tools == null)) {
            return;
        }
        toolsShown = shown;
        toolsView.setVisible(shown);
        toolsView.setManaged(shown);
        mainPane.setVisible(!shown && currentProfile != null);
    }

    /**
     * Freezes every control that could mutate profile state or leave the running step
     * while a Check or Apply task runs.
     */
    private void setBusy(boolean busy) {
        this.busy = busy;
        for (Node control : List.of(profileListView, newProfileButton, languageButton, stepper, setupView, reviewView)) {
            control.setDisable(busy);
        }
    }

    /** Points the sidebar's HDD line at {@code profile}'s drive and asks about it right away. */
    private void watchHdd(SyncProfile profile) {
        watchedHddRoot = profile != null && !profile.hddRoot().toString().isBlank() ? profile.hddRoot() : null;
        // Hidden until the probe answers: the previous profile's answer says nothing here.
        hddStatus.setVisible(false);
        hddStatus.setManaged(false);
        hddWatch.execute(this::probeHdd);
    }

    private void probeHdd() {
        Path root = watchedHddRoot;
        if (root == null) {
            return;
        }
        boolean connected;
        try {
            connected = Files.isDirectory(root);
        } catch (RuntimeException e) {
            // An exception escaping a scheduled task ends the schedule for good.
            connected = false;
        }
        boolean found = connected;
        Platform.runLater(() -> {
            if (!root.equals(watchedHddRoot)) {
                return;
            }
            hddDot.getStyleClass().setAll("dot", found ? "dot-ok" : "dot-warn");
            hddLabel.setText(Texts.t(found ? "main.hddConnected" : "main.hddDisconnected"));
            hddStatus.setVisible(true);
            hddStatus.setManaged(true);
        });
    }

    private String syncHint(SyncProfile profile) {
        return ProfileText.syncHint(isConfigured(profile),
                profileStats.getOrDefault(profile.name(), ProfileStats.NONE));
    }

    /**
     * The stats file is display-only bookkeeping: failing to write it must never fail
     * the Check or Apply it follows.
     */
    private void updateStats(String profileName, UnaryOperator<ProfileStats> change) {
        try {
            profileStore.saveStats(profileName, change.apply(profileStore.stats(profileName)));
        } catch (IOException e) {
            // The sidebar keeps showing the previous numbers.
        }
    }

    private void goTo(int newStep) {
        step = newStep;
        List<Parent> views = List.of(setupView, checkView, reviewView, applyView);
        for (int i = 0; i < views.size(); i++) {
            views.get(i).setVisible(i + 1 == step);
            views.get(i).setManaged(i + 1 == step);
        }
        updateStepper();
    }

    /** Step 2 is never a destination; 3 needs a check and 4 needs an Apply to show. */
    private void updateStepper() {
        List<Button> buttons = List.of(step1Button, step2Button, step3Button, step4Button);
        for (int i = 0; i < buttons.size(); i++) {
            int number = i + 1;
            Button button = buttons.get(i);
            boolean active = step == number;
            boolean done = step > number;
            boolean locked = number == 2 || (number == 3 && lastCheck == null) || (number == 4 && !applyShown);
            button.getStyleClass().removeAll("active", "done");
            if (active) {
                button.getStyleClass().add("active");
            } else if (done) {
                button.getStyleClass().add("done");
            }
            ((Label) button.getGraphic()).setText(done ? "✓" : String.valueOf(number));
            button.setDisable(locked && !active);
            // The active step must not look disabled, but must not react either.
            button.setMouseTransparent(active);
        }
    }

    @FXML
    private void onStep1() {
        goTo(1);
    }

    @FXML
    private void onStep3() {
        if (lastCheck != null) {
            goTo(3);
        }
    }

    @FXML
    private void onStep4() {
        if (applyShown) {
            goTo(4);
        }
    }

    private void refreshProfileList() {
        refreshingProfiles = true;
        try {
            List<SyncProfile> profiles = profileStore.listProfiles().stream()
                    .sorted(Comparator.comparing(SyncProfile::name))
                    .toList();
            profileStats.clear();
            profiles.forEach(p -> profileStats.put(p.name(), profileStore.stats(p.name())));
            profileListView.getItems().setAll(profiles);
            if (currentProfile != null) {
                profiles.stream().filter(p -> p.name().equals(currentProfile.name())).findFirst()
                        .ifPresent(p -> profileListView.getSelectionModel().select(p));
            }
        } catch (IOException e) {
            showError(Texts.t("profiles.readFailed", e.getMessage()));
        } finally {
            refreshingProfiles = false;
        }
    }

    @FXML
    private void onNewProfile() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setHeaderText(Texts.t("profile.newName"));
        Optional<String> name = dialog.showAndWait();
        // The list reads a profile's name back from its file name, so the profile is
        // created under that name from the start.
        name.filter(n -> !n.isBlank()).map(n -> SyncProfile.sanitizeFileName(n.strip())).ifPresent(n -> {
            try {
                if (Files.exists(profileStore.profileFilePath(n))) {
                    showError(Texts.t("profile.exists", n));
                    return;
                }
                profileStore.save(new SyncProfile(n, Path.of(""), Path.of(""), List.of()));
                refreshProfileList();
                profileListView.getItems().stream().filter(p -> p.name().equals(n)).findFirst()
                        .ifPresent(p -> profileListView.getSelectionModel().select(p));
            } catch (IOException e) {
                showError(Texts.t("profile.createFailed", e.getMessage()));
            }
        });
    }

    private void deleteProfile(SyncProfile profile) {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                Texts.t("profile.deleteConfirm", profile.name()),
                ButtonType.OK, ButtonType.CANCEL);
        if (confirm.showAndWait().filter(ButtonType.OK::equals).isEmpty()) {
            return;
        }
        try {
            profileStore.delete(profile.name());
            if (currentProfile != null && currentProfile.name().equals(profile.name())) {
                profileListView.getSelectionModel().clearSelection();
            }
            refreshProfileList();
        } catch (IOException e) {
            showError(Texts.t("profile.deleteFailed", e.getMessage()));
        }
    }

    private void onProfileSelected(SyncProfile profile) {
        currentProfile = profile;
        clearCheckResult();
        applyShown = false;
        mergeLeftover = null;
        if (toolsShown && profile != null && tools != null && tools.found(ToolCheck.Tool.UNISON)) {
            // Picking a profile is as clear a "continue" as the button.
            showTools(false);
        }
        mainPane.setVisible(!toolsShown && profile != null);
        watchHdd(profile);
        if (profile != null) {
            profileTitle.setText(profile.name());
            setupViewController.show(profile, syncHint(profile));
        }
        goTo(1);
        profileListView.refresh();
    }

    /** Drops everything the last Check left, so no screen describes a plan that no longer holds. */
    private void clearCheckResult() {
        lastCheckedPrfHash = null;
        gate = null;
        lastCheck = null;
        conflictResolutions.clear();
        skippedChanges.clear();
        mergeablePaths.clear();
        reviewViewController.clear();
    }

    /** @return whether the profile is now on disk exactly as the form shows it */
    private boolean saveProfile() {
        if (currentProfile == null) {
            return false;
        }
        try {
            SyncProfile updated = setupViewController.read(currentProfile.name());
            profileStore.save(updated);
            if (!updated.laptopRoot().equals(currentProfile.laptopRoot())) {
                // The counts were taken from the folder that is no longer this profile's.
                updateStats(updated.name(), stats -> stats.withCounts(-1, -1));
            }
            currentProfile = updated;
            clearCheckResult();
            mergeLeftover = null;
            setupViewController.markSaved();
            refreshProfileList();
            setupViewController.setSyncHint(syncHint(updated));
            watchHdd(updated);
            updateStepper();
            return true;
        } catch (IOException | java.nio.file.InvalidPathException e) {
            setupViewController.showError(Texts.t("profile.saveFailed", e.getMessage()));
            return false;
        }
    }

    /**
     * Check always saves first, so the plan can only ever describe what the form shows.
     * The hash guard in Apply stays regardless: the .prf can also change outside the app.
     */
    private void saveAndCheck() {
        if (!saveProfile()) {
            return;
        }
        if (!unisonRunner.isUnisonAvailable()) {
            setupViewController.showError(Texts.t("check.unisonMissing"));
            return;
        }
        String rootsError = validateRoots(currentProfile);
        if (rootsError != null) {
            setupViewController.showError(rootsError);
            return;
        }

        final SyncProfile profile = currentProfile;
        applyShown = false;
        checkViewController.reset();
        goTo(2);
        setBusy(true);

        Task<CheckResult> task = new Task<>() {
            @Override
            protected CheckResult call() throws Exception {
                byte[] prfHash = hashFile(profileStore.profileFilePath(profile.name()));
                UnisonRunner.Result result = unisonRunner.dryRun(profile.name());
                // Codes normalized by UnisonRunner.dryRunExitCode: 0 = clean, 1 = some
                // items skipped (conflicts are expected and shown as rows; anything else
                // becomes a gate item, see RiskGate.from), 2 = the dry run did not
                // complete cleanly (wrong Unison version, unreadable root, ...).
                requireSuccess(result);
                List<SyncAction> actions = new SyncPlanParser().parse(result.output());
                stageDone(0, Texts.n("check.found", actions.size()));

                RiskScanner.Scan scan = riskScanner.scanWithTotals(
                        profile.laptopRoot(), profile.hddRoot(), profile.ignorePatterns());
                List<RiskFlag> risks = scan.flags();
                updateStats(profile.name(), stats -> stats.withCounts(scan.laptopFiles(), scan.laptopBytes()));
                long riskyFiles = risks.stream().filter(f -> f.scope() == RiskFlag.Scope.FILE).count();
                stageDone(1, Texts.n("check.risky", riskyFiles));

                Map<String, CheckResult.FilePair> facts = new HashMap<>();
                Set<String> mergeable = new HashSet<>();
                int conflicts = 0;
                for (SyncAction action : actions) {
                    Path laptopFile = profile.laptopRoot().resolve(action.relativePath());
                    Path hddFile = profile.hddRoot().resolve(action.relativePath());
                    facts.put(action.relativePath(),
                            new CheckResult.FilePair(FileDiff.facts(laptopFile), FileDiff.facts(hddFile)));
                    if (action.actionType() == SyncAction.ActionType.CONFLICT) {
                        conflicts++;
                        // Probed even when the profile keeps no backups, so the review
                        // screen can say *why* merge is unavailable for this file.
                        if (FileDiff.bothLookLikeText(laptopFile, hddFile)) {
                            mergeable.add(action.relativePath());
                        }
                    }
                }
                for (RiskFlag flag : risks) {
                    // A flagged file is not necessarily in Unison's plan; it still needs
                    // its sizes for the gate.
                    flag.relativePath().ifPresent(path -> facts.computeIfAbsent(path, p ->
                            new CheckResult.FilePair(FileDiff.facts(profile.laptopRoot().resolve(p)),
                                    FileDiff.facts(profile.hddRoot().resolve(p)))));
                }
                // Without the backups Unison has no common ancestor and the merge run
                // fails outright, so nothing is mergeable however textual it is.
                int offered = profile.keepMergeBackups() ? mergeable.size() : 0;
                stageDone(2, Texts.t("check.conflicts", conflicts, offered));

                return new CheckResult(actions, risks, result.output(), prfHash, mergeable,
                        result.exitCode(), facts);
            }

            private void stageDone(int index, String text) {
                Platform.runLater(() -> checkViewController.stageDone(index, text));
            }
        };

        task.setOnSucceeded(e -> {
            // Long enough to read the last line before the screen changes.
            PauseTransition pause = new PauseTransition(Duration.millis(400));
            pause.setOnFinished(done -> {
                setBusy(false);
                showCheckResult(profile, task.getValue());
            });
            pause.play();
        });
        task.setOnFailed(e -> {
            setBusy(false);
            goTo(1);
            Throwable exception = task.getException();
            String message = exception.getMessage() != null
                    ? exception.getMessage() : exception.getClass().getSimpleName();
            setupViewController.showError(Texts.t("check.failed", message));
        });

        new Thread(task).start();
    }

    private void showCheckResult(SyncProfile profile, CheckResult result) {
        lastCheck = result;
        lastCheckedPrfHash = result.prfHash();
        conflictResolutions.clear();
        skippedChanges.clear();
        mergeablePaths.clear();
        mergeablePaths.addAll(result.mergeablePaths());
        gate = RiskGate.from(result.risks(), result.exitCode(), !result.conflicts().isEmpty());
        // Not yet probed counts as found: the merge run itself fails safely without diff3.
        reviewViewController.show(profile, result, gate,
                tools == null || tools.found(ToolCheck.Tool.DIFF3));
        // The check has just counted the laptop copy.
        refreshProfileList();
        setupViewController.setSyncHint(syncHint(profile));
        goTo(3);
    }

    private void startApply() {
        if (currentProfile == null || lastCheck == null) {
            return;
        }
        // The review view enforces this as well; Apply must not depend on a view's button state.
        if (gate == null || !gate.allAcknowledged()) {
            return;
        }
        String rootsError = validateRoots(currentProfile);
        if (rootsError != null) {
            showError(rootsError);
            return;
        }
        final SyncProfile profile = currentProfile;
        final List<SyncAction> actions = lastCheck.actions();
        // Snapshotted on the FX thread: the task below reads it off it. What Apply does
        // is what was on screen when it was pressed.
        final byte[] expectedHash = lastCheckedPrfHash;
        final ApplyPlan plan = ApplyPlan.from(conflictResolutions, actions, mergeablePaths,
                profile.keepMergeBackups(),
                ApplyPlan.skipped(gate.skippedPaths(), skippedChanges, actions));

        final List<String> phases = new ArrayList<>(List.of(Texts.t("apply.phase.changes")));
        if (!plan.laptopWins().isEmpty()) {
            phases.add(Texts.t("apply.phase.laptop"));
        }
        if (!plan.hddWins().isEmpty()) {
            phases.add(Texts.t("apply.phase.hdd"));
        }
        if (!plan.merges().isEmpty()) {
            phases.add(Texts.t("apply.phase.merge"));
        }

        applyShown = true;
        applyViewController.showRunning();
        goTo(4);
        setBusy(true);

        Task<ApplyResult> task = new Task<>() {
            private int phase;

            private void nextPhase() {
                int index = phase++;
                Platform.runLater(() ->
                        applyViewController.setPhase(phases.get(index), index / (double) phases.size()));
            }

            @Override
            protected ApplyResult call() throws Exception {
                byte[] currentHash = hashFile(profileStore.profileFilePath(profile.name()));
                if (expectedHash == null || !java.util.Arrays.equals(currentHash, expectedHash)) {
                    throw new IOException(Texts.t("apply.prfChanged"));
                }
                // Plain -batch first: it propagates everything that isn't a conflict and
                // leaves conflicts untouched. Each resolved side then gets one scoped run,
                // so twenty conflicts still cost at most two extra Unison processes.
                nextPhase();
                UnisonRunner.Result batch = unisonRunner.apply(profile.name(), plan.skipped());
                StringBuilder output = new StringBuilder(requireSuccess(batch));
                if (!plan.laptopWins().isEmpty()) {
                    nextPhase();
                    output.append("\n" + Texts.t("apply.log.laptop") + "\n").append(requireSuccess(
                            unisonRunner.applyPreferring(
                                    profile.name(), profile.laptopRoot().toString(), plan.laptopWins(),
                                    plan.skipped()),
                            output));
                }
                if (!plan.hddWins().isEmpty()) {
                    nextPhase();
                    output.append("\n" + Texts.t("apply.log.hdd") + "\n").append(requireSuccess(
                            unisonRunner.applyPreferring(
                                    profile.name(), profile.hddRoot().toString(), plan.hddWins(),
                                    plan.skipped()),
                            output));
                }
                String warning = null;
                List<String> unmerged = List.of();
                if (!plan.merges().isEmpty()) {
                    nextPhase();
                    // Not requireSuccess: a merge that fails on overlapping edits exits 2 with
                    // both copies untouched, and the earlier phases' log must be kept.
                    UnisonRunner.Result merged = unisonRunner.applyMerging(
                            profile.name(), plan.merges(), plan.skipped());
                    output.append("\n" + Texts.t("apply.log.merge") + "\n").append(merged.output());
                    if (merged.exitCode() >= 2) {
                        unmerged = stillDifferent(profile, plan.merges());
                        warning = unmerged.isEmpty()
                                // Failed, yet no file to point at: only the log can say more.
                                ? Texts.t("apply.mergeError") : Texts.t("apply.mergeOverlap");
                    }
                }
                updateStats(profile.name(), stats -> stats.withLastSync(Instant.now()));
                return new ApplyResult(output.toString(), warning, batch.exitCode(), unmerged);
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            // The plan is spent: clear it, then show what Unison actually did. Another
            // Apply has to go through a fresh Check.
            clearCheckResult();
            ApplyResult result = task.getValue();
            String subline = Texts.t("apply.today", LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")))
                    + " · " + profile.laptopRoot() + " ⇄ " + profile.hddRoot();
            mergeLeftover = result.unmerged().isEmpty() ? null
                    : new MergeLeftover(profile, expectedHash, new HashSet<>(result.unmerged()));
            applyViewController.showDone(subline,
                    ApplySummary.rows(actions, plan, result.batchExitCode(), result.warning() != null,
                            result.unmerged()),
                    result.warning(), result.unmerged(), result.output());
            refreshProfileList();
            updateStepper();
        });
        task.setOnFailed(e -> {
            setBusy(false);
            // An earlier phase may already have transferred files, so the plan no longer
            // describes the disks either way.
            clearCheckResult();
            Throwable exception = task.getException();
            String message = exception.getMessage() != null
                    ? exception.getMessage() : exception.getClass().getSimpleName();
            int newline = message.indexOf('\n');
            applyViewController.showFailed(newline < 0 ? message : message.substring(0, newline), message);
            updateStepper();
        });

        new Thread(task).start();
    }

    /**
     * Settles one file a merge left untouched by keeping one side's copy. {@code -prefer}
     * with a single {@code -path}, exactly as a conflict resolved on the review screen.
     */
    private void keepSide(String path, boolean laptop) {
        MergeLeftover leftover = mergeLeftover;
        // Only a path this Apply's merge run left unsettled may end up after a -path.
        if (leftover == null || !leftover.unmerged().contains(path)) {
            return;
        }
        SyncProfile profile = leftover.profile();
        String rootsError = validateRoots(profile);
        if (rootsError != null) {
            showError(rootsError);
            return;
        }
        setBusy(true);
        applyViewController.setBusy(true);

        Task<String> task = new Task<>() {
            @Override
            protected String call() throws Exception {
                byte[] currentHash = hashFile(profileStore.profileFilePath(profile.name()));
                if (!java.util.Arrays.equals(currentHash, leftover.prfHash())) {
                    throw new IOException(Texts.t("apply.prfChanged"));
                }
                Path root = laptop ? profile.laptopRoot() : profile.hddRoot();
                String output = requireSuccess(unisonRunner.applyPreferring(
                        profile.name(), root.toString(), List.of(path), List.of()));
                if (!stillDifferent(profile, List.of(path)).isEmpty()) {
                    throw new IOException(Texts.t("apply.unchanged") + "\n" + output);
                }
                return output;
            }
        };
        task.setOnSucceeded(e -> {
            setBusy(false);
            applyViewController.setBusy(false);
            leftover.unmerged().remove(path);
            applyViewController.kept(path, ApplySummary.keptRow(path, laptop),
                    "\n" + Texts.t(laptop ? "apply.log.kept.laptop" : "apply.log.kept.hdd", path) + "\n"
                            + task.getValue());
        });
        task.setOnFailed(e -> {
            setBusy(false);
            applyViewController.setBusy(false);
            Throwable exception = task.getException();
            showError(exception.getMessage() != null
                    ? exception.getMessage() : exception.getClass().getSimpleName());
        });

        new Thread(task).start();
    }

    private void showError(String message) {
        new Alert(Alert.AlertType.ERROR, message).showAndWait();
    }

    /** One sidebar entry: status dot, name, and when it was last synced. */
    private class ProfileCell extends ListCell<SyncProfile> {
        @Override
        protected void updateItem(SyncProfile profile, boolean empty) {
            super.updateItem(profile, empty);
            if (empty || profile == null) {
                setGraphic(null);
                setContextMenu(null);
                return;
            }
            boolean configured = isConfigured(profile);
            Region dot = new Region();
            dot.getStyleClass().addAll("dot", configured ? "dot-ok" : "dot-warn");
            Label name = new Label(profile.name());
            name.getStyleClass().add("profile-name");
            HBox top = new HBox(8, dot, name);
            top.setAlignment(Pos.CENTER_LEFT);
            Label sub = new Label(ProfileText.sidebarLine(configured,
                    profileStats.getOrDefault(profile.name(), ProfileStats.NONE), ZonedDateTime.now()));
            sub.getStyleClass().addAll("muted", "small");
            VBox box = new VBox(2, top, sub);
            box.getStyleClass().add("profile-cell");
            if (currentProfile != null && currentProfile.name().equals(profile.name())) {
                box.getStyleClass().add("selected");
            }
            setGraphic(box);

            MenuItem delete = new MenuItem(Texts.t("profile.delete"));
            delete.setOnAction(e -> deleteProfile(profile));
            setContextMenu(new ContextMenu(delete));
        }
    }
}
