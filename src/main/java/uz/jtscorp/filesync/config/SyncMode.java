package uz.jtscorp.filesync.config;

import uz.jtscorp.filesync.Texts;

/** How a profile treats the two sides; stored in the .prf as a comment line Unison ignores. */
public enum SyncMode {
    TWO_WAY("two-way"),
    TWO_WAY_PRUNE("two-way-prune"),
    MIRROR_TO_BACKUP("mirror-to-backup"),
    RESTORE_FROM_BACKUP("restore-from-backup");

    private final String prfValue;

    SyncMode(String prfValue) {
        this.prfValue = prfValue;
    }

    public String prfValue() {
        return prfValue;
    }

    /** Unknown or missing reads as TWO_WAY: the one mode that deletes and overwrites nothing on its own. */
    public static SyncMode fromPrf(String value) {
        if (value != null) {
            String v = value.strip();
            for (SyncMode mode : values()) {
                if (mode.prfValue.equals(v)) {
                    return mode;
                }
            }
        }
        return TWO_WAY;
    }

    /** Looked up when shown, not when the class loads, so a language change applies. */
    public String label() {
        return Texts.t("mode." + prfValue);
    }

    public String hint() {
        return Texts.t("mode." + prfValue + ".hint");
    }

    /** One side's copy is made the truth by force, whatever the other side changed. */
    public boolean forced() {
        return this == MIRROR_TO_BACKUP || this == RESTORE_FROM_BACKUP;
    }

    /**
     * Backup-only files (whatever only the backup has, whether Main deleted it or it was only
     * ever written to the backup) are offered for deletion from the backup instead of being
     * copied to Main.
     */
    public boolean prunesBackup() {
        return this == TWO_WAY_PRUNE;
    }
}
