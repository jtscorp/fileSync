package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SizeFormatTest {

    @Test
    void bytesStayWholeNumbers() {
        assertEquals("0 B", SizeFormat.format(0));
        assertEquals("812 B", SizeFormat.format(812));
    }

    @Test
    void belowTenGetsOneDecimalWithAComma() {
        assertEquals("1,9 KB", SizeFormat.format(1946));
        assertEquals("2,4 MB", SizeFormat.format(2_516_582));
        assertEquals("4,0 MB", SizeFormat.format(4L * 1024 * 1024));
    }

    @Test
    void tenAndAboveIsAWholeNumber() {
        assertEquals("48 KB", SizeFormat.format(48 * 1024));
        assertEquals("18 GB", SizeFormat.format(19_542_101_197L));
    }

    @Test
    void roundsAtUnitAndDecimalBoundaries() {
        assertEquals("1023 B", SizeFormat.format(1023));
        assertEquals("1,0 KB", SizeFormat.format(1024));
        assertEquals("9,9 KB", SizeFormat.format(10_188));   // 9.949 KB
        assertEquals("10 KB", SizeFormat.format(10_199));    // 9.96 KB must not print "10,0 KB"
        assertEquals("1,0 MB", SizeFormat.format(1_048_575)); // must not print "1024 KB"
    }
}
