package uz.jtscorp.filesync.sync;

import java.util.Optional;

/**
 * @param content what the bytes of a file say, for a content signal; null for every other flag
 */
public record RiskFlag(Scope scope, Optional<String> relativePath, String reason, ContentSignals.Finding content) {

    /** {@code UNREADABLE}: part of a tree could not be walked, so the scan says nothing about it. */
    public enum Scope { FILE, BATCH, UNREADABLE }

    public static RiskFlag forFile(String relativePath, String reason) {
        return new RiskFlag(Scope.FILE, Optional.of(relativePath), reason, null);
    }

    /** A file whose bytes contradict its type or the other copy: decided like any other file. */
    public static RiskFlag forContent(String relativePath, String reason, ContentSignals.Finding content) {
        return new RiskFlag(Scope.FILE, Optional.of(relativePath), reason, content);
    }

    public static RiskFlag forBatch(String reason) {
        return new RiskFlag(Scope.BATCH, Optional.empty(), reason, null);
    }

    public static RiskFlag forUnreadable(String reason) {
        return new RiskFlag(Scope.UNREADABLE, Optional.empty(), reason, null);
    }
}
