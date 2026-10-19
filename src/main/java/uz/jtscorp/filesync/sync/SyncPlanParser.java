package uz.jtscorp.filesync.sync;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SyncPlanParser {

    // One plan line, e.g.:
    //   "new file  ---->                foo.txt"
    //   "              <---- deleted     bar.txt"
    //   "deleted  <-?-> chgd dir   d"
    //   "deleted  ====> changed    adel.txt"
    // A -force run prints "====>" / "<====" for rows where the forced side wins; either column
    // may carry a state there, so both are read (see forced()).
    // The arrow is the anchor. State words are matched loosely because Unison has more
    // states than a fixed list ("props", "deleted", ...), but only "new file|dir|link" and
    // "chgd dir" have two words; any other state is one word, or a path with a space
    // ("my file.txt") would lose its first word. A right-hand state sits one space after the
    // arrow (a blank column is padded), and its lookahead keeps it from eating the start of a
    // file name such as "changed.txt".
    // src/test/resources/sample-dryrun-output.txt and sample-two-sided-dryrun-output.txt are
    // real 2.53.3 captures to test changes against.
    private static final String STATE = "new\\s+(?:file|dir|link)|chgd\\s+dir|[A-Za-z]+";
    private static final Pattern ITEM_LINE = Pattern.compile(
            "^\\s*(?<leftState>" + STATE + ")?\\s*"
                    + "(?<arrow>---->|<----|<-\\?->|====>|<====)"
                    + "(?:\\s(?<rightState>" + STATE + ")(?=\\s))?\\s*"
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
            String left = matcher.group("leftState");
            String right = matcher.group("rightState");

            if (arrow.equals("====>") || arrow.equals("<====")) {
                // The left column is Main; "====>" means Main wins and the backup is written.
                boolean towardHdd = arrow.equals("====>");
                actions.add(new SyncAction(path,
                        towardHdd ? SyncAction.Direction.LAPTOP_TO_HDD : SyncAction.Direction.HDD_TO_LAPTOP,
                        forced(towardHdd ? left : right, towardHdd ? right : left),
                        SyncAction.Origin.MODE));
                continue;
            }
            String state = left != null ? left : right;
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

    /**
     * What a forced row does to the losing side. The winner's delete removes the loser's copy
     * whatever the loser did to it ("deleted ====> changed" deletes an edit); the loser's delete
     * is undone; the loser's own creation, with nothing on the winner's side, is removed; anything
     * else, a creation on both sides included, overwrites the loser's copy.
     */
    private static SyncAction.ActionType forced(String winner, String loser) {
        if ("deleted".equals(winner)) {
            return SyncAction.ActionType.DELETED;
        }
        if ("deleted".equals(loser)) {
            return SyncAction.ActionType.NEW;
        }
        if (winner == null && isCreation(loser)) {
            return SyncAction.ActionType.DELETED;
        }
        return SyncAction.ActionType.CHANGED;
    }

    // "new file" / "new dir" / "new link" normally; on the first check, with no archive,
    // Unison prints the bare "file" / "dir" / "link" for the same thing.
    private static boolean isCreation(String state) {
        return state != null
                && (state.startsWith("new ")
                        || state.equals("file") || state.equals("dir") || state.equals("link"));
    }
}
