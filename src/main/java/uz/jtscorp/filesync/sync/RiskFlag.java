package uz.jtscorp.filesync.sync;

import java.util.Optional;

public record RiskFlag(Scope scope, Optional<String> relativePath, String reason) {

    /** {@code UNREADABLE}: part of a tree could not be walked, so the scan says nothing about it. */
    public enum Scope { FILE, BATCH, UNREADABLE }

    public static RiskFlag forFile(String relativePath, String reason) {
        return new RiskFlag(Scope.FILE, Optional.of(relativePath), reason);
    }

    public static RiskFlag forBatch(String reason) {
        return new RiskFlag(Scope.BATCH, Optional.empty(), reason);
    }

    public static RiskFlag forUnreadable(String reason) {
        return new RiskFlag(Scope.UNREADABLE, Optional.empty(), reason);
    }
}
