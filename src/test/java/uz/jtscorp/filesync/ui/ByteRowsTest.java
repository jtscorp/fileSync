package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.ui.ByteRows.Cell;
import uz.jtscorp.filesync.ui.ByteRows.Row;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ByteRowsTest {

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }

    private static String hex(Row row) {
        return String.join(" ", row.cells().stream().map(Cell::hex).toList());
    }

    private static String characters(Row row) {
        return String.join("", row.cells().stream().map(Cell::character).toList());
    }

    @Test
    void eightBytesARowWithTheOffsetInHexAndOnlyPrintableCharacters() {
        byte[] pdf = new byte[20];
        System.arraycopy("%PDF-1.7".getBytes(), 0, pdf, 0, 8);
        pdf[8] = (byte) 0xd4;
        pdf[9] = 0x0a;

        List<Row> rows = ByteRows.of(pdf, null);

        assertEquals(List.of("00", "08", "10"), rows.stream().map(Row::offset).toList());
        assertEquals("25 50 44 46 2d 31 2e 37", hex(rows.get(0)));
        assertEquals("%PDF-1.7", characters(rows.get(0)));
        assertEquals("d4 0a 00 00 00 00 00 00", hex(rows.get(1)));
        assertEquals("········", characters(rows.get(1)));
        // The last row holds what is left, not a padded eight.
        assertEquals(4, rows.get(2).cells().size());
    }

    @Test
    void aByteDiffersWhenTheOtherCopyHasAnotherOrNoneAtThatOffset() {
        List<Row> rows = ByteRows.of(bytes(0x25, 0x50, 0x44), bytes(0x25, 0x51));

        assertEquals(List.of(false, true, true),
                rows.get(0).cells().stream().map(Cell::differs).toList());
    }

    @Test
    void nothingDiffersWithoutAnotherCopyToCompareWith() {
        assertTrue(ByteRows.of(bytes(1, 2), null).get(0).cells().stream().noneMatch(Cell::differs));
        assertTrue(ByteRows.of(bytes(1, 2), new byte[0]).get(0).cells().stream().noneMatch(Cell::differs));
    }

    @Test
    void theViewIsShownWhenOneCopyHasBytesAndComparedOnlyWhenBothDo() {
        assertTrue(ByteRows.shown(bytes(1), null));
        assertTrue(ByteRows.shown(new byte[0], bytes(1)));
        assertFalse(ByteRows.shown(null, new byte[0]));
        assertFalse(ByteRows.shown(null, null));

        assertTrue(ByteRows.compared(bytes(1), bytes(2)));
        assertFalse(ByteRows.compared(bytes(1), new byte[0]));
        assertFalse(ByteRows.compared(null, bytes(2)));
    }
}
