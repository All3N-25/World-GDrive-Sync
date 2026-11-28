package net.aii3n.worldgdrivesync.session;

import net.aii3n.worldgdrivesync.io.AtomicFile;
import net.aii3n.worldgdrivesync.settings.Settings;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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

	// --- Crash recovery ---

	@Test
	void afterACrashPlayWhileStillHoldingTheLockResumesFromTheLocalCopyWithoutDownloading(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		LocalCopy local = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		write(local.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house");
		clock.advance(Duration.ofMinutes(30));
		coordinator().heartbeat(shared.id());
		coordinator().periodicUpload(shared.id(), folder -> WorldZip.copyForUpload(local.folder(), folder), ProgressListener.NONE);
		write(local.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house and garden");
		// Alice's game crashes and she relaunches it a minute later, before the Lock goes Stale.
		clock.advance(Duration.ofMinutes(1));
		List<ProgressListener.Step> steps = new ArrayList<>();

		PlayResult result = coordinator().play(shared.id(), (step, done, total) -> steps.add(step));

		LocalCopy resumed = assertInstanceOf(PlayResult.Ready.class, result).localCopy();
		assertEquals(List.of(), steps, "nothing downloaded");
		assertEquals("chunks with Alice's house and garden", Files.readString(resumed.folder().resolve("region/r.0.0.mca")));

		clock.advance(Duration.ofMinutes(20));
		coordinator().quit(shared.id(), ProgressListener.NONE);

		assertEquals("chunks with Alice's house and garden", cloud.currentSaveEntries(shared.id()).get("region/r.0.0.mca"));
		SharedWorld seenByBob = friend(bobsSharedsaves).listSharedWorlds().getFirst();
		assertEquals(3, seenByBob.revision());
		assertEquals(WorldStatus.FREE, seenByBob.status());
		assertEquals(List.of(), unmergedBackups(shared.id()));
	}

	@Test
	void aSessionClosedWithoutItsFinalUploadIsUploadedByTheRetryOnLaunch(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		LocalCopy local = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		write(local.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house");
		clock.advance(Duration.ofMinutes(30));
		coordinator().heartbeat(shared.id());
		coordinator().periodicUpload(shared.id(), folder -> WorldZip.copyForUpload(local.folder(), folder), ProgressListener.NONE);
		write(local.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house and garden");
		// Alice closes the game window instead of Save and Quit, and starts it again later.
		clock.advance(Duration.ofMinutes(3));
		SessionCoordinator afterRestart = coordinator();

		assertEquals(List.of(shared.id()), afterRestart.pendingUploads());
		RetryResult result = afterRestart.retryPendingUpload(shared.id(), ProgressListener.NONE);

		assertEquals(3, assertInstanceOf(RetryResult.Uploaded.class, result).current().revision());
		assertEquals("chunks with Alice's house and garden", cloud.currentSaveEntries(shared.id()).get("region/r.0.0.mca"));
		assertEquals(WorldStatus.FREE, friend(bobsSharedsaves).listSharedWorlds().getFirst().status());
		assertEquals(List.of(), afterRestart.pendingUploads());
		assertEquals(List.of(), unmergedBackups(shared.id()));
	}

	@Test
	void aSessionClosedWithoutItsFinalUploadBecomesAnUnmergedBackupOnLaunchIfSomeoneUploadedSince(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		LocalCopy local = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		write(local.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house");
		// Alice closes the game window; her Lock goes Stale and Bob Takes Over and uploads.
		clock.advance(Duration.ofMinutes(10));
		SessionCoordinator bob = friend(bobsSharedsaves);
		LocalCopy bobs = ((PlayResult.Ready) bob.takeOver(shared.id(), ProgressListener.NONE)).localCopy();
		write(bobs.folder().resolve("region/r.0.0.mca"), "chunks with Bob's farm");
		bob.quit(shared.id(), ProgressListener.NONE);

		RetryResult result = coordinator().retryPendingUpload(shared.id(), ProgressListener.NONE);

		Backup unmerged = assertInstanceOf(RetryResult.KeptAsUnmergedBackup.class, result).backup();
		assertEquals(new CrashedSession(ALICE.id(), "Alice", SHARED_AT), unmerged.unmerged().orElseThrow());
		assertEquals("chunks with Alice's house", cloud.backupEntries(shared.id(), unmerged.id()).get("region/r.0.0.mca"));
		assertEquals("chunks with Bob's farm", cloud.currentSaveEntries(shared.id()).get("region/r.0.0.mca"));
		assertEquals(List.of(), coordinator().pendingUploads());
	}

	@Test
	void aCrashedHostReturningAfterATakeoverGetsAnUnmergedBackupAndTheNewerRevisionStaysIntact(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		Instant alicesSessionStart = clock.instant();
		LocalCopy alices = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		write(alices.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house");
		clock.advance(Duration.ofMinutes(30));
		coordinator().heartbeat(shared.id());
		// Revision 2 is in the cloud; then Alice builds more and her game crashes.
		coordinator().periodicUpload(shared.id(), folder -> WorldZip.copyForUpload(alices.folder(), folder), ProgressListener.NONE);
		write(alices.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house and tower");
		clock.advance(Duration.ofMinutes(10));
		SessionCoordinator bob = friend(bobsSharedsaves);
		LocalCopy bobs = ((PlayResult.Ready) bob.takeOver(shared.id(), ProgressListener.NONE)).localCopy();
		write(bobs.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house and Bob's farm");
		clock.advance(Duration.ofHours(1));
		bob.quit(shared.id(), ProgressListener.NONE);
		clock.advance(Duration.ofHours(1));
		Instant alicesReturn = clock.instant();

		PlayResult result = coordinator().play(shared.id(), ProgressListener.NONE);

		// Bob's Revision 3 is untouched as the Current Save.
		assertEquals(3, cloud.metadata(shared.id()).revision());
		assertEquals("chunks with Alice's house and Bob's farm", cloud.currentSaveEntries(shared.id()).get("region/r.0.0.mca"));
		// Alice's crashed session is kept as an Unmerged Backup with her name.
		List<Backup> unmerged = unmergedBackups(shared.id());
		assertEquals(1, unmerged.size());
		assertEquals(new CrashedSession(ALICE.id(), "Alice", alicesSessionStart), unmerged.getFirst().unmerged().orElseThrow());
		assertEquals(2, unmerged.getFirst().revision());
		assertEquals(alicesReturn, unmerged.getFirst().savedAt());
		assertEquals("chunks with Alice's house and tower", cloud.backupEntries(shared.id(), unmerged.getFirst().id()).get("region/r.0.0.mca"));
		// Her Local Copy is now Bob's newer Revision, and she is hosting it.
		LocalCopy updated = assertInstanceOf(PlayResult.Ready.class, result).localCopy();
		assertEquals(3, updated.revision());
		assertEquals("chunks with Alice's house and Bob's farm", Files.readString(updated.folder().resolve("region/r.0.0.mca")));
		assertEquals(new WorldStatus.Hosted("Alice", alicesReturn), bob.listSharedWorlds().getFirst().status());
	}

	@Test
	void ifTheUnmergedBackupOrTheDownloadFailsNothingIsLostAndPlayCanBeTriedAgain(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		leaveAPendingUploadThenLetBobTakeOverAndUpload(shared.id(), bobsSharedsaves);

		// Alice's connection drops while uploading the Unmerged Backup.
		cloud.failUploads();
		assertThrows(IOException.class, () -> coordinator().play(shared.id(), ProgressListener.NONE));
		assertEquals(List.of(), unmergedBackups(shared.id()));
		assertEquals(WorldStatus.FREE, coordinator().listSharedWorlds().getFirst().status(), "the Lock was released");
		assertEquals(List.of(shared.id()), coordinator().pendingUploads());
		Path alicesFolder = coordinator().localCopy(shared.id()).orElseThrow().folder();
		assertEquals("chunks with Alice's house", Files.readString(alicesFolder.resolve("region/r.0.0.mca")));

		// Next time the Unmerged Backup goes up, but the download fails.
		cloud.uploadsWork();
		cloud.failDownloads();
		assertThrows(IOException.class, () -> coordinator().play(shared.id(), ProgressListener.NONE));
		assertEquals(1, unmergedBackups(shared.id()).size());

		// Then Play just downloads, without a second Unmerged Backup.
		cloud.downloadsWork();
		PlayResult.Ready ready = assertInstanceOf(PlayResult.Ready.class, coordinator().play(shared.id(), ProgressListener.NONE));
		assertEquals(Optional.empty(), ready.unmergedBackup());
		assertEquals(1, unmergedBackups(shared.id()).size());
		assertEquals("chunks with Bob's farm", Files.readString(ready.localCopy().folder().resolve("region/r.0.0.mca")));
		assertEquals(2, cloud.metadata(shared.id()).revision());
	}

	@Test
	void backingOutOfAPlayLeavesNoUnsavedProgressBehind(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		coordinator().leaveWithoutPlaying(shared.id());
		hostASession(friend(bobsSharedsaves), shared.id(), "chunks with Bob's farm");

		PlayResult.Ready ready = assertInstanceOf(PlayResult.Ready.class, coordinator().play(shared.id(), ProgressListener.NONE));

		assertEquals(Optional.empty(), ready.unmergedBackup());
		assertEquals(List.of(), unmergedBackups(shared.id()));
		assertEquals(2, ready.localCopy().revision());
	}

	@Test
	void backingOutOfAResumedSessionStillKeepsTheCrashedProgress(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		LocalCopy alices = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		write(alices.folder().resolve("region/r.0.0.mca"), "chunks with Alice's tower");
		// Crash. Alice relaunches, presses Play, then backs out before the world opens.
		clock.advance(Duration.ofMinutes(2));
		coordinator().play(shared.id(), ProgressListener.NONE);
		coordinator().leaveWithoutPlaying(shared.id());

		// She keeps the Lock: her tower is a Pending Upload, and Bob can't start over it.
		assertEquals(List.of(shared.id()), coordinator().pendingUploads());
		assertTrue(coordinator().localCopy(shared.id()).orElseThrow().pendingUpload());
		assertEquals("Alice", assertInstanceOf(PlayResult.Refused.class,
				friend(bobsSharedsaves).play(shared.id(), ProgressListener.NONE)).heldBy().hostName());

		coordinator().retryPendingUpload(shared.id(), ProgressListener.NONE);

		assertEquals(2, cloud.metadata(shared.id()).revision());
		assertEquals("chunks with Alice's tower", cloud.currentSaveEntries(shared.id()).get("region/r.0.0.mca"));
		assertEquals(WorldStatus.FREE, friend(bobsSharedsaves).listSharedWorlds().getFirst().status());
		assertEquals(List.of(), coordinator().pendingUploads());
	}

	@Test
	void aResumedSessionWhosePlayFailsKeepsTheLockAsAPendingUpload(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		LocalCopy alices = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		write(alices.folder().resolve("region/r.0.0.mca"), "chunks with Alice's tower");
		// Crash. Alice relaunches and presses Play, but the cloud stops answering partway.
		clock.advance(Duration.ofMinutes(2));
		cloud.failMetadataReads();

		assertThrows(IOException.class, () -> coordinator().play(shared.id(), ProgressListener.NONE));

		cloud.metadataReadsWork();
		assertEquals(List.of("Alice"), cloud.lockHolders(shared.id()));
		assertEquals(List.of(shared.id()), coordinator().pendingUploads());
		assertEquals("Alice", assertInstanceOf(PlayResult.Refused.class,
				friend(bobsSharedsaves).play(shared.id(), ProgressListener.NONE)).heldBy().hostName());
	}

	@Test
	void aResumedSessionWhoseLockCheckFailsKeepsTheLock(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		LocalCopy alices = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		write(alices.folder().resolve("region/r.0.0.mca"), "chunks with Alice's tower");
		// Crash. Alice relaunches and presses Play; the cloud stops answering right after her Lock is rewritten.
		clock.advance(Duration.ofMinutes(2));
		cloud.beforeNext(InMemoryCloudStore.Call.LOCK_WRITE, cloud::failLockReads);

		assertThrows(IOException.class, () -> coordinator().play(shared.id(), ProgressListener.NONE));

		cloud.lockReadsWork();
		assertEquals(List.of("Alice"), cloud.lockHolders(shared.id()));
		assertEquals(List.of(shared.id()), coordinator().pendingUploads());
		assertEquals("Alice", assertInstanceOf(PlayResult.Refused.class,
				friend(bobsSharedsaves).play(shared.id(), ProgressListener.NONE)).heldBy().hostName());
	}

	@Test
	void unmergedBackupsAreNeverDeletedByRotation(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		leaveAPendingUploadThenLetBobTakeOverAndUpload(shared.id(), bobsSharedsaves);
		SessionCoordinator alice = new SessionCoordinator(cloud, clock, sharedsaves, ALICE, () -> Settings.DEFAULTS.withBackupCount(3));
		Backup unmerged = ((PlayResult.Ready) alice.play(shared.id(), ProgressListener.NONE)).unmergedBackup().orElseThrow();
		alice.quit(shared.id(), ProgressListener.NONE);

		for (int session = 1; session <= 5; session++) hostASession(alice, shared.id(), "chunks after session " + session);

		List<Backup> backups = alice.backups(shared.id());
		assertEquals(List.of(unmerged), unmergedBackups(shared.id()));
		assertEquals(List.of(7, 6, 5), backups.stream().filter(backup -> backup.unmerged().isEmpty()).map(Backup::revision).toList());
	}

	@Test
	void aRetryAfterTheNewHostGaveUpWithoutUploadingTakesTheLockAndUploads(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		LocalCopy alices = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		write(alices.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house");
		cloud.failUploads();
		assertThrows(IOException.class, () -> coordinator().quit(shared.id(), ProgressListener.NONE));
		cloud.uploadsWork();
		clock.advance(Duration.ofMinutes(10));
		SessionCoordinator bob = friend(bobsSharedsaves);
		bob.takeOver(shared.id(), ProgressListener.NONE);
		bob.leaveWithoutPlaying(shared.id());

		RetryResult result = coordinator().retryPendingUpload(shared.id(), ProgressListener.NONE);

		assertEquals(2, assertInstanceOf(RetryResult.Uploaded.class, result).current().revision());
		assertEquals("chunks with Alice's house", cloud.currentSaveEntries(shared.id()).get("region/r.0.0.mca"));
		assertEquals(WorldStatus.FREE, bob.listSharedWorlds().getFirst().status());
		assertEquals(List.of(), coordinator().pendingUploads());
		assertEquals(List.of(), unmergedBackups(shared.id()));
	}

	@Test
	void aPendingUploadLeftBehindBeforeATakeoverBecomesAnUnmergedBackupOnPlay(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		leaveAPendingUploadThenLetBobTakeOverAndUpload(shared.id(), bobsSharedsaves);

		PlayResult result = coordinator().play(shared.id(), ProgressListener.NONE);

		PlayResult.Ready ready = assertInstanceOf(PlayResult.Ready.class, result);
		Backup unmerged = ready.unmergedBackup().orElseThrow();
		assertEquals(List.of(unmerged), unmergedBackups(shared.id()));
		assertEquals(new CrashedSession(ALICE.id(), "Alice", SHARED_AT), unmerged.unmerged().orElseThrow());
		assertEquals("chunks with Alice's house", cloud.backupEntries(shared.id(), unmerged.id()).get("region/r.0.0.mca"));
		assertEquals(2, cloud.metadata(shared.id()).revision());
		assertEquals("chunks with Bob's farm", cloud.currentSaveEntries(shared.id()).get("region/r.0.0.mca"));
		assertEquals(new LocalCopy(ready.localCopy().folder(), 2, false), ready.localCopy());
		assertEquals("chunks with Bob's farm", Files.readString(ready.localCopy().folder().resolve("region/r.0.0.mca")));
		assertFalse(coordinator().localCopy(shared.id()).orElseThrow().pendingUpload());
	}

	@Test
	void theRetryOnLaunchMakesAnUnmergedBackupWhenTheCloudHasMovedOn(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		leaveAPendingUploadThenLetBobTakeOverAndUpload(shared.id(), bobsSharedsaves);

		RetryResult result = coordinator().retryPendingUpload(shared.id(), ProgressListener.NONE);

		Backup unmerged = assertInstanceOf(RetryResult.KeptAsUnmergedBackup.class, result).backup();
		assertEquals(List.of(unmerged), unmergedBackups(shared.id()));
		assertEquals("Alice", unmerged.unmerged().orElseThrow().hostName());
		assertEquals("chunks with Alice's house", cloud.backupEntries(shared.id(), unmerged.id()).get("region/r.0.0.mca"));
		assertEquals(2, cloud.metadata(shared.id()).revision());
		assertEquals("chunks with Bob's farm", cloud.currentSaveEntries(shared.id()).get("region/r.0.0.mca"));
		LocalCopy local = coordinator().localCopy(shared.id()).orElseThrow();
		assertEquals(2, local.revision());
		assertEquals("chunks with Bob's farm", Files.readString(local.folder().resolve("region/r.0.0.mca")));
		assertEquals(List.of(), coordinator().pendingUploads());
		assertEquals(WorldStatus.FREE, coordinator().listSharedWorlds().getFirst().status(), "the retry didn't take the Lock");
	}

	@Test
	void aHostWhoLostTheLockToATakeoverNeverUploadsOverTheNewHost(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		LocalCopy alices = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		write(alices.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house");
		// Alice's game hangs long enough for her Lock to go Stale, and Bob Takes Over.
		clock.advance(Duration.ofMinutes(10));
		SessionCoordinator bob = friend(bobsSharedsaves);
		LocalCopy bobs = ((PlayResult.Ready) bob.takeOver(shared.id(), ProgressListener.NONE)).localCopy();
		write(bobs.folder().resolve("region/r.0.0.mca"), "chunks with Bob's farm");
		clock.advance(Duration.ofMinutes(30));
		bob.heartbeat(shared.id());
		bob.periodicUpload(shared.id(), folder -> WorldZip.copyForUpload(bobs.folder(), folder), ProgressListener.NONE);

		// Alice's game wakes up and tries to upload, periodically and then on save and quit.
		assertThrows(IOException.class, () -> coordinator().periodicUpload(shared.id(),
				folder -> WorldZip.copyForUpload(alices.folder(), folder), ProgressListener.NONE));
		assertThrows(IOException.class, () -> coordinator().quit(shared.id(), ProgressListener.NONE));

		assertEquals(2, cloud.metadata(shared.id()).revision());
		assertEquals("chunks with Bob's farm", cloud.currentSaveEntries(shared.id()).get("region/r.0.0.mca"));
		assertEquals(List.of("Bob"), cloud.lockHolders(shared.id()));
		assertEquals(List.of(shared.id()), coordinator().pendingUploads(), "Alice's progress is kept on her computer");

		// Once Bob has quit, Alice's next Play keeps her session as an Unmerged Backup.
		bob.quit(shared.id(), ProgressListener.NONE);
		PlayResult.Ready ready = assertInstanceOf(PlayResult.Ready.class, coordinator().play(shared.id(), ProgressListener.NONE));
		assertEquals("chunks with Alice's house", cloud.backupEntries(shared.id(), ready.unmergedBackup().orElseThrow().id()).get("region/r.0.0.mca"));
		assertEquals(3, ready.localCopy().revision());
		assertEquals("chunks with Bob's farm", Files.readString(ready.localCopy().folder().resolve("region/r.0.0.mca")));
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
	void heartbeatsKeepTheLockFreshThroughALongDownloadAndFinalUpload(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		SessionCoordinator bob = friend(bobsSharedsaves);
		// The game's Heartbeat thread beats once a minute while Bob's slow download and upload run.
		cloud.beforeNext(InMemoryCloudStore.Call.DOWNLOAD, () -> beatEveryMinuteFor(bob, Duration.ofMinutes(8)));
		LocalCopy bobs = ((PlayResult.Ready) bob.play(shared.id(), ProgressListener.NONE)).localCopy();
		assertEquals(WorldStatus.Hosted.class, coordinator().listSharedWorlds().getFirst().status().getClass(), "not Stale after the download");
		write(bobs.folder().resolve("region/r.0.0.mca"), "chunks with Bob's farm");
		List<WorldStatus> seenDuringUpload = new ArrayList<>();
		cloud.beforeNext(InMemoryCloudStore.Call.REPLACE, () -> {
			beatEveryMinuteFor(bob, Duration.ofMinutes(8));
			seenDuringUpload.add(coordinator().listSharedWorlds().getFirst().status());
		});

		bob.quit(shared.id(), ProgressListener.NONE);

		assertEquals(WorldStatus.Hosted.class, seenDuringUpload.getFirst().getClass(), "not Stale during the final upload");
		assertEquals(List.of(), bob.heartbeatHeldLocks(), "no Lock is held after quitting");
		assertEquals(List.of(), cloud.lockHolders(shared.id()));
	}

	@Test
	void heartbeatsReportALockLostToATakeoverOnce(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		SessionCoordinator alice = coordinator();
		alice.play(shared.id(), ProgressListener.NONE);
		assertEquals(List.of(), alice.heartbeatHeldLocks());
		clock.advance(Duration.ofMinutes(10));
		friend(bobsSharedsaves).takeOver(shared.id(), ProgressListener.NONE);

		assertEquals(List.of(shared.id()), alice.heartbeatHeldLocks());

		assertEquals(List.of(), alice.heartbeatHeldLocks(), "a lost Lock is reported once, then forgotten");
		assertEquals(List.of("Bob"), cloud.lockHolders(shared.id()));
	}

	/** What the game's Heartbeat thread does over {@code time}: a Heartbeat for every held Lock each minute. */
	private void beatEveryMinuteFor(SessionCoordinator player, Duration time) throws IOException {
		for (long minute = 0; minute < time.toMinutes(); minute++) {
			clock.advance(Duration.ofMinutes(1));
			player.heartbeatHeldLocks();
		}
	}

	@Test
	void aHeartbeatNeverPutsBackALockTakenOverWhileItWasBeingSent(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		clock.advance(Duration.ofMinutes(10));
		SessionCoordinator bob = friend(bobsSharedsaves);
		// Alice's game wakes up and sends a Heartbeat; Bob's Takeover lands between its read and its write.
		cloud.beforeNext(InMemoryCloudStore.Call.LOCK_WRITE, () -> bob.takeOver(shared.id(), ProgressListener.NONE));

		assertFalse(coordinator().heartbeat(shared.id()));

		assertEquals(List.of("Bob"), cloud.lockHolders(shared.id()));
	}

	@Test
	void aHeartbeatReportsTheLockLostWhenSomeoneElsesLockIsEarlier() throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		// Somehow Bob's Lock now counts and Alice's is a later one, still lying around.
		cloud.deleteLock(shared.id(), ALICE.id());
		cloud.writeLock(shared.id(), Lock.takenBy(BOB, clock.instant()));
		cloud.writeLock(shared.id(), Lock.takenBy(ALICE, clock.instant()));

		assertFalse(coordinator().heartbeat(shared.id()));

		assertEquals(new WorldStatus.Hosted("Bob", SHARED_AT), coordinator().listSharedWorlds().getFirst().status());
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
	void takeoverReplacesTheStaleLockAndStartsFromTheCurrentSave(@TempDir Path bobsSharedsaves) throws Exception {
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
		cloud.beforeNext(InMemoryCloudStore.Call.LOCK_WRITE, () -> bobsResult.add(bob.play(shared.id(), ProgressListener.NONE)));

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
		cloud.beforeNext(InMemoryCloudStore.Call.LOCK_WRITE, () -> carolsResult.add(carol.takeOver(shared.id(), ProgressListener.NONE)));

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
	void aLockThatCouldntBeReleasedAfterQuitIsReleasedByTheNextHeartbeat(@TempDir Path bobsSharedsaves) throws Exception {
		SessionCoordinator alice = coordinator();
		SharedWorld shared = alice.share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		LocalCopy local = ((PlayResult.Ready) alice.play(shared.id(), ProgressListener.NONE)).localCopy();
		write(local.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house");
		// The upload goes through, but the connection drops before the Lock can be deleted.
		cloud.failLockDeletes();

		WorldMetadata uploaded = alice.quit(shared.id(), ProgressListener.NONE);

		assertEquals(2, uploaded.revision());
		assertEquals(List.of(), alice.pendingUploads());
		assertEquals(List.of("Alice"), cloud.lockHolders(shared.id()));

		cloud.lockDeletesWork();
		clock.advance(Duration.ofMinutes(1));

		assertEquals(List.of(), alice.heartbeatHeldLocks(), "a Lock let go is not reported as lost");
		assertEquals(List.of(), cloud.lockHolders(shared.id()));
		assertEquals(WorldStatus.FREE, friend(bobsSharedsaves).listSharedWorlds().getFirst().status());
	}

	@Test
	void playingAgainBeforeALeftoverLockIsReleasedKeepsTheNewLock() throws Exception {
		SessionCoordinator alice = coordinator();
		SharedWorld shared = alice.share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		alice.play(shared.id(), ProgressListener.NONE);
		cloud.failLockDeletes();
		alice.quit(shared.id(), ProgressListener.NONE);
		cloud.lockDeletesWork();
		clock.advance(Duration.ofMinutes(5));

		assertInstanceOf(PlayResult.Ready.class, alice.play(shared.id(), ProgressListener.NONE));
		clock.advance(Duration.ofMinutes(1));

		assertEquals(List.of(), alice.heartbeatHeldLocks());
		assertEquals(List.of("Alice"), cloud.lockHolders(shared.id()));
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

	// --- Failed uploads and Pending Upload ---

	@Test
	void aFailedFinalUploadLeavesAPendingUploadThatSurvivesARestart(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		cloud.failUploads();

		assertThrows(IOException.class, () -> coordinator().quit(shared.id(), ProgressListener.NONE));

		// A fresh coordinator over the same folder is the game after a restart.
		SessionCoordinator afterRestart = coordinator();
		assertTrue(afterRestart.localCopy(shared.id()).orElseThrow().pendingUpload());
		assertTrue(afterRestart.listSharedWorlds().getFirst().pendingUpload());
		assertEquals(List.of(shared.id()), afterRestart.pendingUploads());
		// Only Alice's computer has the Pending Upload; Bob just sees her hosting.
		SharedWorld seenByBob = friend(bobsSharedsaves).listSharedWorlds().getFirst();
		assertFalse(seenByBob.pendingUpload());
		assertEquals(new WorldStatus.Hosted("Alice", SHARED_AT), seenByBob.status());
	}

	@Test
	void retryingAPendingUploadUploadsTheNextRevisionReleasesTheLockAndClearsIt(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		LocalCopy local = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		write(local.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house");
		cloud.failUploads();
		assertThrows(IOException.class, () -> coordinator().quit(shared.id(), ProgressListener.NONE));
		cloud.uploadsWork();
		clock.advance(Duration.ofHours(1));
		List<long[]> uploading = new ArrayList<>();

		coordinator().retryPendingUpload(shared.id(), (step, done, total) -> {
			if (step == ProgressListener.Step.UPLOADING) uploading.add(new long[]{done, total});
		});

		assertEquals("chunks with Alice's house", cloud.currentSaveEntries(shared.id()).get("region/r.0.0.mca"));
		SharedWorld seenByBob = friend(bobsSharedsaves).listSharedWorlds().getFirst();
		assertEquals(2, seenByBob.revision());
		assertEquals(SHARED_AT.plus(Duration.ofHours(1)), seenByBob.savedAt());
		assertEquals(WorldStatus.FREE, seenByBob.status());
		assertEquals(new LocalCopy(local.folder(), 2, false), coordinator().localCopy(shared.id()).orElseThrow());
		assertFalse(coordinator().listSharedWorlds().getFirst().pendingUpload());
		assertEquals(List.of(), coordinator().pendingUploads());
		assertEquals(uploading.getLast()[1], uploading.getLast()[0], "upload progress reaches the whole zip");
	}

	@Test
	void aRetryThatFailsAgainKeepsTheLockAndThePendingUpload(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		cloud.failUploads();
		assertThrows(IOException.class, () -> coordinator().quit(shared.id(), ProgressListener.NONE));

		assertThrows(IOException.class, () -> coordinator().retryPendingUpload(shared.id(), ProgressListener.NONE));

		assertTrue(coordinator().localCopy(shared.id()).orElseThrow().pendingUpload());
		SharedWorld seenByBob = friend(bobsSharedsaves).listSharedWorlds().getFirst();
		assertEquals(new WorldStatus.Hosted("Alice", SHARED_AT), seenByBob.status());
		assertEquals(1, seenByBob.revision());
	}

	@Test
	void aRetryWhileSomeoneElseHostsChangesNothing(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		coordinator().play(shared.id(), ProgressListener.NONE);
		cloud.failUploads();
		assertThrows(IOException.class, () -> coordinator().quit(shared.id(), ProgressListener.NONE));
		cloud.uploadsWork();
		// Alice's Lock goes away (as after a Takeover) and Bob is hosting right now.
		cloud.deleteLock(shared.id(), ALICE.id());
		clock.advance(Duration.ofMinutes(10));
		SessionCoordinator bob = friend(bobsSharedsaves);
		bob.play(shared.id(), ProgressListener.NONE);

		assertThrows(IOException.class, () -> coordinator().retryPendingUpload(shared.id(), ProgressListener.NONE));

		assertEquals(1, cloud.metadata(shared.id()).revision());
		assertEquals(new WorldStatus.Hosted("Bob", SHARED_AT.plus(Duration.ofMinutes(10))), bob.listSharedWorlds().getFirst().status());
		assertTrue(coordinator().localCopy(shared.id()).orElseThrow().pendingUpload());
	}

	@Test
	void anUploadWhoseAnswerIsLostStillCarriesItsRevisionSoNobodySkipsTheDownload(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		SessionCoordinator bob = friend(bobsSharedsaves);
		bob.play(shared.id(), ProgressListener.NONE);
		bob.leaveWithoutPlaying(shared.id()); // Bob now has a Local Copy of Revision 1.
		LocalCopy alices = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		write(alices.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house");
		// Alice's new Current Save is stored, then her connection drops before she hears back.
		cloud.dropConnectionAfterNextReplace();
		assertThrows(IOException.class, () -> coordinator().quit(shared.id(), ProgressListener.NONE));
		clock.advance(Duration.ofMinutes(10));

		LocalCopy bobs = assertInstanceOf(PlayResult.Ready.class, bob.takeOver(shared.id(), ProgressListener.NONE)).localCopy();

		assertEquals(2, bobs.revision());
		assertEquals("chunks with Alice's house", Files.readString(bobs.folder().resolve("region/r.0.0.mca")));
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
	void ifTheNewRevisionCannotBeMovedInTheOldLocalCopyIsPutBack(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		hostASession(friend(bobsSharedsaves), shared.id(), "chunks with Bob's farm");
		LocalCopy before = coordinator().localCopy(shared.id()).orElseThrow();
		AtomicBoolean failed = new AtomicBoolean();
		SessionCoordinator alice = coordinatorMovingWith((from, to) -> {
			// Only the first move into place, which is the new copy's; putting the old one back works.
			if (to.equals(before.folder()) && failed.compareAndSet(false, true)) throw new AccessDeniedException(to.toString());
			Files.move(from, to);
		});

		assertThrows(AccessDeniedException.class, () -> alice.play(shared.id(), ProgressListener.NONE));

		assertEquals(Optional.of(before), alice.localCopy(shared.id()));
		assertEquals("chunks", Files.readString(before.folder().resolve("region/r.0.0.mca")));
		assertEquals(WorldStatus.FREE, alice.listSharedWorlds().getFirst().status());
		try (Stream<Path> left = Files.list(sharedsaves)) {
			assertEquals(List.of(before.folder()), left.toList(), "no leftovers");
		}
	}

	@Test
	void ifTheOldLocalCopyCannotBePutBackEitherItIsKeptOnDisk(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		hostASession(friend(bobsSharedsaves), shared.id(), "chunks with Bob's farm");
		Path folder = coordinator().localCopy(shared.id()).orElseThrow().folder();
		SessionCoordinator alice = coordinatorMovingWith((from, to) -> {
			if (to.equals(folder)) throw new AccessDeniedException(to.toString());
			Files.move(from, to);
		});

		assertThrows(AccessDeniedException.class, () -> alice.play(shared.id(), ProgressListener.NONE));

		assertEquals("chunks", Files.readString(chunksKeptInSharedsaves()));
	}

	@Test
	void ifSomethingElseTakesTheLocalCopysPlaceDuringAFailedSwapTheOldOneIsStillKept(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		hostASession(friend(bobsSharedsaves), shared.id(), "chunks with Bob's farm");
		Path folder = coordinator().localCopy(shared.id()).orElseThrow().folder();
		AtomicBoolean failed = new AtomicBoolean();
		SessionCoordinator alice = coordinatorMovingWith((from, to) -> {
			if (to.equals(folder) && failed.compareAndSet(false, true)) {
				write(folder.resolve("stray.txt"), "written by another program");
				throw new AccessDeniedException(to.toString());
			}
			Files.move(from, to);
		});

		assertThrows(AccessDeniedException.class, () -> alice.play(shared.id(), ProgressListener.NONE));

		assertEquals("chunks", Files.readString(chunksKeptInSharedsaves()));
	}

	@Test
	void ifTheOldLocalCopyCannotBeMovedAsideItStaysAsItWas(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		hostASession(friend(bobsSharedsaves), shared.id(), "chunks with Bob's farm");
		LocalCopy before = coordinator().localCopy(shared.id()).orElseThrow();
		SessionCoordinator alice = coordinatorMovingWith((from, to) -> {
			if (from.equals(before.folder())) throw new AccessDeniedException(from.toString());
			Files.move(from, to);
		});

		assertThrows(AccessDeniedException.class, () -> alice.play(shared.id(), ProgressListener.NONE));

		assertEquals(Optional.of(before), alice.localCopy(shared.id()));
		assertEquals("chunks", Files.readString(before.folder().resolve("region/r.0.0.mca")));
		try (Stream<Path> left = Files.list(sharedsaves)) {
			assertEquals(List.of(before.folder()), left.toList(), "no leftovers");
		}
	}

	/** On Windows the delete fails when a file is still open; here, a read-only folder makes it fail. */
	@Test
	@DisabledOnOs(OS.WINDOWS)
	void playStillOpensTheNewerRevisionWhenTheOldLocalCopyCannotBeDeleted(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		hostASession(friend(bobsSharedsaves), shared.id(), "chunks with Bob's farm");
		Path oldRegion = coordinator().localCopy(shared.id()).orElseThrow().folder().resolve("region");
		Files.setPosixFilePermissions(oldRegion, PosixFilePermissions.fromString("r-xr-xr-x"));
		try {
			PlayResult result = coordinator().play(shared.id(), ProgressListener.NONE);

			LocalCopy updated = assertInstanceOf(PlayResult.Ready.class, result).localCopy();
			assertEquals(2, updated.revision());
			assertEquals("chunks with Bob's farm", Files.readString(updated.folder().resolve("region/r.0.0.mca")));
		} finally {
			// Wherever the old copy ended up, let @TempDir delete it.
			try (Stream<Path> walk = Files.walk(sharedsaves)) {
				for (Path folder : walk.filter(Files::isDirectory).toList()) {
					Files.setPosixFilePermissions(folder, PosixFilePermissions.fromString("rwxr-xr-x"));
				}
			}
		}
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
		coordinator().heartbeat(shared.id()); // the game sends one every minute while hosting

		coordinator().periodicUpload(shared.id(), folder -> WorldZip.copyForUpload(local.folder(), folder), ProgressListener.NONE);

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

	// --- Leftovers of a record write ---

	@Test
	void aTempFileLeftByARecordWriteCutShortIsNotUploaded() throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		LocalCopy local = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		leaveATempFileOfARecordWrite(local.folder());

		coordinator().quit(shared.id(), ProgressListener.NONE);

		assertEquals(Map.of("level.dat", "level data", "region/r.0.0.mca", "chunks"), cloud.currentSaveEntries(shared.id()));
	}

	/** What a game killed partway through writing the Local Copy's record leaves behind. */
	private static void leaveATempFileOfARecordWrite(Path worldFolder) throws IOException {
		Path record = worldFolder.resolve("world-gdrive-sync.properties");
		String contents = Files.readString(record);
		List<Path> before = entriesOf(worldFolder);
		List<Path> temps = new ArrayList<>();
		AtomicFile.write(record, out -> {
			temps.addAll(entriesOf(worldFolder));
			temps.removeAll(before);
			out.write(contents);
		});
		assertEquals(1, temps.size(), "the write's temp file");
		Files.writeString(temps.getFirst(), "revi");
	}

	private static List<Path> entriesOf(Path folder) throws IOException {
		try (Stream<Path> files = Files.list(folder)) {
			return files.toList();
		}
	}

	// --- Local-only Data ---

	private static final String OVERWORLD_LODS = "dimensions/minecraft/overworld/data/DistantHorizons.sqlite";
	private static final String NETHER_LODS = "dimensions/minecraft/the_nether/data/DistantHorizons.sqlite";

	@Test
	void sharingKeepsLocalOnlyDataInTheLocalCopyButNeverUploadsIt() throws Exception {
		Path world = singleplayerWorld("Survival");
		write(world.resolve(OVERWORLD_LODS), "overworld LODs");
		write(world.resolve(OVERWORLD_LODS + "-wal"), "overworld journal");
		write(world.resolve(NETHER_LODS), "nether LODs");
		write(world.resolve("dimensions/minecraft/overworld/data/raids.dat"), "raids");

		SharedWorld shared = coordinator().share(world, "Survival", ProgressListener.NONE);

		assertEquals(Map.of(
				"level.dat", "level data",
				"region/r.0.0.mca", "chunks",
				"dimensions/minecraft/overworld/data/raids.dat", "raids"), cloud.currentSaveEntries(shared.id()));
		Path local = coordinator().localCopy(shared.id()).orElseThrow().folder();
		assertEquals("overworld LODs", Files.readString(local.resolve(OVERWORLD_LODS)));
		assertEquals("overworld journal", Files.readString(local.resolve(OVERWORLD_LODS + "-wal")));
		assertEquals("nether LODs", Files.readString(local.resolve(NETHER_LODS)));
	}

	@Test
	void aPeriodicUploadNeitherCopiesNorUploadsLocalOnlyData() throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		LocalCopy local = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();
		write(local.folder().resolve(OVERWORLD_LODS), "overworld LODs");
		clock.advance(Duration.ofMinutes(20));

		coordinator().periodicUpload(shared.id(), folder -> {
			WorldZip.copyForUpload(local.folder(), folder);
			assertFalse(Files.exists(folder.resolve(OVERWORLD_LODS)), "DH's open database is not even read");
		}, ProgressListener.NONE);

		assertFalse(cloud.currentSaveEntries(shared.id()).containsKey(OVERWORLD_LODS));
	}

	@Test
	void downloadingANewerCurrentSaveKeepsThisPlayersLocalOnlyData(@TempDir Path bobsSharedsaves) throws Exception {
		Path world = singleplayerWorld("Survival");
		write(world.resolve(OVERWORLD_LODS), "Alice's overworld LODs");
		write(world.resolve(OVERWORLD_LODS + "-wal"), "Alice's journal");
		write(world.resolve(NETHER_LODS), "Alice's nether LODs");
		SharedWorld shared = coordinator().share(world, "Survival", ProgressListener.NONE);
		hostASession(friend(bobsSharedsaves), shared.id(), "chunks with Bob's farm");

		LocalCopy updated = ((PlayResult.Ready) coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();

		assertEquals("chunks with Bob's farm", Files.readString(updated.folder().resolve("region/r.0.0.mca")));
		assertEquals("Alice's overworld LODs", Files.readString(updated.folder().resolve(OVERWORLD_LODS)));
		assertEquals("Alice's journal", Files.readString(updated.folder().resolve(OVERWORLD_LODS + "-wal")));
		assertEquals("Alice's nether LODs", Files.readString(updated.folder().resolve(NETHER_LODS)));
	}

	/** Current Saves uploaded before Local-only Data was left out still contain it. */
	@Test
	void localOnlyDataInADownloadedZipStaysWhereTheLocalCopyHasNone(@TempDir Path elsewhere) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		replaceCurrentSave(shared.id(), Map.of(OVERWORLD_LODS, "old upload's LODs", NETHER_LODS, "old upload's nether LODs"), elsewhere);
		Path folder = coordinator().localCopy(shared.id()).orElseThrow().folder();
		write(folder.resolve(NETHER_LODS), "Alice's nether LODs");

		coordinator().play(shared.id(), ProgressListener.NONE);

		assertEquals("old upload's LODs", Files.readString(folder.resolve(OVERWORLD_LODS)));
		assertEquals("Alice's nether LODs", Files.readString(folder.resolve(NETHER_LODS)));
	}

	/** A journal only fits the database it was written for. */
	@Test
	void aDownloadedZipsJournalIsNotLeftBesideThisPlayersDatabase(@TempDir Path elsewhere) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		replaceCurrentSave(shared.id(), Map.of(OVERWORLD_LODS, "old upload's LODs", OVERWORLD_LODS + "-wal", "old upload's journal"), elsewhere);
		Path folder = coordinator().localCopy(shared.id()).orElseThrow().folder();
		write(folder.resolve(OVERWORLD_LODS), "Alice's overworld LODs");

		coordinator().play(shared.id(), ProgressListener.NONE);

		assertEquals("Alice's overworld LODs", Files.readString(folder.resolve(OVERWORLD_LODS)));
		assertFalse(Files.exists(folder.resolve(OVERWORLD_LODS + "-wal")));
	}

	@Test
	void aFailureCarryingLocalOnlyDataOverStillDownloadsAndKeepsTheRest(@TempDir Path elsewhere) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		// A file where the nether's folder would go, so its LODs can't be put in the new copy.
		replaceCurrentSave(shared.id(), Map.of("dimensions/minecraft/the_nether", "in the way"), elsewhere);
		Path folder = coordinator().localCopy(shared.id()).orElseThrow().folder();
		write(folder.resolve(NETHER_LODS), "Alice's nether LODs");
		write(folder.resolve(OVERWORLD_LODS), "Alice's overworld LODs");

		PlayResult result = coordinator().play(shared.id(), ProgressListener.NONE);

		assertEquals(2, assertInstanceOf(PlayResult.Ready.class, result).localCopy().revision());
		assertEquals("in the way", Files.readString(folder.resolve("dimensions/minecraft/the_nether")));
		assertEquals("Alice's overworld LODs", Files.readString(folder.resolve(OVERWORLD_LODS)));
	}

	@Test
	void ifTheNewRevisionCannotBeMovedInTheLocalCopyKeepsItsLocalOnlyData(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		hostASession(friend(bobsSharedsaves), shared.id(), "chunks with Bob's farm");
		Path folder = coordinator().localCopy(shared.id()).orElseThrow().folder();
		write(folder.resolve(OVERWORLD_LODS), "Alice's overworld LODs");
		AtomicBoolean failed = new AtomicBoolean();
		SessionCoordinator alice = coordinatorMovingWith((from, to) -> {
			if (to.equals(folder) && failed.compareAndSet(false, true)) throw new AccessDeniedException(to.toString());
			Files.move(from, to);
		});

		assertThrows(AccessDeniedException.class, () -> alice.play(shared.id(), ProgressListener.NONE));

		assertEquals("Alice's overworld LODs", Files.readString(folder.resolve(OVERWORLD_LODS)));
	}

	@Test
	void anUnmergedBackupLeavesLocalOnlyDataOutAndTheNewLocalCopyKeepsIt(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		Path folder = coordinator().localCopy(shared.id()).orElseThrow().folder();
		write(folder.resolve(OVERWORLD_LODS), "Alice's overworld LODs");
		leaveAPendingUploadThenLetBobTakeOverAndUpload(shared.id(), bobsSharedsaves);

		RetryResult result = coordinator().retryPendingUpload(shared.id(), ProgressListener.NONE);

		Backup unmerged = assertInstanceOf(RetryResult.KeptAsUnmergedBackup.class, result).backup();
		assertFalse(cloud.backupEntries(shared.id(), unmerged.id()).containsKey(OVERWORLD_LODS));
		assertEquals("Alice's overworld LODs", Files.readString(folder.resolve(OVERWORLD_LODS)));
	}

	/** Makes a Current Save at the next Revision holding {@code files}, however the mod would zip them. */
	private void replaceCurrentSave(String worldId, Map<String, String> files, Path elsewhere) throws IOException {
		Path zip = elsewhere.resolve("current.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			for (Map.Entry<String, String> file : files.entrySet()) {
				out.putNextEntry(new ZipEntry(file.getKey()));
				out.write(file.getValue().getBytes(StandardCharsets.UTF_8));
				out.closeEntry();
			}
		}
		WorldMetadata previous = cloud.metadata(worldId);
		cloud.replaceCurrentSave(worldId, zip, new WorldMetadata(previous.revision() + 1, clock.instant()),
				BackupLabel.of(previous), sent -> { });
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

	// --- Restoring a Backup ---

	@Test
	void restoreIsRefusedNamingTheHostWhileSomeoneHoldsTheLock(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		hostASession(coordinator(), shared.id(), "chunks with Alice's house");
		Backup backup = coordinator().backups(shared.id()).getFirst();
		coordinator().play(shared.id(), ProgressListener.NONE);
		SessionCoordinator bob = friend(bobsSharedsaves);

		RestoreResult result = bob.restoreBackup(shared.id(), backup.id());

		RestoreResult.Refused refused = assertInstanceOf(RestoreResult.Refused.class, result);
		assertEquals("Alice", refused.heldBy().hostName());
		assertEquals(2, cloud.metadata(shared.id()).revision());
		assertEquals(Map.of("level.dat", "level data", "region/r.0.0.mca", "chunks with Alice's house"), cloud.currentSaveEntries(shared.id()));
		assertEquals(List.of(backup), bob.backups(shared.id()));
	}

	@Test
	void restoringMakesTheBackupTheCurrentSaveAtANewRevisionAndKeepsTheReplacedOneAsABackup(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		hostASession(coordinator(), shared.id(), "chunks with Alice's house");
		hostASession(coordinator(), shared.id(), "chunks after the creeper");
		Backup beforeTheCreeper = coordinator().backups(shared.id()).getFirst();
		clock.advance(Duration.ofMinutes(5));
		Instant restoredAt = clock.instant();
		SessionCoordinator bob = friend(bobsSharedsaves);

		RestoreResult result = bob.restoreBackup(shared.id(), beforeTheCreeper.id());

		RestoreResult.Restored restored = assertInstanceOf(RestoreResult.Restored.class, result);
		assertEquals(new WorldMetadata(4, restoredAt), restored.current());
		assertEquals(new WorldMetadata(4, restoredAt), cloud.metadata(shared.id()));
		assertEquals(Map.of("level.dat", "level data", "region/r.0.0.mca", "chunks with Alice's house"), cloud.currentSaveEntries(shared.id()));
		// The creeper world (Revision 3) is a Backup now, so the restore can be undone.
		Backup creeper = bob.backups(shared.id()).getFirst();
		assertEquals(restored.replaced(), creeper);
		assertEquals(3, creeper.revision());
		assertEquals(Map.of("level.dat", "level data", "region/r.0.0.mca", "chunks after the creeper"), cloud.backupEntries(shared.id(), creeper.id()));
		// The restored Backup moved out of the Backups list; Revision 1 is still there.
		assertEquals(List.of(3, 1), bob.backups(shared.id()).stream().map(Backup::revision).toList());
		assertEquals(WorldStatus.FREE, bob.listSharedWorlds().getFirst().status());
	}

	@Test
	void afterARestoreAnotherPlayersNextPlayDownloadsTheRestoredWorld(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		hostASession(coordinator(), shared.id(), "chunks after the creeper");
		Backup original = coordinator().backups(shared.id()).getFirst();
		friend(bobsSharedsaves).restoreBackup(shared.id(), original.id());

		// Alice's Local Copy is Revision 2, the creeper world; the restore is Revision 3.
		LocalCopy local = assertInstanceOf(PlayResult.Ready.class, coordinator().play(shared.id(), ProgressListener.NONE)).localCopy();

		assertEquals(3, local.revision());
		assertEquals("chunks", Files.readString(local.folder().resolve("region/r.0.0.mca")));
	}

	@Test
	void nobodyCanStartPlayingWhileARestoreIsUnderWay(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		hostASession(coordinator(), shared.id(), "chunks after the creeper");
		Backup original = coordinator().backups(shared.id()).getFirst();
		SessionCoordinator bob = friend(bobsSharedsaves);
		List<PlayResult> alicesResult = new ArrayList<>();
		cloud.beforeNext(InMemoryCloudStore.Call.RESTORE, () -> alicesResult.add(coordinator().play(shared.id(), ProgressListener.NONE)));

		assertInstanceOf(RestoreResult.Restored.class, bob.restoreBackup(shared.id(), original.id()));

		assertEquals("Bob", assertInstanceOf(PlayResult.Refused.class, alicesResult.getFirst()).heldBy().hostName());
		assertEquals(List.of(), cloud.lockHolders(shared.id()), "the restore released its Lock");
	}

	@Test
	void whenAPlayAndARestoreStartTogetherTheEarliestLockWins(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		hostASession(coordinator(), shared.id(), "chunks after the creeper");
		Backup original = coordinator().backups(shared.id()).getFirst();
		SessionCoordinator bob = friend(bobsSharedsaves);
		List<PlayResult> alicesResult = new ArrayList<>();
		// Both saw the world Free. Alice's Lock lands just before Bob's does.
		cloud.beforeNext(InMemoryCloudStore.Call.LOCK_WRITE, () -> alicesResult.add(coordinator().play(shared.id(), ProgressListener.NONE)));

		RestoreResult result = bob.restoreBackup(shared.id(), original.id());

		assertEquals("Alice", assertInstanceOf(RestoreResult.Refused.class, result).heldBy().hostName());
		assertInstanceOf(PlayResult.Ready.class, alicesResult.getFirst());
		assertEquals(2, cloud.metadata(shared.id()).revision());
		assertEquals("chunks after the creeper", cloud.currentSaveEntries(shared.id()).get("region/r.0.0.mca"));
		assertEquals(List.of("Alice"), cloud.lockHolders(shared.id()));
	}

	@Test
	void restoreIsRefusedEvenWhenTheLockIsLongStale(@TempDir Path bobsSharedsaves) throws Exception {
		SharedWorld shared = coordinator().share(singleplayerWorld("Survival"), "Survival", ProgressListener.NONE);
		hostASession(coordinator(), shared.id(), "chunks with Alice's house");
		Backup backup = coordinator().backups(shared.id()).getFirst();
		coordinator().play(shared.id(), ProgressListener.NONE);
		clock.advance(Duration.ofDays(3)); // Alice crashed: no Heartbeat for days.

		RestoreResult result = friend(bobsSharedsaves).restoreBackup(shared.id(), backup.id());

		assertEquals("Alice", assertInstanceOf(RestoreResult.Refused.class, result).heldBy().hostName());
		assertEquals(2, cloud.metadata(shared.id()).revision());
	}

	private SessionCoordinator coordinatorMovingWith(SessionCoordinator.FolderMover mover) {
		return new SessionCoordinator(cloud, clock, sharedsaves, ALICE, () -> Settings.DEFAULTS, mover);
	}

	private Path chunksKeptInSharedsaves() throws IOException {
		try (Stream<Path> files = Files.walk(sharedsaves)) {
			return files.filter(file -> file.endsWith("region/r.0.0.mca")).findFirst().orElseThrow();
		}
	}

	/** Plays a whole session as {@code player}: Play, change the world, an hour later save and quit. */
	private void hostASession(SessionCoordinator player, String worldId, String chunks) throws IOException {
		LocalCopy local = ((PlayResult.Ready) player.play(worldId, ProgressListener.NONE)).localCopy();
		write(local.folder().resolve("region/r.0.0.mca"), chunks);
		clock.advance(Duration.ofHours(1));
		player.quit(worldId, ProgressListener.NONE);
	}

	/**
	 * Alice plays and builds a house, but her final upload fails (a Pending Upload). Ten minutes
	 * later her Lock is Stale; Bob Takes Over, builds a farm and uploads Revision 2.
	 */
	private void leaveAPendingUploadThenLetBobTakeOverAndUpload(String worldId, Path bobsSharedsaves) throws IOException {
		LocalCopy alices = ((PlayResult.Ready) coordinator().play(worldId, ProgressListener.NONE)).localCopy();
		write(alices.folder().resolve("region/r.0.0.mca"), "chunks with Alice's house");
		cloud.failUploads();
		assertThrows(IOException.class, () -> coordinator().quit(worldId, ProgressListener.NONE));
		cloud.uploadsWork();
		clock.advance(Duration.ofMinutes(10));
		SessionCoordinator bob = friend(bobsSharedsaves);
		LocalCopy bobs = ((PlayResult.Ready) bob.takeOver(worldId, ProgressListener.NONE)).localCopy();
		write(bobs.folder().resolve("region/r.0.0.mca"), "chunks with Bob's farm");
		clock.advance(Duration.ofHours(1));
		bob.quit(worldId, ProgressListener.NONE);
	}

	private List<Backup> unmergedBackups(String worldId) throws IOException {
		return coordinator().backups(worldId).stream().filter(backup -> backup.unmerged().isPresent()).toList();
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
