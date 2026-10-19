package uz.jtscorp.filesync.sync;

/**
 * One planned change. {@code direction} points at the side that gets written and
 * {@code actionType} is what happens there ({@code DELETED} = removed from that side,
 * {@code NEW} = created there, {@code CHANGED} = overwritten there), whatever the origin.
 */
public record SyncAction(String relativePath, Direction direction, ActionType actionType, Origin origin) {

    public enum Direction { LAPTOP_TO_HDD, HDD_TO_LAPTOP, CONFLICT }

    public enum ActionType { NEW, CHANGED, DELETED, CONFLICT }

    /** Who decided the change: Unison's own reconciliation, the sync mode's -force, or the manifest. */
    public enum Origin { UNISON, MODE, MANIFEST }

    public SyncAction(String relativePath, Direction direction, ActionType actionType) {
        this(relativePath, direction, actionType, Origin.UNISON);
    }
}
