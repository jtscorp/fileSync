package uz.jtscorp.filesync;

import javafx.application.Application;
import javafx.stage.Stage;
import uz.jtscorp.filesync.config.AppSettings;
import uz.jtscorp.filesync.config.ProfileStore;
import uz.jtscorp.filesync.ui.MainView;

import java.nio.file.Path;

public class Main extends Application {

    @Override
    public void start(Stage stage) {
        Path unisonDir = Path.of(System.getProperty("user.home"), ".unison");
        AppSettings settings = new AppSettings(unisonDir);
        Texts.use(Texts.Language.fromCode(settings.language()));

        stage.setTitle("FileSync — Unison GUI");
        show(stage, new ProfileStore(unisonDir), settings, null);
        stage.setMinWidth(1000);
        stage.setMinHeight(650);
        stage.show();
    }

    /**
     * Builds the window's content in the current language; a language change rebuilds it.
     *
     * @param selectProfile the profile to open on, or null for none
     */
    private void show(Stage stage, ProfileStore profileStore, AppSettings settings, String selectProfile) {
        double width = stage.getWidth();
        double height = stage.getHeight();
        MainView mainView = new MainView(profileStore, settings,
                profile -> show(stage, profileStore, settings, profile));
        stage.setScene(mainView.createScene(selectProfile));
        if (stage.isShowing()) {
            // A new scene would otherwise snap the window back to its initial size.
            stage.setWidth(width);
            stage.setHeight(height);
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
