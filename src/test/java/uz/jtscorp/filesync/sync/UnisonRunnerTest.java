package uz.jtscorp.filesync.sync;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The outputs below are trimmed from real unison 2.53.3 runs. */
class UnisonRunnerTest {

    private static final String PLAN_AND_QUIT = """
            Reconciling changes

            u1             u2
            new file ---->            a.txt

            2 items will be synced, 0 skipped

            Proceed with propagating updates? [] Terminated!
            """;

    @Test
    void quittingAtThePromptCountsAsACleanDryRun() {
        assertEquals(0, UnisonRunner.dryRunExitCode(new UnisonRunner.Result(3, PLAN_AND_QUIT)));
    }

    @Test
    void skippedItemsAreReportedAsExitCodeOne() {
        String output = PLAN_AND_QUIT.replace("0 skipped", "2 skipped");
        assertEquals(1, UnisonRunner.dryRunExitCode(new UnisonRunner.Result(3, output)));
    }

    @Test
    void conflictsForceTheRiskGateEvenWithoutASummaryLine() {
        // A conflict prompt interrupts the run before Unison prints its "N skipped"
        // summary, so the conflict itself has to raise the gate.
        String output = """
                  changed  <-?-> changed    conflict.docx
                Proceed with propagating updates? [] Terminated!
                """;
        assertEquals(1, UnisonRunner.dryRunExitCode(new UnisonRunner.Result(3, output)));
    }

    @Test
    void nothingToDoIsClean() {
        assertEquals(0, UnisonRunner.dryRunExitCode(
                new UnisonRunner.Result(0, "Nothing to do: replicas have not changed since last sync.\n")));
    }

    @Test
    void anUnsupportedOptionStaysFatal() {
        assertEquals(2, UnisonRunner.dryRunExitCode(
                new UnisonRunner.Result(2, "unison: unknown option `-dryrun'.\n")));
    }

    @Test
    void aFatalErrorIsNotMistakenForOurQuit() {
        // Same exit code as a clean quit, but no "Terminated!" -- must stay fatal.
        assertEquals(2, UnisonRunner.dryRunExitCode(new UnisonRunner.Result(3,
                "Fatal error: Failure reading from the standard input (End of file)\n")));
    }

    @Test
    void preferCommandScopesOnePathPerConflict() {
        assertEquals(
                List.of("unison", "ishwork", "-batch", "-prefer", "/home/u/Hujjatlar",
                        "-path", "hisobot.docx", "-path", "ichki papka/smeta.xlsx"),
                UnisonRunner.preferCommand("ishwork", "/home/u/Hujjatlar",
                        List.of("hisobot.docx", "ichki papka/smeta.xlsx"), List.of()));
    }

    @Test
    void preferCommandRefusesAnEmptyPathList() {
        // Without -path, `-prefer <root>` applies to the WHOLE tree: every conflict in
        // the profile gets overwritten from that side. An empty list means "nothing was
        // resolved", so building this command at all would be a data-loss bug.
        assertThrows(IllegalArgumentException.class,
                () -> UnisonRunner.preferCommand("ishwork", "/home/u/Hujjatlar", List.of(), List.of()));
    }

    @Test
    void mergeCommandScopesTheThreeWayTemplateToTheChosenPaths() {
        assertEquals(
                List.of("unison", "ishwork", "-batch", "-merge",
                        "Name * -> diff3 -m CURRENT1 CURRENTARCH CURRENT2 > NEW",
                        "-path", "eslatma.txt"),
                UnisonRunner.mergeCommand("ishwork", List.of("eslatma.txt"), List.of()));
    }

    @Test
    void mergeCommandRefusesAnEmptyPathList() {
        // "Name *" plus no -path would three-way merge the entire profile.
        assertThrows(IllegalArgumentException.class,
                () -> UnisonRunner.mergeCommand("ishwork", List.of(), List.of()));
    }

    @Test
    void skippedPathsBecomeOneRunIgnoresOnEveryCommand() {
        List<String> skips = List.of("hisobot/byudjet.xlsx");

        assertEquals(List.of("unison", "ishwork", "-batch"), UnisonRunner.batchCommand("ishwork", List.of()));
        assertEquals(List.of("unison", "ishwork", "-batch", "-ignore", "Path hisobot/byudjet.xlsx"),
                UnisonRunner.batchCommand("ishwork", skips));
        assertEquals(List.of("unison", "ishwork", "-batch", "-prefer", "/home/u/Hujjatlar",
                        "-path", "a.md", "-ignore", "Path hisobot/byudjet.xlsx"),
                UnisonRunner.preferCommand("ishwork", "/home/u/Hujjatlar", List.of("a.md"), skips));
        assertEquals(List.of("-ignore", "Path hisobot/byudjet.xlsx"),
                UnisonRunner.mergeCommand("ishwork", List.of("a.md"), skips).subList(7, 9));
    }

    @Test
    void globCharactersInASkippedPathAreEscaped() {
        // Unescaped, "Path a[1].txt" does not match the file a[1].txt, and the file the
        // user asked to skip is propagated anyway.
        assertEquals("Path ichki papka/o'zbek.txt", UnisonRunner.ignorePathPattern("ichki papka/o'zbek.txt"));
        assertEquals("Path a\\[1\\].txt", UnisonRunner.ignorePathPattern("a[1].txt"));
        assertEquals("Path b\\{x\\,y\\}\\*\\?.txt", UnisonRunner.ignorePathPattern("b{x,y}*?.txt"));
        assertEquals("Path c\\\\d.txt", UnisonRunner.ignorePathPattern("c\\d.txt"));
    }

    @Test
    void readsTheVersionNumberOutOfUnisonsBanner() {
        assertEquals("unison 2.53.3", UnisonRunner.parseVersion("unison version 2.53.3 (ocaml 4.14.1)\n"));
        assertNull(UnisonRunner.parseVersion("bash: unison: command not found"));
    }
}
