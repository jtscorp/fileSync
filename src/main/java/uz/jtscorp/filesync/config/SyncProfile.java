package uz.jtscorp.filesync.config;

import uz.jtscorp.filesync.Texts;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public record SyncProfile(String name, Path laptopRoot, Path hddRoot, List<String> ignorePatterns,
                          boolean keepMergeBackups) {

    /** A profile that keeps no backups -- the default for everything but an explicit opt-in. */
    public SyncProfile(String name, Path laptopRoot, Path hddRoot, List<String> ignorePatterns) {
        this(name, laptopRoot, hddRoot, ignorePatterns, false);
    }

    /** Keeps letters and digits of any script. No leading dash: Unison would read the name as an option. */
    public static String sanitizeFileName(String name) {
        String safe = name.replaceAll("[^\\p{L}\\p{M}\\p{N}_-]", "_");
        return safe.startsWith("-") ? "_" + safe : safe;
    }

    /**
     * Turns bare input into a Unison ignore rule. Text with a separator becomes a
     * {@code Path} rule, since {@code Name} only matches the last path component.
     */
    public static String normalizeIgnorePattern(String pattern) {
        String stripped = pattern.strip();
        if (stripped.isBlank() || stripped.matches("(Name|Path|BelowPath|Regex)\\s+.*")) {
            return stripped;
        }
        return (stripped.contains("/") ? "Path " : "Name ") + stripped;
    }

    /** A {@code .prf} without two roots: one of Unison's own, not a profile of this app. */
    public static class NotASyncProfile extends IOException {
        NotASyncProfile(String message) {
            super(message);
        }
    }

    public static SyncProfile readFrom(String name, Path prfFile) throws IOException {
        List<Path> roots = new ArrayList<>();
        List<String> ignores = new ArrayList<>();
        boolean keepMergeBackups = false;
        for (String line : Files.readAllLines(prfFile, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            // No trailing space in the prefixes: a new profile's empty root is
            // written as "root = " and strip() has already eaten that space.
            if (trimmed.startsWith("root =")) {
                roots.add(Path.of(trimmed.substring("root =".length()).strip()));
            } else if (trimmed.startsWith("ignore =")) {
                ignores.add(trimmed.substring("ignore =".length()).strip());
            } else if (trimmed.startsWith("backupcurrent =")) {
                // Any non-blank rule counts: the UI writes "Name *", but a profile
                // hand-edited to back up a narrower set still supports merging.
                keepMergeBackups = !trimmed.substring("backupcurrent =".length()).isBlank();
            }
        }
        if (roots.size() != 2) {
            throw new NotASyncProfile(Texts.t("profile.badRoots", prfFile));
        }
        return new SyncProfile(name, roots.get(0), roots.get(1), ignores, keepMergeBackups);
    }

    public void writeTo(Path prfFile) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("root = ").append(laptopRoot).append('\n');
        sb.append("root = ").append(hddRoot).append('\n');
        for (String pattern : ignorePatterns) {
            sb.append("ignore = ").append(pattern).append('\n');
        }
        if (keepMergeBackups) {
            // Merging needs a copy of the last synced version, and Unison only keeps one
            // when the profile says so before the conflict appears.
            sb.append("backupcurrent = Name *\n");
        }
        sb.append("batch = false\n");
        if (prfFile.getParent() != null) {
            Files.createDirectories(prfFile.getParent());
        }
        Path tmpFile = prfFile.resolveSibling(prfFile.getFileName().toString() + ".tmp");
        Files.writeString(tmpFile, sb.toString(), StandardCharsets.UTF_8);
        try {
            Files.move(tmpFile, prfFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmpFile, prfFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
