package net.aii3n.worldgdrivesync.session;

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
import java.util.concurrent.atomic.AtomicReference;

import net.aii3n.worldgdrivesync.settings.Settings;

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

	// --- Periodic upload ---

	@Test
	void aPeriodicUploadFallsDueAfterTheIntervalAndBumpsTheRevisionWhileTheHostKeepsTheLock(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		LocalCopy local = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		write(local.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house");

		clock.advance(Duration.ofMinutes(29));
		assertFalse(coordinator().periodicUploadDue(shared.id()), "not before the default 30 minutes");
		clock.advance(Duration.ofMinutes(1));
		assertTrue(coordinator().periodicUploadDue(shared.id()));

		coordinator().periodicUpload(shared.id(), snapshot -> WorldZip.copy(local.folder(), snapshot), ProgressListener.NONE);

		assertEquals("chunks with Alice's house", cloud.currentSaveEntries(shared.id()).get("region/r.0.0.mca"));
		SharedWorld seenByBob = friend(bobsSharedsaves).listSharedWorlds().getFirst();
		assertEquals(2, seenByBob.revision());
		assertEquals(new WorldStatus.Hosted("Alice", SHARED_AT), seenByBob.status());
		assertEquals(2, coordinator().localCopy(shared.id()).orElseThrow().revision());
		assertFalse(coordinator().periodicUploadDue(shared.id()), "the interval starts again");
		clock.advance(Duration.ofMinutes(30));
		assertTrue(coordinator().periodicUploadDue(shared.id()));
	}

	@Test
	void withPeriodicUploadDisabledNoneFallsDueButQuittingStillUploads(@TempDir Path bobsSharedsaves) throws Exception {
		SessionCoordinator alice = new SessionCoordinator(cloud, clock, sharedsaves, ALICE,
				() -> Settings.DEFAULTS.withPeriodicUploadInterval(Duration.ZERO));
		SharedWorld shared = alice.share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		alice.play(shared.id(), ProgressListener.NONE);

		clock.advance(Duration.ofHours(5));
		assertFalse(alice.periodicUploadDue(shared.id()));

		alice.quit(shared.id(), ProgressListener.NONE);
		SharedWorld seenByBob = friend(bobsSharedsaves).listSharedWorlds().getFirst();
		assertEquals(2, seenByBob.revision());
		assertEquals(WorldStatus.FREE, seenByBob.status());
	}

	@Test
	void theIntervalFollowsTheCurrentSetting() throws Exception {
		AtomicReference<Settings> settings = new AtomicReference<>(Settings.DEFAULTS);
		SessionCoordinator alice = new SessionCoordinator(cloud, clock, sharedsaves, ALICE, settings::get);
		SharedWorld shared = alice.share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		alice.play(shared.id(), ProgressListener.NONE);
		clock.advance(Duration.ofMinutes(10));
		assertFalse(alice.periodicUploadDue(shared.id()));

		settings.set(Settings.DEFAULTS.withPeriodicUploadInterval(Duration.ofMinutes(10)));

		assertTrue(alice.periodicUploadDue(shared.id()));
	}

	@Test
	void noPeriodicUploadIsDueForAPlayerWhoIsNotHosting(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		SessionCoordinator bob = friend(bobsSharedsaves);
		clock.advance(Duration.ofHours(1));
		assertFalse(bob.periodicUploadDue(shared.id()), "nobody is hosting");

		coordinator().play(shared.id(), ProgressListener.NONE);
		clock.advance(Duration.ofHours(1));

		assertFalse(bob.periodicUploadDue(shared.id()), "Alice is hosting, not Bob");
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
