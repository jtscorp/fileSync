package uz.jtscorp.filesync.sync;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SyncPlanParserTest {

    @Test
    void parsesSampleDryRunOutputIntoActions() throws IOException {
        String rawOutput;
        try (InputStream in = getClass().getResourceAsStream("/sample-dryrun-output.txt")) {
            rawOutput = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }

        List<SyncAction> actions = new SyncPlanParser().parse(rawOutput);

        assertTrue(actions.stream().anyMatch(a ->
                a.relativePath().equals("newfile.txt") && a.actionType() == SyncAction.ActionType.NEW));
        assertTrue(actions.stream().anyMatch(a ->
                a.relativePath().equals("newdir") && a.actionType() == SyncAction.ActionType.NEW));
        assertTrue(actions.stream().anyMatch(a ->
                a.relativePath().equals("tobedeleted.txt") && a.actionType() == SyncAction.ActionType.DELETED));
        assertTrue(actions.stream().anyMatch(a ->
                a.relativePath().equals("changed.txt") && a.actionType() == SyncAction.ActionType.CHANGED));
        assertTrue(actions.stream().anyMatch(a ->
                a.relativePath().equals("propsdir") && a.actionType() == SyncAction.ActionType.CHANGED));
        assertTrue(actions.stream().anyMatch(a ->
                a.relativePath().equals("conflict.docx") && a.actionType() == SyncAction.ActionType.CONFLICT));
    }

    @Test
    void keepsTheConflictRowThatArrivesGluedToAPromptRedraw() {
        // Real capture: with no TTY, Unison reprints the current item on the same line
        // for every prompt redraw. The conflict must survive that, not be dropped with
        // the noise -- it is the row the user most needs to see.
        String rawOutput = "changed  <-?-> changed    conflict.docx  [] changed  <-?-> changed  "
                + "  conflict.docx  []   changed  <-?-> changed    conflict.docx  \n";

        List<SyncAction> actions = new SyncPlanParser().parse(rawOutput);

        assertEquals(1, actions.size());
        assertEquals("conflict.docx", actions.getFirst().relativePath());
        assertEquals(SyncAction.ActionType.CONFLICT, actions.getFirst().actionType());
    }

    @Test
    void treatsFirstCheckWordingAsCreationsNotModifications() {
        // With no archive yet Unison prints the bare "file"/"dir" rather than
        // "new file"/"new dir", but the items are still creations.
        String rawOutput = """
                file     ---->            new1.txt \s
                dir      ---->            newdir \s
                """;

        List<SyncAction> actions = new SyncPlanParser().parse(rawOutput);

        assertEquals(2, actions.size());
        assertTrue(actions.stream().allMatch(a -> a.actionType() == SyncAction.ActionType.NEW),
                "first-check wording must map to NEW, got: " + actions);
    }

    @Test
    void ignoresNonItemLines() {
        String rawOutput = "Looking for changes\nReconciling changes\n\n";

        List<SyncAction> actions = new SyncPlanParser().parse(rawOutput);

        assertEquals(0, actions.size());
    }

    @Test
    void parsesLinesWithUnrecognizedStateWordsInsteadOfDroppingThem() {
        // "props" isn't in the fixed keyword list, but Unison emits it and
        // unison -batch would still act on the line -- the parser must not drop it.
        // "new dir" is a creation, same as "new file".
        String rawOutput = """
                new dir  ---->                someDir
                props     ---->                somefile.txt
                """;

        List<SyncAction> actions = new SyncPlanParser().parse(rawOutput);

        assertEquals(2, actions.size());
        assertTrue(actions.stream().anyMatch(a ->
                a.relativePath().equals("someDir") && a.actionType() == SyncAction.ActionType.NEW));
        assertTrue(actions.stream().anyMatch(a ->
                a.relativePath().equals("somefile.txt") && a.actionType() == SyncAction.ActionType.CHANGED));
    }

    @Test
    void stillSplitsChangedStateFromFilenameStartingWithChanged() {
        // Regression check for the bug the (?=\s) lookahead fixed: rightState must
        // not swallow the leading "changed" of a filename that has no separating
        // space, leaving only ".txt" as the parsed path.
        String rawOutput = "changed   ---->                                       changed.txt\n";

        List<SyncAction> actions = new SyncPlanParser().parse(rawOutput);

        assertEquals(1, actions.size());
        assertEquals("changed.txt", actions.getFirst().relativePath());
        assertEquals(SyncAction.ActionType.CHANGED, actions.getFirst().actionType());
    }
}
