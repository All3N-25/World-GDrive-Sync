package net.aii3n.worldgdrivesync.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checking the account's free space before every upload, and warning when it is nearly full. */
class StorageSpaceTest {
	private static final Instant SHARED_AT = Instant.parse("2026-11-24T18:00:00Z");
	private static final Player ALICE = new Player("uuid-alice", "Alice");

	@TempDir
	Path saves;
	@TempDir
	Path sharedsaves;

	InMemoryCloudStore cloud;
	TestClock clock;
	SessionCoordinator coordinator;

	@BeforeEach
	void setUp() throws IOException {
		cloud = new InMemoryCloudStore();
		clock = new TestClock(SHARED_AT);
		coordinator = new SessionCoordinator(cloud, clock, sharedsaves, ALICE);
		Files.writeString(saves.resolve("level.dat"), "level data");
	}

	@Test
	void sharingAWorldThatWontFitCreatesNoCloudFolder() throws Exception {
		cloud.setFreeSpace(10);

		NotEnoughSpaceException refused = assertThrows(NotEnoughSpaceException.class,
				() -> coordinator.share(saves, "Survival", ProgressListener.NONE));

		assertEquals("Survival", refused.worldName());
		assertEquals(OptionalLong.of(10), refused.free());
		assertTrue(refused.needed() > 10);
		assertEquals(0, cloud.worldsCreated());
		try (Stream<Path> leftovers = Files.list(sharedsaves)) {
			assertEquals(List.of(), leftovers.toList());
		}
	}

	@Test
	void aFinalUploadThatWontFitUploadsNothingAndKeepsTheLockWithAPendingUpload() throws Exception {
		String worldId = shareAndPlay();
		cloud.setFreeSpace(10);

		NotEnoughSpaceException refused = assertThrows(NotEnoughSpaceException.class,
				() -> coordinator.quit(worldId, ProgressListener.NONE));

		assertEquals("Survival", refused.worldName());
		assertNothingUploaded(worldId);
		assertEquals(List.of("Alice"), cloud.lockHolders(worldId));
		assertTrue(coordinator.localCopy(worldId).orElseThrow().pendingUpload());
	}

	@Test
	void aPeriodicUploadThatWontFitUploadsNothingAndHostingContinues() throws Exception {
		String worldId = shareAndPlay();
		Path folder = coordinator.localCopy(worldId).orElseThrow().folder();
		cloud.setFreeSpace(10);

		assertThrows(NotEnoughSpaceException.class, () -> coordinator.periodicUpload(worldId,
				copy -> WorldZip.copyForUpload(folder, copy), ProgressListener.NONE));

		assertNothingUploaded(worldId);
		assertEquals(List.of("Alice"), cloud.lockHolders(worldId));
		assertFalse(coordinator.localCopy(worldId).orElseThrow().pendingUpload());
	}

	@Test
	void aRetryThatWontFitStaysAPendingUploadAndGoesUpOnceThereIsRoom() throws Exception {
		String worldId = shareAndPlay();
		cloud.setFreeSpace(10);
		assertThrows(NotEnoughSpaceException.class, () -> coordinator.quit(worldId, ProgressListener.NONE));

		assertThrows(NotEnoughSpaceException.class, () -> coordinator.retryPendingUpload(worldId, ProgressListener.NONE));

		assertNothingUploaded(worldId);
		assertEquals(List.of(worldId), coordinator.pendingUploads());
		assertEquals(List.of("Alice"), cloud.lockHolders(worldId));

		cloud.setFreeSpace(1_000_000);
		coordinator.retryPendingUpload(worldId, ProgressListener.NONE);

		assertEquals(2, cloud.metadata(worldId).revision());
		assertEquals(List.of(), coordinator.pendingUploads());
	}

	@Test
	void anUnmergedBackupThatWontFitIsNotUploadedAndTheProgressStaysOnThisComputer(@TempDir Path bobsSharedsaves) throws Exception {
		String worldId = shareAndPlay();
		cloud.failUploads();
		assertThrows(IOException.class, () -> coordinator.quit(worldId, ProgressListener.NONE));
		cloud.uploadsWork();
		clock.advance(Duration.ofMinutes(10));
		SessionCoordinator bob = new SessionCoordinator(cloud, clock, bobsSharedsaves, new Player("uuid-bob", "Bob"));
		bob.takeOver(worldId, ProgressListener.NONE);
		bob.quit(worldId, ProgressListener.NONE);
		List<Backup> before = coordinator.backups(worldId);
		cloud.setFreeSpace(10);

		assertThrows(NotEnoughSpaceException.class, () -> coordinator.retryPendingUpload(worldId, ProgressListener.NONE));
		assertThrows(NotEnoughSpaceException.class, () -> coordinator.play(worldId, ProgressListener.NONE));

		assertEquals(before, coordinator.backups(worldId));
		assertEquals(2, cloud.metadata(worldId).revision());
		assertEquals(List.of(worldId), coordinator.pendingUploads());
	}

