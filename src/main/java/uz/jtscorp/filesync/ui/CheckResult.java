package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.sync.FileDiff;
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
 */
record CheckResult(List<SyncAction> actions, List<RiskFlag> risks, String rawOutput, byte[] prfHash,
                   Set<String> mergeablePaths, int exitCode, Map<String, FilePair> facts) {

    record FilePair(FileDiff.FileFacts laptop, FileDiff.FileFacts hdd) {}

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

    FilePair factsFor(String path) {
        return facts.getOrDefault(path, new FilePair(UNKNOWN, UNKNOWN));
    }
}
