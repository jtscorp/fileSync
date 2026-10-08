package uz.jtscorp.filesync.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProfileStoreTest {

    @TempDir
    Path tempDir;

    @Test
    void savesAndListsMultipleProfiles() throws Exception {
        ProfileStore store = new ProfileStore(tempDir);
        store.save(new SyncProfile("Documents", Path.of("/home/user/Documents"),
                Path.of("/media/hdd/Documents"), List.of("Name *.tmp")));
        store.save(new SyncProfile("Photos", Path.of("/home/user/Photos"),
                Path.of("/media/hdd/Photos"), List.of()));

        List<SyncProfile> profiles = store.listProfiles();

        assertEquals(2, profiles.size());
        assertTrue(profiles.stream().anyMatch(p -> p.name().equals("Documents")));
        assertTrue(profiles.stream().anyMatch(p -> p.name().equals("Photos")));
    }

    @Test
    void deleteRemovesProfileFile() throws Exception {
        ProfileStore store = new ProfileStore(tempDir);
        store.save(new SyncProfile("Documents", Path.of("/a"), Path.of("/b"), List.of()));

        store.delete("Documents");

        assertTrue(store.listProfiles().isEmpty());
    }

    @Test
    void statsRoundTripBesideTheProfileAndAreNotListedAsOne() throws Exception {
        ProfileStore store = new ProfileStore(tempDir);
        store.save(new SyncProfile("Documents", Path.of("/a"), Path.of("/b"), List.of()));
        ProfileStats stats = new ProfileStats(Instant.parse("2026-10-06T22:10:00Z"), 12_480, 19_542_101_197L);

        assertEquals(ProfileStats.NONE, store.stats("Documents"));
        store.saveStats("Documents", stats);
        store.saveStats("Documents", stats.withCounts(5, 100));

        assertEquals(stats.withCounts(5, 100), store.stats("Documents"));
        assertEquals(1, store.listProfiles().size());
        assertTrue(Files.exists(tempDir.resolve("Documents.filesync")));
    }

    @Test
    void statsThatCannotBeParsedReadAsNone() throws Exception {
        ProfileStore store = new ProfileStore(tempDir);
        Files.writeString(tempDir.resolve("Documents.filesync"), "lastSync = kecha\nfiles = ko'p\n");

        assertEquals(ProfileStats.NONE, store.stats("Documents"));
    }

    @Test
    void deletingAProfileDeletesItsStats() throws Exception {
        ProfileStore store = new ProfileStore(tempDir);
        store.save(new SyncProfile("Documents", Path.of("/a"), Path.of("/b"), List.of()));
        store.saveStats("Documents", ProfileStats.NONE.withCounts(1, 1));

        store.delete("Documents");

        assertFalse(Files.exists(tempDir.resolve("Documents.filesync")));
    }

    @Test
    void listProfilesOnMissingDirectoryReturnsEmpty() throws Exception {
        ProfileStore store = new ProfileStore(tempDir.resolve("does-not-exist"));

        assertTrue(store.listProfiles().isEmpty());
    }

    @Test
    void skipsPrfFilesThatAreNotSyncProfiles() throws Exception {
        // ~/.unison belongs to Unison, not to us: it writes its own default.prf the first
        // time it runs without a profile name. Listing must not die on it.
        ProfileStore store = new ProfileStore(tempDir);
        Files.writeString(tempDir.resolve("default.prf"), "\n# Unison preferences file");
        store.save(new SyncProfile("Documents", Path.of("/a"), Path.of("/b"), List.of()));

        assertEquals(List.of("Documents"),
                store.listProfiles().stream().map(SyncProfile::name).toList());
    }
}
