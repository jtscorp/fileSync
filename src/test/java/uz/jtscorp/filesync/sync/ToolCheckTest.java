package uz.jtscorp.filesync.sync;

import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.sync.ToolCheck.Report;
import uz.jtscorp.filesync.sync.ToolCheck.Status;
import uz.jtscorp.filesync.sync.ToolCheck.Tool;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolCheckTest {

    @Test
    void readsTheVersionOutOfEachToolsBanner() {
        assertEquals("2.53.3", ToolCheck.parseVersion("unison version 2.53.3 (ocaml 4.14.1)\n"));
        assertEquals("3.10", ToolCheck.parseVersion("diff (GNU diffutils) 3.10\nCopyright (C) 2023\n"));
        assertEquals("2.8.1", ToolCheck.parseVersion("Apple diff (based on FreeBSD diff)\ndiff3 2.8.1"));
        assertNull(ToolCheck.parseVersion("usage: diff3 [-3aAeEimTxX] file1 file2 file3"));
    }

    @Test
    void aReportKnowsWhatIsMissing() {
        Report report = new Report(List.of(
                new Status(Tool.UNISON, true, "2.53.3"),
                new Status(Tool.DIFF, false, null),
                new Status(Tool.DIFF3, true, null)));

        assertTrue(report.found(Tool.UNISON));
        assertFalse(report.found(Tool.DIFF));
        assertEquals(List.of(Tool.DIFF), report.missing());
        assertEquals("2.53.3", report.version(Tool.UNISON));
        assertNull(report.version(Tool.DIFF3));
    }
}
