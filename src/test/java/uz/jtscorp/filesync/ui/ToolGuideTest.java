package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.sync.ToolCheck.Report;
import uz.jtscorp.filesync.sync.ToolCheck.Status;
import uz.jtscorp.filesync.sync.ToolCheck.Tool;
import uz.jtscorp.filesync.ui.ToolGuide.Os;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ToolGuideTest {

    private static Report report(boolean unison, boolean diff, boolean diff3) {
        return new Report(List.of(new Status(Tool.UNISON, unison, null),
                new Status(Tool.DIFF, diff, null), new Status(Tool.DIFF3, diff3, null)));
    }

    @Test
    void recognisesTheSystemFromTheJvmsName() {
        assertEquals(Os.LINUX, Os.from("Linux"));
        assertEquals(Os.WINDOWS, Os.from("Windows 11"));
        assertEquals(Os.MACOS, Os.from("Mac OS X"));
        // An unknown Unix is far likelier than anything else.
        assertEquals(Os.LINUX, Os.from("FreeBSD"));
        assertEquals(Os.LINUX, Os.from(null));
    }

    @Test
    void everyToolHasSomethingToCopyAndStepsOnEverySystem() {
        for (Tool tool : Tool.values()) {
            for (Os os : Os.values()) {
                ToolGuide.Guide guide = ToolGuide.guide(tool, os);
                assertFalse(guide.copyText().isBlank(), tool + " on " + os);
                assertFalse(guide.steps().isEmpty(), tool + " on " + os);
            }
        }
    }

    @Test
    void diffAndDiff3ComeFromTheSamePackage() {
        assertEquals("sudo apt install unison", ToolGuide.guide(Tool.UNISON, Os.LINUX).copyText());
        assertEquals("brew install unison", ToolGuide.guide(Tool.UNISON, Os.MACOS).copyText());
        for (Os os : Os.values()) {
            assertEquals(ToolGuide.guide(Tool.DIFF, os), ToolGuide.guide(Tool.DIFF3, os));
        }
    }

    @Test
    void onlyAMissingUnisonStopsTheUser() {
        assertEquals("Hamma dasturlar joyida", ToolGuide.footerLine(report(true, true, true)));
        assertEquals("Davom etish mumkin — yo'q dasturga bog'liq qism ishlamaydi",
                ToolGuide.footerLine(report(true, false, false)));
        assertEquals("Unison o'rnatilmaguncha davom etib bo'lmaydi",
                ToolGuide.footerLine(report(false, true, true)));
        assertEquals("", ToolGuide.missingLine(report(true, true, true)));
        assertEquals("2 ta dastur yo'q", ToolGuide.missingLine(report(true, false, false)));
    }
}
