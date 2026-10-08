package uz.jtscorp.filesync.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.format.DateTimeParseException;

/**
 * Display-only numbers about a profile, kept in {@code <profile>.filesync} beside the
 * {@code .prf}. A missing or unreadable file reads as {@link #NONE}.
 *
 * @param lastSync   null until the first successful Apply
 * @param fileCount  -1 until the first check
 * @param totalBytes -1 until the first check
 */
public record ProfileStats(Instant lastSync, long fileCount, long totalBytes) {

    public static final ProfileStats NONE = new ProfileStats(null, -1, -1);

    public boolean hasCounts() {
        return fileCount >= 0 && totalBytes >= 0;
    }

    public ProfileStats withLastSync(Instant when) {
        return new ProfileStats(when, fileCount, totalBytes);
    }

    public ProfileStats withCounts(long files, long bytes) {
        return new ProfileStats(lastSync, files, bytes);
    }

    static ProfileStats readFrom(Path file) {
        Instant lastSync = null;
        long files = -1;
        long bytes = -1;
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int equals = line.indexOf('=');
                if (equals < 0) {
                    continue;
                }
                String value = line.substring(equals + 1).strip();
                switch (line.substring(0, equals).strip()) {
                    case "lastSync" -> lastSync = Instant.parse(value);
                    case "files" -> files = Long.parseLong(value);
                    case "bytes" -> bytes = Long.parseLong(value);
                    default -> { }
                }
            }
        } catch (IOException | DateTimeParseException | NumberFormatException e) {
            return NONE;
        }
        return new ProfileStats(lastSync, files, bytes);
    }

    void writeTo(Path file) throws IOException {
        StringBuilder sb = new StringBuilder();
        if (lastSync != null) {
            sb.append("lastSync = ").append(lastSync).append('\n');
        }
        if (hasCounts()) {
            sb.append("files = ").append(fileCount).append('\n');
            sb.append("bytes = ").append(totalBytes).append('\n');
        }
        Path tmpFile = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.writeString(tmpFile, sb.toString(), StandardCharsets.UTF_8);
        Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
}