	@Test
	void anUploadExactlyTheSizeOfTheFreeSpaceGoesAhead() throws Exception {
		cloud.setFreeSpace(10);
		long size = assertThrows(NotEnoughSpaceException.class,
				() -> coordinator.share(saves, "Survival", ProgressListener.NONE)).needed();
		cloud.setFreeSpace(size);

		assertEquals(1, coordinator.share(saves, "Survival", ProgressListener.NONE).revision());
	}

	@Test
	void ifTheFreeSpaceCantBeReadUploadsGoAheadAsBefore() throws Exception {
		cloud.setFreeSpace(10);
		cloud.failQuotaReads();

		String worldId = shareAndPlay();
		coordinator.quit(worldId, ProgressListener.NONE);

		assertEquals(2, cloud.metadata(worldId).revision());
	}

	@Test
	void anUploadTheCloudStoreRefusesForWantOfRoomSaysWhichWorldDidntFit() throws Exception {
		String worldId = shareAndPlay();
		cloud.refuseUploadsAsFull();

		NotEnoughSpaceException refused = assertThrows(NotEnoughSpaceException.class,
				() -> coordinator.quit(worldId, ProgressListener.NONE));

		assertEquals("Survival", refused.worldName());
		assertTrue(refused.needed() > 0);
		assertTrue(coordinator.localCopy(worldId).orElseThrow().pendingUpload());
	}

	@Test
	void aRefusedShareDoesntQuoteAFreeSpaceTheWorldWouldFitIn() throws Exception {
		cloud.setFreeSpace(1_000_000);
		cloud.refuseUploadsAsFull();

		NotEnoughSpaceException refused = assertThrows(NotEnoughSpaceException.class,
				() -> coordinator.share(saves, "Survival", ProgressListener.NONE));

		assertEquals(OptionalLong.empty(), refused.free());
		assertEquals(List.of(), cloud.listWorlds());
	}

	@Test
	void aShareTheCloudStoreCantMakeAFolderForSaysWhichWorldDidntFit() {
		cloud.refuseNewFilesAsFull();

		NotEnoughSpaceException refused = assertThrows(NotEnoughSpaceException.class,
				() -> coordinator.share(saves, "Survival", ProgressListener.NONE));

		assertEquals("Survival", refused.worldName());
		assertTrue(refused.needed() > 0);
	}

	@Test
	void aLockThatCantBeWrittenForWantOfRoomSaysTheStorageIsFull() throws Exception {
		String worldId = coordinator.share(saves, "Survival", ProgressListener.NONE).id();
		cloud.refuseNewFilesAsFull();

		IOException refused = assertThrows(IOException.class, () -> coordinator.play(worldId, ProgressListener.NONE));

		assertTrue(StorageFullException.in(refused).isPresent());
	}

	@Test
	void theAccountIsNearlyFullExactlyWhenItCantTakeTwiceTheSize() {
		assertEquals(OptionalLong.empty(), new StorageQuota(OptionalLong.of(300), 100).freeSpaceIfNearlyFull(100));
		assertEquals(OptionalLong.of(199), new StorageQuota(OptionalLong.of(299), 100).freeSpaceIfNearlyFull(100));
		assertEquals(OptionalLong.empty(), new StorageQuota(OptionalLong.empty(), 100).freeSpaceIfNearlyFull(Long.MAX_VALUE));
	}

