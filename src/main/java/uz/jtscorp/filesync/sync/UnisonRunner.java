package uz.jtscorp.filesync.sync;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class UnisonRunner {

    public record Result(int exitCode, String output) {}

    /** Matches the "2 items will be synced, 0 skipped" summary line. */
    private static final Pattern SKIPPED_COUNT = Pattern.compile("(\\d+)\\s+skipped");

    private static final Pattern VERSION = Pattern.compile("version\\s+(\\d+(?:\\.\\d+)+)");

    public boolean isUnisonAvailable() {
        try {
            Process process = new ProcessBuilder("unison", "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(10, TimeUnit.SECONDS);
            return finished && process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    /** "unison 2.53.3" for the sidebar, or null when Unison is missing or silent. */
    public String version() {
        try {
            Process process = new ProcessBuilder("unison", "-version")
                    .redirectErrorStream(true)
                    .start();
            if (!process.waitFor(10, TimeUnit.SECONDS)) {
                process.destroy();
                return null;
            }
            return parseVersion(new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }

    static String parseVersion(String output) {
        Matcher matcher = VERSION.matcher(output);
        return matcher.find() ? "unison " + matcher.group(1) : null;
    }

    /**
     * Produces the sync plan without touching any file.
     *
     * <p>Unison 2.53.3 has no {@code -dryrun}, so this runs the text UI with {@code -auto}
     * and feeds it {@code "\n0\nL\nq\n"}: the newline dismisses the first-run banner,
     * {@code 0} and {@code L} list every item (otherwise a conflict prompt hides the rest
     * of the plan), and {@code q} quits before anything is propagated.
     */
    public Result dryRun(String profileName) throws IOException, InterruptedException {
        Result raw = run(List.of("unison", profileName, "-auto", "-ui", "text"), "\n0\nL\nq\n");
        return new Result(dryRunExitCode(raw), raw.output());
    }

    /**
     * Maps the quit-based dry run to 0 = clean, 1 = some items skipped, 2 = failure.
     * Our quit is exit 3 with "Terminated!"; any other non-zero exit is a failure.
     */
    static int dryRunExitCode(Result raw) {
        boolean quitCleanly = raw.exitCode() == 3 && raw.output().contains("Terminated!");
        if (raw.exitCode() != 0 && !quitCleanly) {
            return 2;
        }
        // A conflict prompt means the "N skipped" summary is never reached, so
        // conflicts are read off the plan itself.
        if (raw.output().contains("<-?->")) {
            return 1;
        }
        Matcher matcher = SKIPPED_COUNT.matcher(raw.output());
        return matcher.find() && !matcher.group(1).equals("0") ? 1 : 0;
    }

    /**
     * @param skipPaths files left out of this Apply, each passed as a one-run
     *                  {@code -ignore "Path ..."}; nothing is written to the profile
     */
    public Result apply(String profileName, List<String> skipPaths) throws IOException, InterruptedException {
        return run(batchCommand(profileName, skipPaths), null);
    }

    static List<String> batchCommand(String profileName, List<String> skipPaths) {
        List<String> command = new ArrayList<>(List.of("unison", profileName, "-batch"));
        addSkips(command, skipPaths);
        return command;
    }

    private static void addSkips(List<String> command, List<String> skipPaths) {
        for (String path : skipPaths) {
            command.add("-ignore");
            command.add(ignorePathPattern(path));
        }
    }

    /**
     * A {@code Path} rule is a glob: unescaped, {@code hisobot[1].xlsx} does not match its
     * own name and the file would be synced anyway.
     */
    static String ignorePathPattern(String path) {
        StringBuilder pattern = new StringBuilder("Path ");
        for (char c : path.toCharArray()) {
            if ("\\*?[]{},".indexOf(c) >= 0) {
                pattern.append('\\');
            }
            pattern.append(c);
        }
        return pattern.toString();
    }

    /**
     * Resolves the given conflicts in favour of {@code preferRoot}; run after {@link #apply}.
     *
     * <p>Uses {@code -prefer}, not {@code -force}: if a file is no longer a conflict,
     * {@code -prefer} falls back to the normal direction instead of overwriting.
     */
    public Result applyPreferring(String profileName, String preferRoot, List<String> paths,
                                  List<String> skipPaths) throws IOException, InterruptedException {
        return run(preferCommand(profileName, preferRoot, paths, skipPaths), null);
    }

    static List<String> preferCommand(String profileName, String preferRoot, List<String> paths,
                                      List<String> skipPaths) {
        return scopedCommand(profileName, "-prefer", preferRoot, paths, skipPaths);
    }

    /**
     * Three-way merges the given conflicts.
     *
     * <p>Needs {@code backupcurrent} in the profile from before the conflict arose. When
     * both sides edited the same lines the merge fails: Unison exits 2 and leaves both
     * copies untouched.
     */
    public Result applyMerging(String profileName, List<String> paths, List<String> skipPaths)
            throws IOException, InterruptedException {
        return run(mergeCommand(profileName, paths, skipPaths), null);
    }

    static List<String> mergeCommand(String profileName, List<String> paths, List<String> skipPaths) {
        // "Name *" is safe only because -path already narrows the run to the files the
        // user picked; without that scoping this template would merge the whole tree.
        return scopedCommand(profileName, "-merge",
                "Name * -> diff3 -m CURRENT1 CURRENTARCH CURRENT2 > NEW", paths, skipPaths);
    }

    /**
     * {@code -path} accumulates, so one run covers every path. An empty list is refused:
     * without {@code -path} the option would apply to the whole tree.
     */
    private static List<String> scopedCommand(String profileName, String option, String value,
                                              List<String> paths, List<String> skipPaths) {
        if (paths.isEmpty()) {
            throw new IllegalArgumentException(option + " command needs at least one path");
        }
        List<String> command = new ArrayList<>(
                List.of("unison", profileName, "-batch", option, value));
        for (String path : paths) {
            command.add("-path");
            command.add(path);
        }
        // ApplyPlan already keeps a skipped file out of the paths above; the ignore is
        // the second lock on the same door.
        addSkips(command, skipPaths);
        return command;
    }

    private Result run(List<String> command, String stdin) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        if (stdin != null) {
            try (OutputStream out = process.getOutputStream()) {
                out.write(stdin.getBytes(StandardCharsets.UTF_8));
            } catch (IOException ignored) {
                // Unison quits as soon as it reads 'q', so the tail of our input can hit a
                // closed pipe. The exit code and output collected below are what matter.
            }
        }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        return new Result(exitCode, output);
    }
}
