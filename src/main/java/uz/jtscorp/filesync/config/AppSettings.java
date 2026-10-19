package uz.jtscorp.filesync.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * App-wide settings -- the language and the widths the sidebar and the detail panel were
 * dragged to -- as {@code key = value} lines in {@code filesync.conf}. Saving one key keeps
 * the others.
 */
public final class AppSettings {

    private static final String LANGUAGE = "language";
    private static final String SIDEBAR_WIDTH = "sidebarWidth";
    private static final String DETAIL_WIDTH = "detailWidth";

    private final Path file;

    public AppSettings(Path unisonDir) {
        this.file = unisonDir.resolve("filesync.conf");
    }

    /** Null when nothing was saved or the file cannot be read; the caller has a default. */
    public String language() {
        return read().get(LANGUAGE);
    }

    public void saveLanguage(String code) throws IOException {
        save(LANGUAGE, code);
    }

    /** NaN when nothing usable was saved; the caller has a default. */
    public double sidebarWidth() {
        return number(SIDEBAR_WIDTH);
    }

    public void saveSidebarWidth(double width) throws IOException {
        save(SIDEBAR_WIDTH, String.valueOf(width));
    }

    /** NaN when nothing usable was saved; the caller has a default. */
    public double detailWidth() {
        return number(DETAIL_WIDTH);
    }

    public void saveDetailWidth(double width) throws IOException {
        save(DETAIL_WIDTH, String.valueOf(width));
    }

    private double number(String key) {
        String value = read().get(key);
        try {
            return value == null ? Double.NaN : Double.parseDouble(value);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private Map<String, String> read() {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            for (String line : Files.readAllLines(file)) {
                int equals = line.indexOf('=');
                if (equals > 0) {
                    values.put(line.substring(0, equals).strip(), line.substring(equals + 1).strip());
                }
            }
        } catch (IOException e) {
            // A preference that cannot be read is a preference not yet made.
        }
        return values;
    }

    private void save(String key, String value) throws IOException {
        Map<String, String> values = read();
        values.put(key, value);
        StringBuilder sb = new StringBuilder();
        values.forEach((k, v) -> sb.append(k).append(" = ").append(v).append('\n'));
        Files.createDirectories(file.getParent());
        Path tmpFile = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.writeString(tmpFile, sb.toString());
        Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
