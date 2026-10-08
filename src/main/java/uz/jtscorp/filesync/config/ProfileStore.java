package uz.jtscorp.filesync.config;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class ProfileStore {

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
}
