package uz.jtscorp.filesync.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class ProfileStore {

    /** How many past syncs' change counts the history keeps; the newest replace the oldest. */
    public static final int HISTORY_KEPT = 50;

    private final Path unisonDir;

    public ProfileStore(Path unisonDir) {
        this.unisonDir = unisonDir;
    }

    public List<SyncProfile> listProfiles() throws IOException {
        List<SyncProfile> profiles = new ArrayList<>();
        if (!Files.isDirectory(unisonDir)) {
            return profiles;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(unisonDir, "*.prf")) {
            for (Path prfFile : stream) {
                String fileName = prfFile.getFileName().toString();
                String name = fileName.substring(0, fileName.length() - ".prf".length());
                try {
                    profiles.add(SyncProfile.readFrom(name, prfFile));
                } catch (SyncProfile.NotASyncProfile e) {
                    // ~/.unison also holds Unison's own profiles (default.prf); skip those.
                }
            }
        }
        return profiles;
    }

    public void save(SyncProfile profile) throws IOException {
        profile.writeTo(profileFile(profile.name()));
    }

    public void delete(String profileName) throws IOException {
        Files.deleteIfExists(profileFile(profileName));
        Files.deleteIfExists(statsFile(profileName));
        Files.deleteIfExists(historyFile(profileName));
    }

    /**
     * How many changes each past sync carried, oldest first. Empty when there is no history
     * or it cannot be read: the fixed mass-change rule is then used, the safe direction.
     */
    public List<Integer> history(String profileName) {
        try {
            List<Integer> counts = new ArrayList<>();
            for (String line : Files.readAllLines(historyFile(profileName), StandardCharsets.UTF_8)) {
                if (!line.isBlank()) {
                    counts.add(Integer.parseInt(line.strip()));
                }
            }
            return counts;
        } catch (IOException | NumberFormatException e) {
            return List.of();
        }
    }

    /** Adds one sync's change count: called once per Apply, so a check repeated does not count twice. */
    public void recordChanges(String profileName, int changes) throws IOException {
        List<Integer> counts = new ArrayList<>(history(profileName));
        counts.add(changes);
        List<Integer> kept = counts.subList(Math.max(0, counts.size() - HISTORY_KEPT), counts.size());
        StringBuilder text = new StringBuilder();
        kept.forEach(count -> text.append(count).append('\n'));
        Path file = historyFile(profileName);
        Files.createDirectories(file.getParent());
        Path tmpFile = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.writeString(tmpFile, text.toString(), StandardCharsets.UTF_8);
        Files.move(tmpFile, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Never fails: the stats are for display, see {@link ProfileStats}. */
    public ProfileStats stats(String profileName) {
        return ProfileStats.readFrom(statsFile(profileName));
    }

    public void saveStats(String profileName, ProfileStats stats) throws IOException {
        stats.writeTo(statsFile(profileName));
    }

    public Path profileFilePath(String profileName) {
        return profileFile(profileName);
    }

    private Path profileFile(String profileName) {
        return unisonDir.resolve(SyncProfile.sanitizeFileName(profileName) + ".prf");
    }

    /** Not {@code .prf}, so neither Unison nor {@link #listProfiles} takes it for a profile. */
    private Path statsFile(String profileName) {
        return unisonDir.resolve(SyncProfile.sanitizeFileName(profileName) + ".filesync");
    }

    /** Apart from the stats: those are for display only, this one feeds the mass-change rule. */
    private Path historyFile(String profileName) {
        return unisonDir.resolve(SyncProfile.sanitizeFileName(profileName) + ".history");
    }
}
