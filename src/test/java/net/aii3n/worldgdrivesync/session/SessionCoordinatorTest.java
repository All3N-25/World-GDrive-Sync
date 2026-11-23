package net.aii3n.worldgdrivesync.session;

import net.aii3n.worldgdrivesync.settings.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionCoordinatorTest {
	private static final Instant SHARED_AT = Instant.parse("2026-11-24T18:00:00Z");
	private static final Player ALICE = new Player("uuid-alice", "Alice");
	private static final Player BOB = new Player("uuid-bob", "Bob");

	@TempDir
	Path saves;
	@TempDir
	Path sharedsaves;

	InMemoryCloudStore cloud;
	TestClock clock;

	@BeforeEach
	void setUp() {
		cloud = new InMemoryCloudStore();
		clock = new TestClock(SHARED_AT);
	}

	private SessionCoordinator coordinator() {
		return new SessionCoordinator(cloud, clock, sharedsaves, ALICE);
	}

	/** Another player on another computer, sharing the same cloud account. */
	private SessionCoordinator friend(Path theirSharedsaves) {
		return new SessionCoordinator(cloud, clock, theirSharedsaves, BOB);
	}

	// --- Play and quit ---

	@Test
	void playOnAFreeSharedWorldTakesTheLockSoFriendsSeeItHosted(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		clock.advance(Duration.ofMinutes(10));

		PlayResult result = coordinator().play(shared.id(), ProgressListener.NONE);

		assertTrue(result instanceof PlayResult.Ready);
		WorldStatus seenByBob = friend(bobsSharedsaves).listSharedWorlds().getFirst().status();
		assertEquals(new WorldStatus.Hosted("Alice", SHARED_AT.plus(Duration.ofMinutes(10))), seenByBob);
	}

	@Test
	void playIsRefusedNamingTheHostWhileSomeoneElseHoldsTheLock(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		SessionCoordinator bob = friend(bobsSharedsaves);
		clock.advance(Duration.ofMinutes(1));

		PlayResult result = bob.play(shared.id(), ProgressListener.NONE);

		PlayResult.Refused refused = assertInstanceOf(PlayResult.Refused.class, result);
		assertEquals("Alice", refused.heldBy().hostName());
		// Bob got nothing and took nothing: Alice is still the Host.
		assertEquals(Optional.empty(), bob.localCopy(shared.id()));
		assertEquals(new WorldStatus.Hosted("Alice", SHARED_AT), bob.listSharedWorlds().getFirst().status());
	}

	@Test
	void playWithoutALocalCopyDownloadsTheCurrentSaveWithProgress(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		SessionCoordinator bob = friend(bobsSharedsaves);
		List<long[]> downloading = new ArrayList<>();

		PlayResult result = bob.play(shared.id(), (step, done, total) -> {
			if (step == ProgressListener.Step.DOWNLOADING) downloading.add(new long[]{done, total});
		});

		LocalCopy local = assertInstanceOf(PlayResult.Ready.class, result).localCopy();
		assertEquals(bob.localCopy(shared.id()), Optional.of(local));
		assertEquals(1, local.revision());
		assertEquals(bobsSharedsaves, local.folder().getParent());
		assertEquals("level data", Files.readString(local.folder().resolve("level.dat")));
		assertEquals("chunks", Files.readString(local.folder().resolve("region/r.0.0.mca")));
		assertTrue(downloading.size() > 1, "progress reported more than once");
		assertEquals(downloading.getLast()[1], downloading.getLast()[0], "last report covers the whole zip");
	}

	@Test
	void quittingUploadsTheWorldAsTheNextRevisionThenReleasesTheLock(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		LocalCopy local = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		write(local.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house");
		write(local.folder().resolve("session.lock"), "☃");
		clock.advance(Duration.ofHours(2));
		List<long[]> uploading = new ArrayList<>();

		coordinator().quit(shared.id(), (step, done, total) -> {
			if (step == ProgressListener.Step.UPLOADING) uploading.add(new long[]{done, total});
		});

		assertEquals(Map.of(
				"level.dat", "level data",
				"region/r.0.0.mca", "chunks with Alice's house"), cloud.currentSaveEntries(shared.id()));
		SharedWorld seenByBob = friend(bobsSharedsaves).listSharedWorlds().getFirst();
		assertEquals(2, seenByBob.revision());
		assertEquals(SHARED_AT.plus(Duration.ofHours(2)), seenByBob.savedAt());
		assertEquals(WorldStatus.FREE, seenByBob.status());
		assertEquals(2, coordinator().localCopy(shared.id()).orElseThrow().revision());
		assertEquals(uploading.getLast()[1], uploading.getLast()[0], "upload progress reaches the whole zip");
	}

	@Test
	void aFailedFinalUploadKeepsTheLockAndTheRevision(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		cloud.failUploads();

		assertThrows(IOException.class, () -> coordinator().quit(shared.id(), ProgressListener.NONE));

		SharedWorld seenByBob = friend(bobsSharedsaves).listSharedWorlds().getFirst();
		assertEquals(new WorldStatus.Hosted("Alice", SHARED_AT), seenByBob.status());
		assertEquals(1, seenByBob.revision());
		assertEquals(1, coordinator().localCopy(shared.id()).orElseThrow().revision());
	}

	@Test
	void aFailedDownloadReleasesTheLockAgain(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		cloud.failDownloads();
		SessionCoordinator bob = friend(bobsSharedsaves);

		assertThrows(IOException.class, () -> bob.play(shared.id(), ProgressListener.NONE));

		assertEquals(WorldStatus.FREE, bob.listSharedWorlds().getFirst().status());
		assertEquals(Optional.empty(), bob.localCopy(shared.id()));
	}

	@Test
	void backingOutBeforeTheWorldOpensReleasesTheLock(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);

		coordinator().leaveWithoutPlaying(shared.id());

		SharedWorld seenByBob = friend(bobsSharedsaves).listSharedWorlds().getFirst();
		assertEquals(WorldStatus.FREE, seenByBob.status());
		assertEquals(1, seenByBob.revision());
	}

	@Test
	void anOlderLocalCopyIsReplacedByTheNewerRevisionOnPlay(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		SessionCoordinator bob = friend(bobsSharedsaves);
		LocalCopy bobsCopy = ((PlayResult.Ready) bob.play(shared.id(), ProgressListener.NONE)).localCopy();
		write(bobsCopy.folder().resolve("region/r.0.0.mca"), "chunks with Bob's farm");
		bob.quit(shared.id(), ProgressListener.NONE);
		// Alice still has Revision 1; Bob's session made Revision 2.
		LocalCopy alicesCopy = coordinator().localCopy(shared.id()).orElseThrow();
		assertEquals(1, alicesCopy.revision());

		PlayResult result = coordinator().play(shared.id(), ProgressListener.NONE);

		LocalCopy updated = assertInstanceOf(PlayResult.Ready.class, result).localCopy();
		assertEquals(2, updated.revision());
		assertEquals(alicesCopy.folder(), updated.folder());
		assertEquals("chunks with Bob's farm", Files.readString(updated.folder().resolve("region/r.0.0.mca")));
		assertEquals(Optional.of(updated), coordinator().localCopy(shared.id()));
	}

	@Test
	void aLocalCopyAtTheCurrentRevisionOpensWithoutDownloading() throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		LocalCopy before = coordinator().localCopy(shared.id()).orElseThrow();
		// Only on this computer: a download would wipe it.
		write(before.folder().resolve("options-only-here.txt"), "local");
		List<ProgressListener.Step> steps = new ArrayList<>();

		PlayResult result = coordinator().play(shared.id(), (step, done, total) -> steps.add(step));

		assertEquals(new PlayResult.Ready(before), result);
		assertEquals(List.of(), steps);
		assertEquals("local", Files.readString(before.folder().resolve("options-only-here.txt")));
	}

	@Test
	void sharingAWorldUploadsItAsRevision1InItsOwnCloudFolder() throws Exception {
		Path world = singleplayerWorld("My LAN World");

		SharedWorld shared = coordinator().share(world, "My LAN World", ProgressListener.NONE);

		assertEquals(1, cloud.worldCount());
		assertEquals("My LAN World", cloud.worldName(shared.id()));
		assertEquals(1, cloud.metadata(shared.id()).revision());
		assertEquals(Map.of(
				"level.dat", "level data",
				"region/r.0.0.mca", "chunks"), cloud.currentSaveEntries(shared.id()));
	}

	@Test
	void sharingKeepsALocalCopyInSharedsavesThatRemembersRevision1() throws Exception {
		Path world = singleplayerWorld("My LAN World");

		SharedWorld shared = coordinator().share(world, "My LAN World", ProgressListener.NONE);

		LocalCopy local = coordinator().localCopy(shared.id()).orElseThrow();
		assertEquals(1, local.revision());
		assertTrue(local.folder().startsWith(sharedsaves));
		assertEquals("level data", Files.readString(local.folder().resolve("level.dat")));
		assertEquals("chunks", Files.readString(local.folder().resolve("region/r.0.0.mca")));
	}

	@Test
	void aSharedWorldsLocalCopyIsAWorldFolderDirectlyInSharedsavesThatTheGameCanOpen(@TempDir Path friendsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		SessionCoordinator friend = friend(friendsSharedsaves);
		SharedWorld listed = friend.listSharedWorlds().getFirst();

		// The game opens <sharedsaves>/<folder name>, so the Local Copy must sit directly in sharedsaves/.
		LocalCopy local = coordinator().localCopy(listed.id()).orElseThrow();
		assertEquals(sharedsaves, local.folder().getParent());
		assertTrue(Files.isRegularFile(local.folder().resolve("level.dat")));
		// The friend's machine has no Local Copy of it: Play can't open anything there yet.
		assertEquals(Optional.empty(), friend.localCopy(shared.id()));
	}

	@Test
	void aFolderInSharedsavesWithoutARecordIsNotALocalCopy() throws Exception {
		write(sharedsaves.resolve("world-stray/level.dat"), "level data");

		assertEquals(Optional.empty(), coordinator().localCopy("world-stray"));
	}

	@Test
	void aWorldNeverSharedHasNoLocalCopy() {
		assertEquals(Optional.empty(), coordinator().localCopy("world-unknown"));
	}

	@Test
	void everySharedWorldOnTheAccountIsListedAsFree(@TempDir Path friendsSharedsaves) throws Exception {
		SessionCoordinator me = coordinator();
		SessionCoordinator friend = friend(friendsSharedsaves);
		SharedWorld survival = me.share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		clock.advance(Duration.ofHours(1));
		SharedWorld creative = me.share(singleplayerWorld("Creative"), "Creative", ProgressListener.NONE);

		List<SharedWorld> listed = friend.listSharedWorlds();

		assertEquals(List.of(
				new SharedWorld(survival.id(), "Survival", 1, SHARED_AT, WorldStatus.FREE),
				new SharedWorld(creative.id(), "Creative", 1, SHARED_AT.plus(Duration.ofHours(1)), WorldStatus.FREE)),
				listed.stream().sorted(Comparator.comparing(SharedWorld::name).reversed()).toList());
	}

	@Test
	void listingReReadsTheCloudEachTime(@TempDir Path friendsSharedsaves) throws Exception {
		SessionCoordinator friend = friend(friendsSharedsaves);
		assertEquals(List.of(), friend.listSharedWorlds());

		coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);

		assertEquals(List.of("Survival"), friend.listSharedWorlds().stream().map(SharedWorld::name).toList());
	}

	@Test
	void aShareWhoseUploadFailsIsNotListedAndLeavesNoLocalCopy() throws Exception {
		cloud.failUploads();
		SessionCoordinator me = coordinator();

		assertThrows(IOException.class, () -> me.share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE));

		assertEquals(List.of(), me.listSharedWorlds());
		try (var leftovers = Files.list(sharedsaves)) {
			assertEquals(List.of(), leftovers.toList());
		}
	}

	@Test
	void sharingReportsUploadProgressUpToTheWholeZip() throws Exception {
		List<long[]> uploading = new ArrayList<>();

		coordinator().share(singleplayerWorld("Survival"), "Survival", (step, done, total) -> {
			if (step == ProgressListener.Step.UPLOADING) uploading.add(new long[]{done, total});
		});

		assertTrue(uploading.size() > 1, "progress reported more than once");
		long[] last = uploading.getLast();
		assertTrue(last[1] > 0);
		assertEquals(last[1], last[0], "last report covers the whole zip");
	}

	@Test
	void theOpenWorldsSessionLockIsNotShared() throws Exception {
		Path world = singleplayerWorld("Survival");
		write(world.resolve("session.lock"), "☃");

		SharedWorld shared = coordinator().share(world, "Survival", ProgressListener.NONE);

		assertFalse(cloud.currentSaveEntries(shared.id()).containsKey("session.lock"));
		assertFalse(Files.exists(coordinator().localCopy(shared.id()).orElseThrow().folder().resolve("session.lock")));
	}

	// --- Backups ---

	@Test
	void anUploadKeepsThePreviousCurrentSaveAsATimestampedBackup(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);

		hostASession(coordinator(), shared.id(), "chunks with Alice's house");

		SessionCoordinator bob = friend(bobsSharedsaves);
		List<Backup> backups = bob.backups(shared.id());
		assertEquals(1, backups.size());
		Backup backup = backups.getFirst();
		assertEquals(1, backup.revision());
		assertEquals(SHARED_AT, backup.savedAt());
		assertEquals(Optional.empty(), backup.unmerged());
		assertEquals(Map.of("level.dat", "level data", "region/r.0.0.mca", "chunks"), cloud.backupEntries(shared.id(), backup.id()));
		assertEquals(Map.of("level.dat", "level data", "region/r.0.0.mca", "chunks with Alice's house"), cloud.currentSaveEntries(shared.id()));
		assertEquals(2, bob.listSharedWorlds().getFirst().revision());
	}

	@Test
	void onceThereAreMoreThanTheConfiguredCountTheOldestBackupsAreDeleted(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		SessionCoordinator alice = new SessionCoordinator(cloud, clock, sharedsaves, ALICE, () -> Settings.DEFAULTS.withBackupCount(3));

		for (int session = 1; session <= 5; session++) hostASession(alice, shared.id(), "chunks after session " + session);

		List<Backup> backups = friend(bobsSharedsaves).backups(shared.id());
		assertEquals(List.of(5, 4, 3), backups.stream().map(Backup::revision).toList());
		assertEquals(Map.of("level.dat", "level data", "region/r.0.0.mca", "chunks after session 2"),
				cloud.backupEntries(shared.id(), backups.getLast().id()));
		assertEquals(6, cloud.metadata(shared.id()).revision());
	}

	@Test
	void tenBackupsAreKeptByDefault(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);

		for (int session = 1; session <= 12; session++) hostASession(coordinator(), shared.id(), "chunks after session " + session);

		List<Backup> backups = friend(bobsSharedsaves).backups(shared.id());
		assertEquals(10, backups.size());
		assertEquals(12, backups.getFirst().revision());
		assertEquals(3, backups.getLast().revision());
	}

	@Test
	void aFailedUploadMakesNoBackupAndLeavesTheCurrentSave() throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		cloud.failUploads();

		assertThrows(IOException.class, () -> coordinator().quit(shared.id(), ProgressListener.NONE));

		assertEquals(List.of(), coordinator().backups(shared.id()));
		assertEquals(Map.of("level.dat", "level data", "region/r.0.0.mca", "chunks"), cloud.currentSaveEntries(shared.id()));
	}

	@Test
	void anUploadStillSucceedsWhenOldBackupsCannotBeDeleted(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		SessionCoordinator alice = new SessionCoordinator(cloud, clock, sharedsaves, ALICE, () -> Settings.DEFAULTS.withBackupCount(1));
		hostASession(alice, shared.id(), "chunks after session 1");
		cloud.failBackupDeletes();

		hostASession(alice, shared.id(), "chunks after session 2");

		SharedWorld seenByBob = friend(bobsSharedsaves).listSharedWorlds().getFirst();
		assertEquals(3, seenByBob.revision());
		assertEquals(WorldStatus.FREE, seenByBob.status());
		assertEquals(List.of(2, 1), friend(bobsSharedsaves).backups(shared.id()).stream().map(Backup::revision).toList());
	}

	/** Plays a whole session as {@code player}: Play, change the world, an hour later save and quit. */
	private void hostASession(SessionCoordinator player, String worldId, String chunks) throws IOException {
		LocalCopy local = ((PlayResult.Ready) player.play(worldId, ProgressListener.NONE)).localCopy();
		write(local.folder().resolve("region/r.0.0.mca"), chunks);
		clock.advance(Duration.ofHours(1));
		player.quit(worldId, ProgressListener.NONE);
	}

	private Path singleplayerWorld(String folder) throws IOException {
		Path world = saves.resolve(folder);
		write(world.resolve("level.dat"), "level data");
		write(world.resolve("region/r.0.0.mca"), "chunks");
		return world;
	}

	private static void write(Path file, String content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.writeString(file, content, StandardCharsets.UTF_8);
	}
}
