package uz.jtscorp.filesync.sync;

public record SyncAction(String relativePath, Direction direction, ActionType actionType) {

    public enum Direction { LAPTOP_TO_HDD, HDD_TO_LAPTOP, CONFLICT }

    public enum ActionType { NEW, CHANGED, DELETED, CONFLICT }
}
