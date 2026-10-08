package uz.jtscorp.filesync.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** App-wide settings (so far the language), one {@code key = value} line in {@code filesync.conf}. */
public final class AppSettings {

    private static final String LANGUAGE = "language =";

    private final Path file;

    public AppSettings(Path unisonDir) {
        this.file = unisonDir.resolve("filesync.conf");
    }

    /** Null when nothing was saved or the file cannot be read; the caller has a default. */
    public String language() {
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.strip();
                if (trimmed.startsWith(LANGUAGE)) {
                    return trimmed.substring(LANGUAGE.length()).strip();
                }
            }
        } catch (IOException e) {
            // A preference that cannot be read is a preference not yet made.
        }
        return null;
    }

    public void saveLanguage(String code) throws IOException {
        Files.createDirectories(file.getParent());
        Path tmpFile = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.writeString(tmpFile, LANGUAGE + " " + code + "\n");
        Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
