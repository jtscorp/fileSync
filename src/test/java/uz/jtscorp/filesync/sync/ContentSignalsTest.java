package uz.jtscorp.filesync.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uz.jtscorp.filesync.sync.ContentSignals.Finding;
import uz.jtscorp.filesync.sync.ContentSignals.Signal;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ContentSignalsTest {

    @TempDir
    Path dir;

    private static byte[] random(int n) {
        byte[] bytes = new byte[n];
        new Random(42).nextBytes(bytes);
        return bytes;
    }

    private static byte[] csv(int rows) {
        StringBuilder text = new StringBuilder("sana;summa;izoh\n");
        for (int i = 0; i < rows; i++) {
            text.append("01.07;").append(1250000 + i).append(";ijara\n");
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] withHeader(String header, byte[] rest) {
        byte[] head = header.getBytes(StandardCharsets.ISO_8859_1);
        byte[] out = new byte[head.length + rest.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(rest, 0, out, head.length, rest.length);
        return out;
    }

    private static Finding check(String path, byte[] source, byte[] other) {
        return ContentSignals.check(path, true, source, source.length, other, other.length);
    }

    @Test
    void entropyIsZeroForOneValueAndNearEightForRandomBytes() {
        assertEquals(0.0, ContentSignals.entropy(new byte[100]));
        assertEquals(0.0, ContentSignals.entropy(new byte[0]));
        assertEquals(1.0, ContentSignals.entropy(new byte[] {0, 1, 0, 1}), 1e-9);
        assertTrue(ContentSignals.entropy(random(64 * 1024)) > 7.99);
        assertTrue(ContentSignals.entropy(csv(500)) < 6.0);
    }

    @Test
    void textTurnedIntoRandomBytesIsAnEntropySignalOnThePropagatedCopy() {
        Finding finding = check("hisob.csv", random(8192), csv(300));

        assertEquals(Signal.ENTROPY, finding.signal());
        assertTrue(finding.laptopBad(), "the main copy is the one that would be propagated");
        assertTrue(finding.badEntropy() >= ContentSignals.ENCRYPTED_ENTROPY);
        assertTrue(finding.goodEntropy() <= ContentSignals.PLAIN_ENTROPY);
    }

    @Test
    void aCompressedFormatIsNotJudgedByItsEntropy() {
        // The other copy is a zip: not text, so high entropy says nothing.
        byte[] zip = withHeader("PK\u0003\u0004", random(4096));
        assertNull(check("hisobot.xlsx", withHeader("PK\u0003\u0004", random(5000)), zip));
        // Random bytes on both sides: the other copy is not low-entropy text either.
        assertNull(check("a.bin", random(4096), random(4000)));
    }

    @Test
    void aHeaderThatNoLongerMatchesItsExtensionIsAMagicSignal() {
        byte[] good = withHeader("%PDF-1.7\n", csv(50));
        byte[] bad = new byte[good.length];
        bad[0] = (byte) 0xd4;
        bad[1] = 0x1f;
        bad[2] = (byte) 0x8a;
        bad[3] = 0x3c;

        Finding finding = ContentSignals.check("kredit.PDF", false, bad, bad.length, good, good.length);

        assertEquals(Signal.MAGIC, finding.signal());
        assertFalse(finding.laptopBad(), "the backup copy is propagated here");
        assertEquals("pdf", finding.ext());
        assertArrayEquals(new byte[] {(byte) 0xd4, 0x1f, (byte) 0x8a, 0x3c, 0, 0, 0, 0}, finding.badHead());
    }

    @Test
    void aWrongHeaderCountsOnlyWhenTheOtherCopyHasTheRightOne() {
        // Neither copy is a PDF: whatever it is, it did not just break.
        assertNull(check("a.pdf", random(100), random(100)));
        // No signature for this extension at all.
        assertNull(check("a.dat", random(100), withHeader("%PDF", random(10))));
        assertNull(check("a.jpg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 1},
                new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, 2}));
    }

    @Test
    void anAllZeroCopyOfUnchangedSizeIsAZerosSignal() {
        byte[] photo = withHeader("ÿØÿà", random(2000));
        Finding finding = check("diplom.jpg", new byte[photo.length], photo);

        assertEquals(Signal.ZEROS, finding.signal(), "zeros win over the header the zeros also broke");
        assertEquals(photo.length, finding.size());
        assertEquals(1.0, finding.badZeroShare());
        assertTrue(finding.goodZeroShare() < 0.1);
    }

    @Test
    void zerosAreNoSignalWhenTheSizeChangedOrBothCopiesAreZeros() {
        assertNull(ContentSignals.check("a.img", true, new byte[100], 100, random(100), 120));
        // A preallocated file: zeros on both sides.
        assertNull(check("a.img", new byte[100], new byte[100]));
        assertNull(check("a.img", new byte[0], new byte[0]));
    }

    @Test
    void theSignalsWorkOnPrefixesReadFromDisk() throws Exception {
        Path laptop = dir.resolve("laptop.csv");
        Path hdd = dir.resolve("hdd.csv");
        Files.write(laptop, random(200_000));
        Files.write(hdd, csv(3000));

        byte[] laptopPrefix = FileDiff.head(laptop, ContentSignals.PREFIX_BYTES);
        byte[] hddPrefix = FileDiff.head(hdd, ContentSignals.PREFIX_BYTES);
        assertEquals(ContentSignals.PREFIX_BYTES, laptopPrefix.length, "a large file is read only so far");

        Finding finding = ContentSignals.check("hisob.csv", true, laptopPrefix, Files.size(laptop),
                hddPrefix, Files.size(hdd));

        assertEquals(Signal.ENTROPY, finding.signal());
    }
}
