package uz.jtscorp.filesync.sync;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Looks for the external programs the app shells out to. None of them is bundled, and a
 * missing one otherwise only shows up in the middle of the work it was needed for.
 */
public class ToolCheck {

    public enum Tool {
        /** Check and Apply. Nothing works without it. */
        UNISON("unison", "-version"),
        /** The review screen's line-by-line diff. */
        DIFF("diff", "--version"),
        /** Merging a conflict; Unison runs it, not this app. */
        DIFF3("diff3", "--version");

        private final String command;
        private final String versionFlag;

        Tool(String command, String versionFlag) {
            this.command = command;
            this.versionFlag = versionFlag;
        }

        public String command() {
            return command;
        }
    }

    /** @param version null when the tool is missing or printed nothing recognisable */
    public record Status(Tool tool, boolean found, String version) {}

    public record Report(List<Status> statuses) {

        public boolean found(Tool tool) {
            return statuses.stream().anyMatch(status -> status.tool() == tool && status.found());
        }

        public List<Tool> missing() {
            return statuses.stream().filter(status -> !status.found()).map(Status::tool).toList();
        }

        public String version(Tool tool) {
            return statuses.stream().filter(status -> status.tool() == tool)
                    .findFirst().map(Status::version).orElse(null);
        }
    }

    private static final Pattern VERSION = Pattern.compile("\\d+(?:\\.\\d+)+");

    public Report run() {
        List<Status> statuses = new ArrayList<>();
        for (Tool tool : Tool.values()) {
            statuses.add(probe(tool));
        }
        return new Report(List.copyOf(statuses));
    }

    /** Found means the program started; BSD and GNU diff disagree on a version flag's exit code. */
    private static Status probe(Tool tool) {
        try {
            Process process = new ProcessBuilder(tool.command, tool.versionFlag)
                    .redirectErrorStream(true)
                    .start();
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroy();
                return new Status(tool, true, null);
            }
            return new Status(tool, true,
                    parseVersion(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            return new Status(tool, false, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Status(tool, false, null);
        }
    }

    /** The first dotted number in the banner: "2.53.3", "3.10". Null when there is none. */
    static String parseVersion(String output) {
        Matcher matcher = VERSION.matcher(output);
        return matcher.find() ? matcher.group() : null;
    }
}
