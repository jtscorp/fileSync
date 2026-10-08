package uz.jtscorp.filesync.sync;

import uz.jtscorp.filesync.Texts;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compares the two copies of one file directly, without Unison. */
public class FileDiff {

    /** Read far enough to catch a header-only binary; a text file this size has no NUL. */
    private static final int TEXT_PROBE_BYTES = 8192;

    /** Larger files are not diffed: the result is rendered row by row on the UI thread. */
    private static final long MAX_DIFF_BYTES = 2L * 1024 * 1024;

    /** Enough context lines to make the whole file one hunk, so every line is shown. */
    private static final String FULL_CONTEXT = "999999";

    /** How much whitespace difference still counts as a difference. Maps to a diff flag. */
    public enum Whitespace {
        KEEP(null), TRIM("-b"), IGNORE("-w");

        private final String flag;

        Whitespace(String flag) {
            this.flag = flag;
        }

        @Override
        public String toString() {
            return Texts.t("ws." + name().toLowerCase(Locale.ROOT));
        }
    }

    /**
     * Size and time of one copy. {@code exists} false with a non-null {@code error} means
     * the file is there but could not be examined.
     */
    public record FileFacts(boolean exists, boolean directory, long size, Instant modified,
                            String error) {}

    public static FileFacts facts(Path file) {
        try {
            BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
            return new FileFacts(true, attributes.isDirectory(), attributes.size(),
                    attributes.lastModifiedTime().toInstant(), null);
        } catch (NoSuchFileException e) {
            // Files.exists would also say "absent" for an unreadable file on a failing
            // disk, so only a genuine not-found counts as missing.
            return new FileFacts(false, false, 0, null, null);
        } catch (IOException e) {
            return new FileFacts(false, false, 0, null,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private static final Pattern HUNK_HEADER = Pattern.compile("^@@ -(\\d+)(?:,\\d+)? \\+(\\d+)(?:,\\d+)? @@");

    /** One aligned line pair. A null number and text mean that side has no line here. */
    public record DiffRow(Integer leftNumber, String leftText,
                          Integer rightNumber, String rightText, Kind kind) {

        public enum Kind { SAME, CHANGED, LEFT_ONLY, RIGHT_ONLY }
    }

    /** @param note set when there is no line diff to show (binary, one-sided, identical, unparsable) */
    public record Comparison(FileFacts left, FileFacts right, List<DiffRow> rows, String note) {}

    public static Comparison compare(Path left, Path right) {
        return compare(left, right, Whitespace.KEEP);
    }

    public static Comparison compare(Path left, Path right, Whitespace whitespace) {
        FileFacts leftFacts = facts(left);
        FileFacts rightFacts = facts(right);
        if (leftFacts.error() != null || rightFacts.error() != null) {
            String error = leftFacts.error() != null ? leftFacts.error() : rightFacts.error();
            return note(leftFacts, rightFacts, Texts.t("filediff.unreadable", error));
        }
        if (!leftFacts.exists() || !rightFacts.exists()) {
            return note(leftFacts, rightFacts, Texts.t("filediff.oneSided"));
        }
        if (leftFacts.size() > MAX_DIFF_BYTES || rightFacts.size() > MAX_DIFF_BYTES) {
            return note(leftFacts, rightFacts, Texts.t("filediff.tooBig"));
        }
        if (!bothLookLikeText(left, right)) {
            return note(leftFacts, rightFacts, Texts.t("filediff.binary"));
        }
        String output = runDiff(left, right, whitespace);
        if (output.isBlank()) {
            return note(leftFacts, rightFacts, Texts.t("filediff.same"));
        }
        List<DiffRow> rows = parse(output);
        // No rows means no hunk header: diff reported trouble rather than a difference,
        // and its own words beat anything we could invent.
        return rows.isEmpty()
                ? note(leftFacts, rightFacts, output)
                : new Comparison(leftFacts, rightFacts, rows, null);
    }

    /**
     * Flattens the aligned rows into one column, the way a unified viewer shows them: a
     * changed line becomes its old version followed by its new one.
     */
    public static List<DiffRow> toUnified(List<DiffRow> rows) {
        List<DiffRow> unified = new ArrayList<>();
        for (DiffRow row : rows) {
            if (row.kind() == DiffRow.Kind.CHANGED) {
                unified.add(new DiffRow(row.leftNumber(), row.leftText(), null, null,
                        DiffRow.Kind.LEFT_ONLY));
                unified.add(new DiffRow(null, null, row.rightNumber(), row.rightText(),
                        DiffRow.Kind.RIGHT_ONLY));
            } else {
                unified.add(row);
            }
        }
        return unified;
    }

    /**
     * Turns {@code diff -u} output into aligned rows: removed and added lines are buffered
     * until the next context line and then paired off.
     */
    static List<DiffRow> parse(String unifiedDiff) {
        List<DiffRow> rows = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> added = new ArrayList<>();
        // An array only so flush() can advance them: [0] = left line number, [1] = right.
        int[] lineNumbers = new int[2];
        boolean inHunk = false;

        for (String line : unifiedDiff.split("\n", -1)) {
            Matcher hunk = HUNK_HEADER.matcher(line);
            if (hunk.find()) {
                flush(rows, removed, added, lineNumbers);
                lineNumbers[0] = Integer.parseInt(hunk.group(1));
                lineNumbers[1] = Integer.parseInt(hunk.group(2));
                inHunk = true;
            } else if (!inHunk || line.isEmpty() || line.startsWith("\\")) {
                // Header lines before the first hunk, "\ No newline at the end of file", and
                // the empty tail the final newline leaves behind. An empty string is never
                // a diff line: a blank context line arrives as " ", with its prefix.
            } else if (line.startsWith("-")) {
                removed.add(line.substring(1));
            } else if (line.startsWith("+")) {
                added.add(line.substring(1));
            } else {
                flush(rows, removed, added, lineNumbers);
                String text = line.substring(1);
                rows.add(new DiffRow(lineNumbers[0]++, text, lineNumbers[1]++, text,
                        DiffRow.Kind.SAME));
            }
        }
        flush(rows, removed, added, lineNumbers);
        return rows;
    }

    private static void flush(List<DiffRow> rows, List<String> removed, List<String> added,
                              int[] lineNumbers) {
        for (int i = 0; i < Math.max(removed.size(), added.size()); i++) {
            String left = i < removed.size() ? removed.get(i) : null;
            String right = i < added.size() ? added.get(i) : null;
            if (left != null && right != null) {
                rows.add(new DiffRow(lineNumbers[0]++, left, lineNumbers[1]++, right,
                        DiffRow.Kind.CHANGED));
            } else if (left != null) {
                rows.add(new DiffRow(lineNumbers[0]++, left, null, null, DiffRow.Kind.LEFT_ONLY));
            } else {
                rows.add(new DiffRow(null, null, lineNumbers[1]++, right, DiffRow.Kind.RIGHT_ONLY));
            }
        }
        removed.clear();
        added.clear();
    }

    /** Index of the first row of each run of differing rows; a run counts as one difference. */
    public static List<Integer> differenceBlocks(List<DiffRow> rows) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            boolean differs = rows.get(i).kind() != DiffRow.Kind.SAME;
            boolean previousDiffered = i > 0 && rows.get(i - 1).kind() != DiffRow.Kind.SAME;
            if (differs && !previousDiffered) {
                starts.add(i);
            }
        }
        return starts;
    }

    /** The stretch of characters that actually differs, as [start, leftEnd) / [start, rightEnd). */
    public record Span(int start, int leftEnd, int rightEnd) {}

    /**
     * Narrows a changed line to the characters that differ by trimming the common prefix
     * and suffix. A line whose words moved around is highlighted whole.
     */
    public static Span changedSpan(String left, String right) {
        int start = 0;
        int shorter = Math.min(left.length(), right.length());
        while (start < shorter && left.charAt(start) == right.charAt(start)) {
            start++;
        }
        int leftEnd = left.length();
        int rightEnd = right.length();
        while (leftEnd > start && rightEnd > start
                && left.charAt(leftEnd - 1) == right.charAt(rightEnd - 1)) {
            leftEnd--;
            rightEnd--;
        }
        return new Span(start, leftEnd, rightEnd);
    }

    private static Comparison note(FileFacts leftFacts, FileFacts rightFacts, String note) {
        return new Comparison(leftFacts, rightFacts, List.of(), note);
    }

    private static String runDiff(Path left, Path right, Whitespace whitespace) {
        List<String> command = new ArrayList<>(List.of("diff", "-u", "-U", FULL_CONTEXT));
        if (whitespace.flag != null) {
            command.add(whitespace.flag);
        }
        command.add(left.toString());
        command.add(right.toString());
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            process.waitFor();
            // diff exits 1 for "differs" and 2 for trouble, but its own message is more
            // useful than any code we could map it to, so only silence tells us nothing.
            return output;
        } catch (IOException e) {
            return Texts.t("filediff.notStarted", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Texts.t("filediff.stopped");
        }
    }

    /**
     * NUL-byte probe on both copies. Gates merging: {@code diff3} would merge a binary as
     * lines and corrupt both copies.
     */
    public static boolean bothLookLikeText(Path left, Path right) {
        return looksLikeText(left) && looksLikeText(right);
    }

    private static boolean looksLikeText(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            byte[] probe = in.readNBytes(TEXT_PROBE_BYTES);
            for (byte b : probe) {
                if (b == 0) {
                    return false;
                }
            }
            return true;
        } catch (IOException e) {
            // Unreadable is not provably text, and merging it would be a guess.
            return false;
        }
    }
}
