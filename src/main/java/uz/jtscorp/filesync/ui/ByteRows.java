package uz.jtscorp.filesync.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * A file's first bytes as the detail panel shows them when there is no line diff: rows of
 * eight, each byte in hex and as a character, marked where the other copy differs. Free of
 * JavaFX so the layout has a plain test.
 */
final class ByteRows {

    static final int PER_ROW = 8;

    /** @param character the byte as printable ASCII, or {@code ·} */
    record Cell(String hex, String character, boolean differs) {}

    /** @param offset where the row starts, in hex */
    record Row(String offset, List<Cell> cells) {}

    private ByteRows() {}

    /** Worth a block when at least one copy has bytes; an absent or empty one says so instead. */
    static boolean shown(byte[] laptop, byte[] hdd) {
        return hasBytes(laptop) || hasBytes(hdd);
    }

    /** Bytes are marked as differing only when both copies have some to compare. */
    static boolean compared(byte[] laptop, byte[] hdd) {
        return hasBytes(laptop) && hasBytes(hdd);
    }

    /** @param other the other copy's bytes, or null when there is no other copy */
    static List<Row> of(byte[] bytes, byte[] other) {
        boolean compare = compared(bytes, other);
        List<Row> rows = new ArrayList<>();
        for (int start = 0; start < bytes.length; start += PER_ROW) {
            List<Cell> cells = new ArrayList<>();
            for (int i = start; i < Math.min(start + PER_ROW, bytes.length); i++) {
                int value = bytes[i] & 0xff;
                boolean differs = compare && (i >= other.length || other[i] != bytes[i]);
                cells.add(new Cell(String.format("%02x", value),
                        value >= 0x20 && value < 0x7f ? String.valueOf((char) value) : "·", differs));
            }
            rows.add(new Row(String.format("%02x", start), cells));
        }
        return rows;
    }

    private static boolean hasBytes(byte[] bytes) {
        return bytes != null && bytes.length > 0;
    }
}
