package uz.jtscorp.filesync.sync;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileDiffTest {

    @TempDir
    Path tempDir;

    private static String describe(FileDiff.DiffRow row) {
        return row.kind() + " " + row.leftNumber() + ":" + row.leftText()
                + " | " + row.rightNumber() + ":" + row.rightText();
    }

    @Test
    void alignsChangedLinesSideBySideAndKeepsTheRestAsContext() {
        List<FileDiff.DiffRow> rows = FileDiff.parse("""
                --- a/smeta.txt
                +++ b/smeta.txt
                @@ -1,3 +1,3 @@
                 mahsulot;narx
                -kitob;17000
                +kitob;16000
                 ruchka;3000
                """);

        assertEquals(List.of(
                "SAME 1:mahsulot;narx | 1:mahsulot;narx",
                "CHANGED 2:kitob;17000 | 2:kitob;16000",
                "SAME 3:ruchka;3000 | 3:ruchka;3000"),
                rows.stream().map(FileDiffTest::describe).toList());
    }

    @Test
    void anUnevenBlockLeavesTheShorterSideEmpty() {
        // Two lines gone, one added in their place: the pair aligns, the leftover does not.
        List<FileDiff.DiffRow> rows = FileDiff.parse("""
                @@ -1,3 +1,2 @@
                -bir
                -ikki
                +BIR
                 uch
                """);

        assertEquals(List.of(
                "CHANGED 1:bir | 1:BIR",
                "LEFT_ONLY 2:ikki | null:null",
                "SAME 3:uch | 2:uch"),
                rows.stream().map(FileDiffTest::describe).toList());
    }

    @Test
    void unifiedSplitsEachChangedRowIntoItsOldAndNewLine() {
        List<FileDiff.DiffRow> unified = FileDiff.toUnified(FileDiff.parse("""
                @@ -1,2 +1,2 @@
                 bir
                -ikki
                +IKKI
                """));

        assertEquals(List.of(
                "SAME 1:bir | 1:bir",
                "LEFT_ONLY 2:ikki | null:null",
                "RIGHT_ONLY null:null | 2:IKKI"),
                unified.stream().map(FileDiffTest::describe).toList());
    }

    @Test
    void hunkHeaderSetsTheStartingLineNumbers() {
        List<FileDiff.DiffRow> rows = FileDiff.parse("""
                @@ -40,2 +55,2 @@
                 kontekst
                -eski
                +yangi
                """);

        assertEquals("SAME 40:kontekst | 55:kontekst", describe(rows.get(0)));
        assertEquals("CHANGED 41:eski | 56:yangi", describe(rows.get(1)));
    }

    @Test
    void keepsBlankLinesButNotTheEmptyTailOfTheOutput() {
        // GNU diff writes a blank context line as " " -- prefix, empty content. A truly
        // empty string is only ever the tail left by the output's final newline, and
        // counting it as a line would shift every number after it.
        List<FileDiff.DiffRow> rows = FileDiff.parse("@@ -1,3 +1,3 @@\n bir\n \n-uch\n+UCH\n");

        assertEquals(List.of(
                "SAME 1:bir | 1:bir",
                "SAME 2: | 2:",
                "CHANGED 3:uch | 3:UCH"),
                rows.stream().map(FileDiffTest::describe).toList());
    }

    @Test
    void comparesTwoRealFilesEndToEnd() throws Exception {
        Path left = Files.writeString(tempDir.resolve("l.txt"), "bir\nikki\nuch\n");
        Path right = Files.writeString(tempDir.resolve("r.txt"), "bir\nIKKI\nuch\n");

        FileDiff.Comparison comparison = FileDiff.compare(left, right);

        assertNull(comparison.note(), comparison.note());
        assertEquals(13, comparison.left().size());
        assertEquals(List.of(FileDiff.DiffRow.Kind.SAME, FileDiff.DiffRow.Kind.CHANGED,
                        FileDiff.DiffRow.Kind.SAME),
                comparison.rows().stream().map(FileDiff.DiffRow::kind).toList());
    }

    @Test
    void identicalAndOneSidedAndBinaryFilesGetANoteInsteadOfRows() throws Exception {
        Path text = Files.writeString(tempDir.resolve("a.txt"), "bir xil\n");
        Path same = Files.writeString(tempDir.resolve("b.txt"), "bir xil\n");
        Path binary = Files.write(tempDir.resolve("c.docx"), new byte[] {'P', 'K', 3, 4, 0, 'x'});
        Path missing = tempDir.resolve("yo'q.txt");

        assertTrue(FileDiff.compare(text, same).note().contains("bir xil"));
        assertTrue(FileDiff.compare(text, binary).note().contains("Binar"));
        assertTrue(FileDiff.compare(text, missing).note().contains("faqat bir tomonda"));
        assertFalse(FileDiff.compare(text, missing).right().exists());
    }

    @Test
    void aNulByteMeansNotText() throws Exception {
        // The whole point of the gate: an office document is not mergeable by diff3.
        Path text = Files.writeString(tempDir.resolve("a.txt"), "sof matn\n");
        Path binary = Files.write(tempDir.resolve("b.docx"), new byte[] {'P', 'K', 3, 4, 0, 'x'});

        assertTrue(FileDiff.bothLookLikeText(text, text));
        assertFalse(FileDiff.bothLookLikeText(text, binary));
        assertFalse(FileDiff.bothLookLikeText(binary, text));
    }

    @Test
    void anUnreadableSideIsNotTreatedAsText() {
        assertFalse(FileDiff.bothLookLikeText(tempDir.resolve("yo'q.txt"), tempDir.resolve("ham-yo'q.txt")));
    }

    @Test
    void differenceBlocksCountRunsOfChangedLinesNotIndividualLines() {
        List<FileDiff.DiffRow> rows = FileDiff.parse("""
                @@ -1,5 +1,5 @@
                 bir
                -ikki
                -uch
                +IKKI
                +UCH
                 tort
                -besh
                +BESH
                """);

        // Rows 1-2 are one difference, row 4 is another: two, not three.
        assertEquals(List.of(1, 4), FileDiff.differenceBlocks(rows));
    }

    @Test
    void changedSpanTrimsWhatTheTwoLinesShare() {
        FileDiff.Span span = FileDiff.changedSpan("kitob;17000", "kitob;16000");

        assertEquals("7", "kitob;17000".substring(span.start(), span.leftEnd()));
        assertEquals("6", "kitob;16000".substring(span.start(), span.rightEnd()));
    }

    @Test
    void changedSpanCoversTheWholeLineWhenNothingIsShared() {
        FileDiff.Span span = FileDiff.changedSpan("birinchi", "BIRINCHI");

        assertEquals(0, span.start());
        assertEquals(8, span.leftEnd());
        assertEquals(8, span.rightEnd());
    }

    @Test
    void changedSpanHandlesAPureInsertion() {
        // Nothing removed: the left side's span is empty, the right side's is the new text.
        FileDiff.Span span = FileDiff.changedSpan("qator", "qator lar");

        assertEquals(5, span.start());
        assertEquals(5, span.leftEnd());
        assertEquals(9, span.rightEnd());
    }

    @Test
    void ignoringWhitespaceMakesIndentOnlyChangesDisappear() throws Exception {
        Path left = Files.writeString(tempDir.resolve("l.txt"), "bir\n    ikki\n");
        Path right = Files.writeString(tempDir.resolve("r.txt"), "bir\nikki\n");

        assertNull(FileDiff.compare(left, right).note());
        assertEquals("Fayllar bir xil.",
                FileDiff.compare(left, right, FileDiff.Whitespace.IGNORE).note());
    }

    @Test
    void factsForMissingAndDirectory() throws Exception {
        Path file = Files.writeString(tempDir.resolve("f.txt"), "12345");
        Path dir = Files.createDirectory(tempDir.resolve("papka"));

        FileDiff.FileFacts fileFacts = FileDiff.facts(file);
        assertTrue(fileFacts.exists());
        assertFalse(fileFacts.directory());
        assertEquals(5, fileFacts.size());
        assertEquals(Files.getLastModifiedTime(file).toInstant(), fileFacts.modified());

        assertTrue(FileDiff.facts(dir).directory());

        FileDiff.FileFacts missing = FileDiff.facts(tempDir.resolve("yo'q"));
        assertFalse(missing.exists());
        assertNull(missing.modified());
        assertNull(missing.error());
    }

    @Test
    void aFileOverTheSizeCapGetsANoteInsteadOfADiff() throws Exception {
        Path big = tempDir.resolve("big.log");
        Path small = tempDir.resolve("small.log");
        Files.writeString(big, "satr\n".repeat(2 * 1024 * 1024 / 5 + 10));
        Files.writeString(small, "satr\n");

        FileDiff.Comparison comparison = FileDiff.compare(big, small);

        assertTrue(comparison.note().contains("Fayl juda katta"));
        assertTrue(comparison.rows().isEmpty());
    }

    @Test
    void aPathThroughARegularFileIsUnreadableNotMissing() throws Exception {
        Path file = tempDir.resolve("plain.txt");
        Files.writeString(file, "x");

        FileDiff.FileFacts facts = FileDiff.facts(file.resolve("child"));

        assertFalse(facts.exists());
        assertNotNull(facts.error());
    }
}
