package uz.jtscorp.filesync.ui;

import org.junit.jupiter.api.Test;
import uz.jtscorp.filesync.sync.SyncAction;
import uz.jtscorp.filesync.sync.SyncAction.ActionType;
import uz.jtscorp.filesync.sync.SyncAction.Direction;
import uz.jtscorp.filesync.ui.ChangeList.Sort;
import uz.jtscorp.filesync.ui.ChangeTree.Folder;
import uz.jtscorp.filesync.ui.ChangeTree.Leaf;
import uz.jtscorp.filesync.ui.ChangeTree.Node;
import uz.jtscorp.filesync.ui.ChangeTree.Ticks;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChangeTreeTest {

    private static ChangeList.Entry entry(String path) {
        return new ChangeList.Entry(new SyncAction(path, Direction.LAPTOP_TO_HDD, ActionType.CHANGED), null);
    }

    /** One line per node: indent by depth, a folder's name ending in '/' and its file count. */
    private static List<String> lines(List<Node> nodes) {
        return nodes.stream().map(node -> switch (node) {
            case Folder f -> "  ".repeat(f.depth()) + f.name() + "/ " + f.files().size();
            case Leaf l -> "  ".repeat(l.depth()) + l.entry().action().relativePath();
        }).toList();
    }

    private static List<String> tree(List<String> paths, Set<String> closed, Sort sort, boolean reversed) {
        return lines(ChangeTree.flatten(paths.stream().map(ChangeTreeTest::entry).toList(), closed, sort, reversed));
    }

    @Test
    void foldersComeFirstByNameAndFilesKeepTheListOrder() {
        // The list is ordered by size, say: the files keep that order inside each folder.
        List<String> paths = List.of("z.txt", "b/2.txt", "a/x.txt", "b/1.txt", "a.txt");
        assertEquals(List.of(
                "a/ 1",
                "  a/x.txt",
                "b/ 2",
                "  b/2.txt",
                "  b/1.txt",
                "z.txt",
                "a.txt"), tree(paths, Set.of(), Sort.SIZE, false));
    }

    @Test
    void aFolderWithOnlyOneSubfolderIsMergedIntoIt() {
        List<String> paths = List.of(
                "backend/src/main/resources/app.yml",
                "backend/src/main/resources/db/v1.sql",
                "backend/build.gradle");
        assertEquals(List.of(
                "backend/ 3",
                "  src/main/resources/ 2",
                "    db/ 1",
                "      backend/src/main/resources/db/v1.sql",
                "    backend/src/main/resources/app.yml",
                "  backend/build.gradle"), tree(paths, Set.of(), Sort.PATH, false));
    }

    @Test
    void aMergedFolderIsKnownByItsDeepestPath() {
        List<Node> nodes = ChangeTree.flatten(List.of(entry("a/b/c/f.txt")), Set.of(), Sort.PATH, false);
        Folder folder = (Folder) nodes.get(0);
        assertEquals("a/b/c", folder.name());
        assertEquals("a/b/c", folder.path());
        assertEquals(0, folder.depth());
        assertEquals(1, ((Leaf) nodes.get(1)).depth());
    }

    @Test
    void aClosedFolderHidesWhatIsUnderItButStillCountsIt() {
        List<String> paths = List.of("a/b/1.txt", "a/c/2.txt", "a/3.txt");
        assertEquals(List.of("a/ 3"), tree(paths, Set.of("a"), Sort.PATH, false));
        assertEquals(List.of(
                "a/ 3",
                "  b/ 1",
                "  c/ 1",
                "    a/c/2.txt",
                "  a/3.txt"), tree(paths, Set.of("a/b"), Sort.PATH, false));
    }

    @Test
    void foldersTurnAroundOnlyWithADescendingPathOrNameOrder() {
        List<String> paths = List.of("a/1.txt", "b/2.txt");
        assertEquals(List.of("b/ 1", "  b/2.txt", "a/ 1", "  a/1.txt"), tree(paths, Set.of(), Sort.PATH, true));
        assertEquals(List.of("b/ 1", "  b/2.txt", "a/ 1", "  a/1.txt"), tree(paths, Set.of(), Sort.NAME, true));
        // Largest or newest first says nothing about folder names.
        assertEquals(List.of("a/ 1", "  a/1.txt", "b/ 1", "  b/2.txt"), tree(paths, Set.of(), Sort.SIZE, true));
        assertEquals(List.of("a/ 1", "  a/1.txt", "b/ 1", "  b/2.txt"), tree(paths, Set.of(), Sort.DATE, true));
    }

    @Test
    void aFolderHoldsEveryFileUnderItInListOrder() {
        List<ChangeList.Entry> entries = List.of(entry("a/b/2.txt"), entry("a/1.txt"), entry("a/b/3.txt"));
        Folder folder = (Folder) ChangeTree.flatten(entries, Set.of(), Sort.SIZE, false).get(0);
        assertEquals(List.of("a/b/2.txt", "a/1.txt", "a/b/3.txt"), folder.paths());
    }

    @Test
    void aPathThatIsBothAPlanRowAndAFolderOfOthersShowsAsBoth() {
        // A deleted folder is one plan row; another row below the same path still gets a folder.
        assertEquals(List.of("a/ 2", "  b/ 1", "    a/b/c.txt", "  a/b"),
                tree(List.of("a/b", "a/b/c.txt"), Set.of(), Sort.PATH, false));
    }

    @Test
    void theFolderTickIsAllSomeOrNone() {
        List<String> paths = List.of("a/1", "a/2", "a/3");
        assertEquals(Ticks.ALL, ChangeTree.ticks(paths, Set.of("a/1", "a/2", "a/3", "b/4")));
        assertEquals(Ticks.SOME, ChangeTree.ticks(paths, Set.of("a/2")));
        assertEquals(Ticks.NONE, ChangeTree.ticks(paths, Set.of("b/4")));
        assertEquals(Ticks.NONE, ChangeTree.ticks(List.of(), Set.of()));
    }

    @Test
    void aClosedFolderCountsTheFilesStillWaitingForADecision() {
        List<ChangeList.Entry> entries = List.of(entry("a/1"), entry("a/b/2"), entry("a/3"));
        Folder folder = (Folder) ChangeTree.flatten(entries, Set.of("a"), Sort.PATH, false).get(0);
        assertEquals(2, folder.count(e -> !e.action().relativePath().equals("a/3")));
    }
}
