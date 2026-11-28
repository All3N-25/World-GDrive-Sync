package net.aii3n.worldgdrivesync.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** {@link SessionCoordinator#listSharedWorlds} running on another thread while an upload runs. */
class ListingDuringUploadTest {
	private static final Instant SHARED_AT = Instant.parse("2026-11-24T18:00:00Z");
	private static final Player ALICE = new Player("uuid-alice", "Alice");
	private static final int LISTINGS = 200;

	@TempDir
	Path saves;
	@TempDir
	Path sharedsaves;

	InMemoryCloudStore cloud;
	SessionCoordinator coordinator;
	String worldId;
	Path record;

	@BeforeEach
	void shareAndPlay() throws Exception {
		cloud = new InMemoryCloudStore();
		coordinator = new SessionCoordinator(cloud, new TestClock(SHARED_AT), sharedsaves, ALICE);
		Files.writeString(saves.resolve("level.dat"), "level data");
		worldId = coordinator.share(saves, "Survival", ProgressListener.NONE).id();
		LocalCopy local = ((PlayResult.Ready) coordinator.play(worldId, ProgressListener.NONE)).localCopy();
		record = local.folder().resolve(LocalCopyRecord.FILE_NAME);
	}

	@Test
	void listingDuringAQuitChangesNeitherTheLocalCopyRecordNorTheLock() throws Exception {
		assertListingLeavesAnUploadAlone(() -> coordinator.quit(worldId, ProgressListener.NONE));
	}

	@Test
	void listingDuringARetryChangesNeitherTheLocalCopyRecordNorTheLock() throws Exception {
		assertListingLeavesAnUploadAlone(() -> coordinator.retryPendingUpload(worldId, ProgressListener.NONE));
	}

	private void assertListingLeavesAnUploadAlone(Upload upload) throws Exception {
		CompletableFuture<Void> uploading;
		try (BlockedUpload blocked = new BlockedUpload(cloud)) {
			uploading = start(upload);
			blocked.awaitStarted();
			String recordDuring = Files.readString(record);
			List<String> holdersDuring = cloud.lockHolders(worldId);

			for (int i = 0; i < LISTINGS; i++) coordinator.listSharedWorlds();

			assertEquals(recordDuring, Files.readString(record));
			assertEquals(holdersDuring, cloud.lockHolders(worldId));
		}
		// Keep listing while the upload finishes: it writes the record and releases the Lock.
		while (!uploading.isDone()) coordinator.listSharedWorlds();
		uploading.get(10, TimeUnit.SECONDS);

		assertEquals(new LocalCopy(record.getParent(), 2, false), coordinator.localCopy(worldId).orElseThrow());
		assertEquals(List.of(), coordinator.pendingUploads());
		assertEquals(List.of(), cloud.lockHolders(worldId));
		SharedWorld listed = coordinator.listSharedWorlds().getFirst();
		assertEquals(2, listed.revision());
		assertEquals(WorldStatus.FREE, listed.status());
	}

	private static CompletableFuture<Void> start(Upload upload) {
		CompletableFuture<Void> done = new CompletableFuture<>();
		new Thread(() -> {
			try {
				upload.run();
				done.complete(null);
			} catch (Throwable e) {
				done.completeExceptionally(e);
			}
		}).start();
		return done;
	}

	@FunctionalInterface
	private interface Upload {
		void run() throws IOException;
	}
}
