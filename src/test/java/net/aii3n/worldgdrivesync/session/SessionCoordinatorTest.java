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

	// --- Heartbeat, Stale Lock, Takeover ---

	@Test
	void aLockWithNoHeartbeatForLongerThanTheStaleTimeoutShowsAsStale(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		SessionCoordinator bob = friend(bobsSharedsaves);

		clock.advance(Duration.ofMinutes(4));
		assertEquals(new WorldStatus.Hosted("Alice", SHARED_AT), bob.listSharedWorlds().getFirst().status());

		// Alice's game crashed: no Heartbeat for over the default 5 minutes.
		clock.advance(Duration.ofMinutes(2));
		assertEquals(new WorldStatus.Stale("Alice", SHARED_AT), bob.listSharedWorlds().getFirst().status());
	}

	@Test
	void theStaleTimeoutComesFromSettings(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		Settings patient = Settings.DEFAULTS.withStaleTimeout(Duration.ofMinutes(15));
		SessionCoordinator bob = new SessionCoordinator(cloud, clock, bobsSharedsaves, BOB, () -> patient);

		clock.advance(Duration.ofMinutes(14));
		assertEquals(new WorldStatus.Hosted("Alice", SHARED_AT), bob.listSharedWorlds().getFirst().status());

		clock.advance(Duration.ofMinutes(2));
		assertEquals(new WorldStatus.Stale("Alice", SHARED_AT), bob.listSharedWorlds().getFirst().status());
	}

	@Test
	void heartbeatsKeepTheLockFreshForAsLongAsTheHostPlays(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		SessionCoordinator bob = friend(bobsSharedsaves);

		for (int minute = 1; minute <= 30; minute++) {
			clock.advance(Duration.ofMinutes(1));
			assertTrue(coordinator().heartbeat(shared.id()), "Alice still holds the Lock");
		}

		assertEquals(new WorldStatus.Hosted("Alice", SHARED_AT), bob.listSharedWorlds().getFirst().status());
		// When the Heartbeats stop, the Lock goes Stale from the last one.
		Instant lastHeartbeat = clock.instant();
		clock.advance(Duration.ofMinutes(6));
		assertEquals(new WorldStatus.Stale("Alice", lastHeartbeat), bob.listSharedWorlds().getFirst().status());
	}

	@Test
	void playOnAStaleWorldOffersTakeoverAndWaitingChangesNothing(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		clock.advance(Duration.ofMinutes(10));
		SessionCoordinator bob = friend(bobsSharedsaves);

		PlayResult result = bob.play(shared.id(), ProgressListener.NONE);

		PlayResult.Stale stale = assertInstanceOf(PlayResult.Stale.class, result);
		assertEquals("Alice", stale.heldBy().hostName());
		assertEquals(SHARED_AT, stale.heldBy().lastHeartbeat());
		// Bob chooses Wait: nothing was taken or downloaded, and Alice can still come back.
		assertEquals(Optional.empty(), bob.localCopy(shared.id()));
		assertEquals(new WorldStatus.Stale("Alice", SHARED_AT), bob.listSharedWorlds().getFirst().status());
		assertTrue(coordinator().heartbeat(shared.id()), "Alice's Lock is untouched");
		assertEquals(new WorldStatus.Hosted("Alice", SHARED_AT), bob.listSharedWorlds().getFirst().status());
	}

	@Test
	void takeoverReplacesTheStaleLockAndStartsFromTheLatestCurrentSave(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		SessionCoordinator bob = friend(bobsSharedsaves);
		bob.play(shared.id(), ProgressListener.NONE);
		bob.quit(shared.id(), ProgressListener.NONE);
		// Alice uploads Revision 3, plays again, and crashes.
		LocalCopy alices = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		write(alices.folder().resolve("region/r.0.0.mca"), "chunks with Alice's tower");
		coordinator().quit(shared.id(), ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		clock.advance(Duration.ofMinutes(10));
		assertInstanceOf(PlayResult.Stale.class, bob.play(shared.id(), ProgressListener.NONE));

		PlayResult result = bob.takeOver(shared.id(), ProgressListener.NONE);

		LocalCopy bobs = assertInstanceOf(PlayResult.Ready.class, result).localCopy();
		assertEquals(3, bobs.revision());
		assertEquals("chunks with Alice's tower", Files.readString(bobs.folder().resolve("region/r.0.0.mca")));
		assertEquals(new WorldStatus.Hosted("Bob", clock.instant()), coordinator().listSharedWorlds().getFirst().status());
		// Alice's game comes back to life: her Lock is gone and her Heartbeat doesn't restore it.
		assertFalse(coordinator().heartbeat(shared.id()));
		assertEquals("Bob", ((WorldStatus.Hosted) coordinator().listSharedWorlds().getFirst().status()).host());
	}

	@Test
	void takeoverIsRefusedIfTheHostCameBackMeanwhile(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		clock.advance(Duration.ofMinutes(10));
		SessionCoordinator bob = friend(bobsSharedsaves);
		assertInstanceOf(PlayResult.Stale.class, bob.play(shared.id(), ProgressListener.NONE));
		coordinator().heartbeat(shared.id());

		PlayResult result = bob.takeOver(shared.id(), ProgressListener.NONE);

		assertEquals("Alice", assertInstanceOf(PlayResult.Refused.class, result).heldBy().hostName());
		assertEquals(Optional.empty(), bob.localCopy(shared.id()));
		assertEquals(new WorldStatus.Hosted("Alice", SHARED_AT), bob.listSharedWorlds().getFirst().status());
	}

	@Test
	void whenTwoPlayersPressPlayTogetherTheEarliestLockWins(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		SessionCoordinator bob = friend(bobsSharedsaves);
		List<PlayResult> bobsResult = new ArrayList<>();
		// Both saw the world Free. Bob's Lock lands just before Alice's does.
		cloud.beforeNextLockWrite(() -> bobsResult.add(bob.play(shared.id(), ProgressListener.NONE)));

		PlayResult alicesResult = coordinator().play(shared.id(), ProgressListener.NONE);

		assertInstanceOf(PlayResult.Ready.class, bobsResult.getFirst());
		assertEquals("Bob", assertInstanceOf(PlayResult.Refused.class, alicesResult).heldBy().hostName());
		assertEquals(List.of("Bob"), cloud.lockHolders(shared.id()));
		assertEquals("Bob", ((WorldStatus.Hosted) coordinator().listSharedWorlds().getFirst().status()).host());
	}

	@Test
	void whenTwoPlayersTakeOverTogetherTheEarliestLockWins(@TempDir Path bobsSharedsaves, @TempDir Path carolsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		clock.advance(Duration.ofMinutes(10));
		SessionCoordinator bob = friend(bobsSharedsaves);
		SessionCoordinator carol = new SessionCoordinator(cloud, clock, carolsSharedsaves, new Player("uuid-carol", "Carol"));
		List<PlayResult> carolsResult = new ArrayList<>();
		cloud.beforeNextLockWrite(() -> carolsResult.add(carol.takeOver(shared.id(), ProgressListener.NONE)));

		PlayResult bobsResult = bob.takeOver(shared.id(), ProgressListener.NONE);

		assertInstanceOf(PlayResult.Ready.class, carolsResult.getFirst());
		assertEquals("Carol", assertInstanceOf(PlayResult.Refused.class, bobsResult).heldBy().hostName());
		assertEquals(List.of("Carol"), cloud.lockHolders(shared.id()));
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
