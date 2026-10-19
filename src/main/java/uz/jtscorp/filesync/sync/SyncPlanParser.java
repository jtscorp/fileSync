package uz.jtscorp.filesync.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SyncPlanParser {

    // One plan line, e.g.:
    //   "new file  ---->                foo.txt"
    //   "              <---- deleted     bar.txt"
    //   "<-?->                           conflict.txt"
    // A -force run prints "====>" / "<====" for rows where one side is overwritten; the
    // state word then belongs to the side that loses (right column for "====>").
    // The arrow is the anchor. State words are matched loosely because Unison has more
    // states than a fixed list ("props", "new dir", ...). The lookahead in rightState keeps
    // it from eating the start of a file name such as "changed.txt".
    // src/test/resources/sample-dryrun-output.txt is a real capture to test changes against.
    private static final Pattern ITEM_LINE = Pattern.compile(
            "^\\s*(?<leftState>[A-Za-z]+(?:\\s+[A-Za-z]+)?)?\\s*"
                    + "(?<arrow>---->|<----|<-\\?->|====>|<====)\\s*"
                    + "(?<rightState>[A-Za-z]+(?:\\s+[A-Za-z]+)?(?=\\s))?\\s*"
                    + "(?<path>\\S.*\\S|\\S)\\s*$");

    // Without a TTY Unison redraws its prompt on the same line, so an item can arrive
    // repeated, each copy followed by "  []" or "  [f]". The real content follows the
    // last such token. A file name containing "  [x]" would be cut here.
    private static final Pattern PROMPT_ECHO = Pattern.compile(".*\\s\\s\\[[A-Za-z]?]");

    public List<SyncAction> parse(String rawOutput) {
        List<SyncAction> actions = new ArrayList<>();
        for (String rawLine : rawOutput.split("\n")) {
            Matcher echo = PROMPT_ECHO.matcher(rawLine);
            String line = echo.lookingAt() ? rawLine.substring(echo.end()) : rawLine;
            Matcher matcher = ITEM_LINE.matcher(line);
            if (!matcher.matches()) {
                continue;
            }
            String path = matcher.group("path").strip();
            String arrow = matcher.group("arrow");
            String state = matcher.group("leftState") != null
                    ? matcher.group("leftState")
                    : matcher.group("rightState");

            if (arrow.equals("====>") || arrow.equals("<====")) {
                // The loser's creation is removed, its deletion is undone, anything else overwritten.
                SyncAction.ActionType forcedType = "deleted".equals(state)
                        ? SyncAction.ActionType.NEW
                        : isCreation(state) ? SyncAction.ActionType.DELETED : SyncAction.ActionType.CHANGED;
                SyncAction.Direction forcedDirection = arrow.equals("====>")
                        ? SyncAction.Direction.LAPTOP_TO_HDD
                        : SyncAction.Direction.HDD_TO_LAPTOP;
                actions.add(new SyncAction(path, forcedDirection, forcedType, SyncAction.Origin.MODE));
                continue;
            }
            SyncAction.Direction direction = switch (arrow) {
                case "---->" -> SyncAction.Direction.LAPTOP_TO_HDD;
                case "<----" -> SyncAction.Direction.HDD_TO_LAPTOP;
                default -> SyncAction.Direction.CONFLICT;
            };
            SyncAction.ActionType actionType;
            if ("<-?->".equals(arrow)) {
                actionType = SyncAction.ActionType.CONFLICT;
            } else if ("deleted".equals(state)) {
                actionType = SyncAction.ActionType.DELETED;
            } else if (isCreation(state)) {
                actionType = SyncAction.ActionType.NEW;
            } else {
                actionType = SyncAction.ActionType.CHANGED;
            }

            actions.add(new SyncAction(path, direction, actionType));
        }
        return actions;
    }

    // "new file" / "new dir" / "new link" normally; on the first check, with no archive,
    // Unison prints the bare "file" / "dir" / "link" for the same thing.
    private static boolean isCreation(String state) {
        return state != null
                && (state.startsWith("new ")
                        || state.equals("file") || state.equals("dir") || state.equals("link"));
    }
}
