package uz.jtscorp.filesync.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

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
}
