package net.aii3n.worldgdrivesync.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class CoordinatorRunnerTest {
	@TempDir
	Path saves;
	@TempDir
	Path sharedsaves;

	@Test
	void sharedWorldsAreListedWhileAnUploadIsStillInProgress() throws Exception {
		InMemoryCloudStore cloud = new InMemoryCloudStore();
		SessionCoordinator coordinator = new SessionCoordinator(cloud, new TestClock(Instant.parse("2026-11-24T18:00:00Z")),
				sharedsaves, new Player("uuid-alice", "Alice"));
		CoordinatorRunner runner = new CoordinatorRunner(() -> coordinator);
		Files.writeString(saves.resolve("level.dat"), "level data");
		SharedWorld shared = coordinator.share(saves, "Survival", ProgressListener.NONE);
		coordinator.play(shared.id(), ProgressListener.NONE);

		CompletableFuture<WorldMetadata> upload;
		try (BlockedUpload blocked = new BlockedUpload(cloud)) {
			upload = runner.run(c -> c.quit(shared.id(), ProgressListener.NONE));
			blocked.awaitStarted();

			List<SharedWorld> listed = runner.sharedWorldListing().get(5, TimeUnit.SECONDS).worlds();

			assertEquals(List.of("Survival"), listed.stream().map(SharedWorld::name).toList());
			assertEquals(1, listed.getFirst().revision());
			assertFalse(upload.isDone());
		}
		assertEquals(2, upload.get(5, TimeUnit.SECONDS).revision());
	}
}