	@Test
	void everyKindOfUploadThatLeavesTheAccountNearlyFullWarns(@TempDir Path bobsSharedsaves) throws Exception {
		List<Long> warnings = new ArrayList<>();
		coordinator.whenNearlyFull(warnings::add);
		String worldId = shareAndPlay();
		long size = cloud.currentSaveSize(worldId);
		Path folder = coordinator.localCopy(worldId).orElseThrow().folder();
		// Room for this upload, but not for two like it.
		long free = size + size / 2;
		cloud.setFreeSpace(free);

		coordinator.periodicUpload(worldId, copy -> WorldZip.copyForUpload(folder, copy), ProgressListener.NONE);
		assertEquals(List.of(free), warnings, "periodic upload");

		coordinator.quit(worldId, ProgressListener.NONE);
		assertEquals(List.of(free, free), warnings, "final upload");

		coordinator.share(saves, "Creative", ProgressListener.NONE);
		assertEquals(List.of(free, free, free), warnings, "sharing");

		coordinator.play(worldId, ProgressListener.NONE);
		cloud.failUploads();
		assertThrows(IOException.class, () -> coordinator.quit(worldId, ProgressListener.NONE));
		cloud.uploadsWork();
		assertEquals(3, warnings.size(), "a failed upload doesn't warn");
		clock.advance(Duration.ofMinutes(10));
		SessionCoordinator bob = new SessionCoordinator(cloud, clock, bobsSharedsaves, new Player("uuid-bob", "Bob"));
		bob.takeOver(worldId, ProgressListener.NONE);
		bob.quit(worldId, ProgressListener.NONE);
		coordinator.retryPendingUpload(worldId, ProgressListener.NONE);
		assertEquals(4, warnings.size(), "Unmerged Backup");
	}

	@Test
	void anUploadThatLeavesRoomForTwoMoreLikeItDoesntWarn() throws Exception {
		List<Long> warnings = new ArrayList<>();
		coordinator.whenNearlyFull(warnings::add);
		String worldId = shareAndPlay();
		cloud.setFreeSpace(2 * cloud.currentSaveSize(worldId));

		coordinator.quit(worldId, ProgressListener.NONE);

		assertEquals(List.of(), warnings);
	}

	@Test
	void withUnlimitedStorageUploadsGoAheadAndNothingWarns() throws Exception {
		List<Long> warnings = new ArrayList<>();
		coordinator.whenNearlyFull(warnings::add);
		String worldId = shareAndPlay();

		coordinator.quit(worldId, ProgressListener.NONE);

		assertEquals(2, cloud.metadata(worldId).revision());
		assertEquals(List.of(), warnings);
		assertEquals(OptionalLong.empty(), coordinator.sharedWorldListing().nearlyFullFreeSpace());
	}

	@Test
	void theListingSaysTheAccountIsNearlyFullWhenItCantTakeTwiceTheLargestCurrentSave() throws Exception {
		String small = coordinator.share(saves, "Small", ProgressListener.NONE).id();
		Files.writeString(saves.resolve("level.dat"), "a much longer level data ".repeat(100) + "0123456789".repeat(50));
		String large = coordinator.share(saves, "Large", ProgressListener.NONE).id();
		long largest = cloud.currentSaveSize(large);
		assertTrue(largest > cloud.currentSaveSize(small));

		cloud.setFreeSpace(2 * largest);
		assertEquals(OptionalLong.empty(), coordinator.sharedWorldListing().nearlyFullFreeSpace());

		cloud.setFreeSpace(2 * largest - 1);
		SharedWorldListing listing = coordinator.sharedWorldListing();
		assertEquals(OptionalLong.of(2 * largest - 1), listing.nearlyFullFreeSpace());
		assertEquals(2, listing.worlds().size());
	}

	@Test
	void ifTheFreeSpaceCantBeReadTheWorldsAreStillListed() throws Exception {
		coordinator.share(saves, "Survival", ProgressListener.NONE);
		cloud.setFreeSpace(0);
		cloud.failQuotaReads();

		SharedWorldListing listing = coordinator.sharedWorldListing();

		assertEquals(1, listing.worlds().size());
		assertEquals(OptionalLong.empty(), listing.nearlyFullFreeSpace());
	}

	@Test
	void sizesReadAsMegabytesAndFromOneGigabyteAsGigabytes() {
		assertEquals("1.5 MB", ByteSize.format(1_572_864));
		assertEquals("1023.0 MB", ByteSize.format(1023L * 1024 * 1024));
		assertEquals("1.5 GB", ByteSize.format(1_610_612_736));
	}

	private String shareAndPlay() throws IOException {
		String worldId = coordinator.share(saves, "Survival", ProgressListener.NONE).id();
		coordinator.play(worldId, ProgressListener.NONE);
		return worldId;
	}

	/** The Current Save is still Revision 1, with no Backup of anything. */
	private void assertNothingUploaded(String worldId) throws IOException {
		assertEquals(1, cloud.metadata(worldId).revision());
		assertEquals(List.of(), coordinator.backups(worldId));
	}
}
