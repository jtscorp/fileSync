package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.config.SyncMode;
import uz.jtscorp.filesync.sync.FileDiff;
import uz.jtscorp.filesync.sync.PlanInterpreter;
import uz.jtscorp.filesync.sync.RiskFlag;
import uz.jtscorp.filesync.sync.SyncAction;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything one Check found, gathered off the UI thread.
 *
 * @param mergeablePaths conflicts whose two copies both look like text
 * @param exitCode       the dry run's normalized code (0 clean, 1 something skipped)
 * @param facts          size and time of both copies, for every plan row and flagged file
 * @param mode           the sync mode the plan was interpreted under
 * @param notes          a sentence per path the interpretation changed or could not decide
 * @param renames        moves recognised for display; Unison still deletes and creates
 * @param folders        what a folder that is about to be deleted holds, by folder path
 * @param manifestFound  whether the backup carried a manifest, so "deleted on the main side" could be told
 * @param heads          the first bytes of both copies, for every plan row and flagged file, read
 *                       here so the detail panel never reads a slow drive on the UI thread
 * @param clusters       the changes that happened together, largest first
 * @param baseline       this profile's past change counts against this check's
 * @param marks          the anomaly model's score for each change it scored; empty while it is off
 */
record CheckResult(List<SyncAction> actions, List<RiskFlag> risks, String rawOutput, byte[] prfHash,
                   Set<String> mergeablePaths, int exitCode, Map<String, FilePair> facts,
                   SyncMode mode, Map<String, String> notes, List<PlanInterpreter.Rename> renames,
                   Map<String, FolderContents> folders, boolean manifestFound, Map<String, Heads> heads,
                   List<ChangeClusters.Cluster> clusters, Baseline baseline, Map<String, Oddity.Mark> marks) {

    /**
     * @param files paths relative to the folder, sorted; ignored ones are listed too because
     *              Unison deletes them along with the folder
     */
    record FolderContents(List<Item> files, long bytes) {}

    record Item(String path, long size, boolean ignored) {}

    record FilePair(FileDiff.FileFacts laptop, FileDiff.FileFacts hdd) {}

    /** Each null when that copy is absent or cannot be read. */
    record Heads(byte[] laptop, byte[] hdd) {}

    private static final FileDiff.FileFacts UNKNOWN = new FileDiff.FileFacts(false, false, 0, null, null);

    List<SyncAction> conflicts() {
        return actions.stream().filter(a -> a.actionType() == SyncAction.ActionType.CONFLICT).toList();
    }

    List<SyncAction> changes() {
        return actions.stream().filter(a -> a.actionType() != SyncAction.ActionType.CONFLICT).toList();
    }

    Optional<SyncAction> actionFor(String path) {
        return actions.stream().filter(a -> a.relativePath().equals(path)).findFirst();
    }

    Heads headsFor(String path) {
        return heads.getOrDefault(path, new Heads(null, null));
    }

    FilePair factsFor(String path) {
        return facts.getOrDefault(path, new FilePair(UNKNOWN, UNKNOWN));
    }
}
