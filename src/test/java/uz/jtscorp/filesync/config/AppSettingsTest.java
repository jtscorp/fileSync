package uz.jtscorp.filesync.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppSettingsTest {

    @Test
    void theLanguageSurvivesARestartAndIsNotAProfile(@TempDir Path dir) throws IOException {
        Path unisonDir = dir.resolve(".unison");
        assertNull(new AppSettings(unisonDir).language());

        new AppSettings(unisonDir).saveLanguage("ru");

        assertEquals("ru", new AppSettings(unisonDir).language());
        assertEquals(0, new ProfileStore(unisonDir).listProfiles().size());
    }

    @Test
    void anUnreadableFileIsNoPreference(@TempDir Path dir) throws IOException {
        Files.createDirectory(dir.resolve("filesync.conf"));

        assertNull(new AppSettings(dir).language());
    }

    @Test
    void thePaneWidthsAndTheLanguageKeepEachOther(@TempDir Path dir) throws IOException {
        AppSettings settings = new AppSettings(dir);
        assertTrue(Double.isNaN(settings.sidebarWidth()));
        assertTrue(Double.isNaN(settings.detailWidth()));

        settings.saveLanguage("en");
        settings.saveSidebarWidth(250);
        settings.saveDetailWidth(512.5);
        settings.saveLanguage("ru");

        AppSettings reread = new AppSettings(dir);
        assertEquals("ru", reread.language());
        assertEquals(250, reread.sidebarWidth());
        assertEquals(512.5, reread.detailWidth());
    }

    @Test
    void aWidthThatIsNotANumberIsNoPreference(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("filesync.conf"), "language = uz\nsidebarWidth = keng\n");

        assertTrue(Double.isNaN(new AppSettings(dir).sidebarWidth()));
        assertEquals("uz", new AppSettings(dir).language());
    }
}
