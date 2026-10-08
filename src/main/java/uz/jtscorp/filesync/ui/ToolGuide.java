package uz.jtscorp.filesync.ui;

import uz.jtscorp.filesync.Texts;
import uz.jtscorp.filesync.sync.ToolCheck.Report;
import uz.jtscorp.filesync.sync.ToolCheck.Tool;

import java.util.List;
import java.util.Locale;

/**
 * What the "Kerakli dasturlar" screen says: what each external program is for, what
 * stops working without it, and how to install it on each system.
 */
final class ToolGuide {

    enum Os {
        LINUX("Linux"), WINDOWS("Windows"), MACOS("macOS");

        private final String label;

        Os(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        /** @param osName the JVM's {@code os.name} */
        static Os from(String osName) {
            String name = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
            if (name.contains("win")) {
                return WINDOWS;
            }
            return name.contains("mac") || name.contains("darwin") ? MACOS : LINUX;
        }
    }

    /**
     * @param copyText the one thing worth copying: a command, or on Windows the download
     *                 page or the folder to put on PATH
     * @param steps    what to do with it, in order
     */
    record Guide(String copyText, List<String> steps) {}

    private ToolGuide() {}

    static String purpose(Tool tool) {
        return Texts.t("tools.purpose." + tool.command());
    }

    static String consequence(Tool tool) {
        return Texts.t("tools.consequence." + tool.command());
    }

    static Guide guide(Tool tool, Os os) {
        // diff and diff3 come from the same package everywhere.
        boolean unison = tool == Tool.UNISON;
        return switch (os) {
            case LINUX -> unison
                    ? new Guide("sudo apt install unison", List.of(
                            Texts.t("tools.linux.apt"),
                            "Fedora: sudo dnf install unison",
                            "Arch: sudo pacman -S unison"))
                    : new Guide("sudo apt install diffutils", List.of(
                            Texts.t("tools.linux.apt"),
                            "Fedora: sudo dnf install diffutils",
                            "Arch: sudo pacman -S diffutils"));
            case WINDOWS -> unison
                    ? new Guide("https://github.com/bcpierce00/unison/releases", List.of(
                            Texts.t("tools.win.unison.download"),
                            Texts.t("tools.win.unison.unpack"),
                            Texts.t("tools.win.unison.path"),
                            Texts.t("tools.reopen")))
                    : new Guide("C:\\Program Files\\Git\\usr\\bin", List.of(
                            Texts.t("tools.win.diff.git"),
                            Texts.t("tools.win.diff.path"),
                            Texts.t("tools.reopen")));
            case MACOS -> unison
                    ? new Guide("brew install unison", List.of(
                            Texts.t("tools.mac.unison.run"),
                            Texts.t("tools.mac.unison.brew")))
                    : new Guide("xcode-select --install", List.of(
                            Texts.t("tools.mac.diff.builtin"),
                            Texts.t("tools.mac.diff.clt")));
        };
    }

    /** The sidebar's warning line; empty when nothing is missing. */
    static String missingLine(Report report) {
        int missing = report.missing().size();
        return missing == 0 ? "" : Texts.n("tools.missingLine", missing);
    }

    /** The footer of the tools screen. */
    static String footerLine(Report report) {
        if (report.missing().isEmpty()) {
            return Texts.t("tools.footer.ok");
        }
        return report.found(Tool.UNISON)
                ? Texts.t("tools.footer.partial") : Texts.t("tools.footer.blocked");
    }
}
