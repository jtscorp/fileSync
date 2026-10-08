package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
/** What the user chose for one conflict. {@code SKIP}, the default, leaves both sides alone. */
enum Resolution {
    SKIP("resolution.skip"), LAPTOP("side.laptop"), HDD("side.hdd"), MERGE("resolution.merge");

    private final String key;

    Resolution(String key) {
        this.key = key;
    }

    String label() {
        return Texts.t(key);
    }
}
