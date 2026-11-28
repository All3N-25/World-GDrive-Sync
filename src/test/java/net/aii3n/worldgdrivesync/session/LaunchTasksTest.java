package net.aii3n.worldgdrivesync.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class LaunchTasksTest {
	@TempDir
	Path saves;
	@TempDir
	Path sharedsaves;

	InMemoryCloudStore cloud = new InMemoryCloudStore();
	SessionCoordinator coordinator;
	CoordinatorRunner runner;
	String worldId;
	Path leftover;
	final List<String> reports = new ArrayList<>();
	final LaunchTasks.Report report = new LaunchTasks.Report() {
		@Override
		public void started(String id) {
			reports.add("started " + id);
		}

		@Override
		public void succeeded(String id, RetryResult result) {
			reports.add("succeeded " + id);
		}

		@Override
		public void failed(String id, Exception failure) {
			reports.add("failed " + id);
		}

		@Override
		public void cleanUpFailed(Throwable failure) {
			reports.add("clean-up failed");
		}

		@Override
		public void retryFailed(Throwable failure) {
			reports.add("retry failed");
		}
	};

	/** A Pending Upload, and a leftover zip in the Local Copy directory. */
	@BeforeEach
	void setUp() throws IOException {
		coordinator = new SessionCoordinator(cloud, new TestClock(Instant.parse("2026-11-24T18:00:00Z")),
				sharedsaves, new Player("uuid-alice", "Alice"));
		runner = new CoordinatorRunner(() -> coordinator);
		Files.writeString(saves.resolve("level.dat"), "level data");
		worldId = coordinator.share(saves, "Survival", ProgressListener.NONE).id();
		coordinator.play(worldId, ProgressListener.NONE);
		cloud.failUploads();
		try {
			coordinator.quit(worldId, ProgressListener.NONE);
		} catch (IOException expected) {
			// The Pending Upload.
		}
		cloud.uploadsWork();
		leftover = Files.writeString(sharedsaves.resolve(".wgds-leftover.zip"), "half a zip");
	}

	@Test
	void leftoversAreCleanedUpBeforeThePendingUploadsAreRetried() throws Exception {
		AtomicBoolean thereWhenTheRetryReachedTheCloud = new AtomicBoolean(true);
		cloud.beforeNext(InMemoryCloudStore.Call.LOCK_READ,
				() -> thereWhenTheRetryReachedTheCloud.set(Files.exists(leftover)));

		queue(true);

		assertFalse(thereWhenTheRetryReachedTheCloud.get());
		assertEquals(List.of("started " + worldId, "succeeded " + worldId), reports);
		assertEquals(List.of(), coordinator.pendingUploads());
	}

	@Test
	void withNoAccountConnectedLeftoversAreStillCleanedUpButNothingIsRetried() throws Exception {
		queue(false);

		assertFalse(Files.exists(leftover));
		assertEquals(List.of(), reports);
		assertEquals(List.of(worldId), coordinator.pendingUploads());
	}

	private void queue(boolean accountConnected) throws Exception {
		LaunchTasks.queue(runner, accountConnected, new LaunchRetry(), report).get(5, TimeUnit.SECONDS);
	}
}
