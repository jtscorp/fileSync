package uz.jtscorp.filesync.sync;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * What one tree looks like, gathered during the risk scanner's walk so the anomaly model
 * needs no second one: a bounded random sample of its files, and per folder how many files
 * it holds, their total size and how many of each extension.
 */
public final class TreeProfile {

    /** How many files of each tree the model learns from: memory stays bounded however large the tree. */
    public static final int SAMPLE_SIZE = 2048;
    private static final long SEED = 42;

    public record FileStat(String path, long size, Instant modified) {}

    private static final class Folder {
        int files;
        long bytes;
        final Map<String, Integer> extensions = new HashMap<>();
    }

    private final int sampleSize;
    private final Random random;
    private final List<FileStat> sample = new ArrayList<>();
    private final Map<String, Folder> folders = new HashMap<>();
    private long seen;

    public TreeProfile() {
        this(SAMPLE_SIZE, SEED);
    }

    TreeProfile(int sampleSize, long seed) {
        this.sampleSize = sampleSize;
        this.random = new Random(seed);
    }

    public void add(String path, long size, Instant modified) {
        Folder folder = folders.computeIfAbsent(folderOf(path), f -> new Folder());
        folder.files++;
        folder.bytes += size;
        folder.extensions.merge(extensionOf(path), 1, Integer::sum);
        // Reservoir sampling: every file of the walk has the same chance to be kept.
        seen++;
        FileStat stat = new FileStat(path, size, modified);
        if (sample.size() < sampleSize) {
            sample.add(stat);
        } else {
            long slot = (long) (random.nextDouble() * seen);
            if (slot < sampleSize) {
                sample.set((int) slot, stat);
            }
        }
    }

    public List<FileStat> sample() {
        return List.copyOf(sample);
    }

    public int files(String folder) {
        Folder stats = folders.get(folder);
        return stats == null ? 0 : stats.files;
    }

    /** @param extension with its dot, as {@link #extensionOf} gives it */
    public int extensionCount(String folder, String extension) {
        Folder stats = folders.get(folder);
        return stats == null ? 0 : stats.extensions.getOrDefault(extension.toLowerCase(Locale.ROOT), 0);
    }

    /** NaN for a folder with no files. */
    public double meanSize(String folder) {
        Folder stats = folders.get(folder);
        return stats == null || stats.files == 0 ? Double.NaN : (double) stats.bytes / stats.files;
    }

    public static String folderOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /** The last extension with its dot, lower case; empty for a name without one. */
    public static String extensionOf(String path) {
        String name = path.substring(path.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }
}
