package uz.jtscorp.filesync.sync;

import uz.jtscorp.filesync.Texts;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** The ignore rule types the UI can produce. Must match what Unison ignores, or the counts drift. */
record IgnoreRules(List<Pattern> names, List<String> pathPrefixes, List<Pattern> regexes) {

    static IgnoreRules parse(List<String> ignorePatterns) throws IOException {
        List<Pattern> names = new ArrayList<>();
        List<String> pathPrefixes = new ArrayList<>();
        List<Pattern> regexes = new ArrayList<>();
        for (String rule : ignorePatterns) {
            if (rule.startsWith("Name ")) {
                names.add(Pattern.compile(globToRegex(rule.substring("Name ".length()).strip())));
            } else if (rule.startsWith("Path ")) {
                pathPrefixes.add(rule.substring("Path ".length()).strip());
            } else if (rule.startsWith("BelowPath ")) {
                // "BelowPath x" covers everything under x, "Path x" covers x itself
                // too -- but x is a directory, which contributes no file size, so the
                // same prefix match serves both.
                pathPrefixes.add(rule.substring("BelowPath ".length()).strip());
            } else if (rule.startsWith("Regex ")) {
                String body = rule.substring("Regex ".length()).strip();
                try {
                    regexes.add(Pattern.compile(body));
                } catch (PatternSyntaxException e) {
                    throw new IOException(Texts.t("risk.badRegex", body), e);
                }
            }
        }
        return new IgnoreRules(names, pathPrefixes, regexes);
    }

    boolean matches(String relativePath) {
        // Called for directories as well as files, hence "last component" rather than
        // "file name" -- Unison's "Name" rule matches the last component at any depth.
        String lastComponent = relativePath.contains("/")
                ? relativePath.substring(relativePath.lastIndexOf('/') + 1)
                : relativePath;
        return names.stream().anyMatch(p -> p.matcher(lastComponent).matches())
                || pathPrefixes.stream().anyMatch(prefix ->
                        relativePath.equals(prefix) || relativePath.startsWith(prefix + "/"))
                // Unison's "Regex" matches the whole path relative to the root.
                || regexes.stream().anyMatch(p -> p.matcher(relativePath).matches());
    }

    private static String globToRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '.' -> regex.append("\\.");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
        }
        return regex.toString();
    }
}
