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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LaunchRetryTest {
	@TempDir
	Path saves;
	@TempDir
	Path sharedsaves;

	InMemoryCloudStore cloud;
	final TestClock clock = new TestClock(Instant.parse("2026-11-24T18:00:00Z"));
	SessionCoordinator coordinator;
	final LaunchRetry retry = new LaunchRetry();
	final List<String> reports = new ArrayList<>();

	@BeforeEach
	void setUp() throws IOException {
		cloud = new InMemoryCloudStore();
		coordinator = new SessionCoordinator(cloud, clock,
				sharedsaves, new Player("uuid-alice", "Alice"));
		Files.writeString(saves.resolve("level.dat"), "level data");
	}

	/** Shares a world and hosts it, leaving the session open as a crashed game would. */
	private String crashedSession(String name) throws IOException {
		String worldId = coordinator.share(saves, name, ProgressListener.NONE).id();
		coordinator.play(worldId, ProgressListener.NONE);
		return worldId;
	}

	/** Notes what the retry reports, with the percent the screen would show at that moment. */
	private final LaunchRetry.Report report = new LaunchRetry.Report() {
		@Override
		public void started(String worldId) {
			reports.add("started " + worldId + " at " + retry.state(worldId).orElseThrow());
		}

		@Override
		public void succeeded(String worldId, RetryResult result) {
			reports.add("succeeded " + worldId);
		}

		@Override
		public void failed(String worldId, Exception failure) {
			reports.add("failed " + worldId + ": " + failure.getMessage());
		}
	};

	@Test
	void theUploadsProgressGoesFrom0To100PercentAndIsClearedOnSuccess() throws Exception {
		String worldId = crashedSession("Survival");
		long mark = retry.endedCount();
		List<Optional<LaunchRetry.State>> seen = new ArrayList<>();
		cloud.afterEachChunkSent(() -> seen.add(retry.state(worldId)));

		retry.run(coordinator, report);

		assertEquals(Optional.of(new LaunchRetry.State.Uploading(100)), seen.getLast());
		List<Integer> percents = seen.stream().map(state -> ((LaunchRetry.State.Uploading) state.orElseThrow()).percent()).toList();
		assertTrue(percents.getFirst() < 10, percents.toString());
		assertEquals(percents.stream().sorted().toList(), percents);
		assertEquals(Optional.empty(), retry.state(worldId));
		assertTrue(retry.endedSince(worldId, mark));
		assertFalse(retry.endedSince(worldId, retry.endedCount()));
		assertEquals(List.of("started " + worldId + " at Uploading[percent=0]", "succeeded " + worldId), reports);
		assertEquals(2, coordinator.listSharedWorlds().getFirst().revision());
	}

	@Test
	void keepingAnUnmergedBackupShowsTheDownloadOfTheNewerRevisionThatFollows(@TempDir Path bobsSharedsaves) throws Exception {
		String worldId = crashedSession("Survival");
		SessionCoordinator bob = new SessionCoordinator(cloud, clock, bobsSharedsaves, new Player("uuid-bob", "Bob"));
		clock.advance(Duration.ofMinutes(10));
		bob.takeOver(worldId, ProgressListener.NONE);
		bob.quit(worldId, ProgressListener.NONE);
		List<Optional<LaunchRetry.State>> seen = new ArrayList<>();
		cloud.afterEachChunkReceived(() -> seen.add(retry.state(worldId)));

		retry.run(coordinator, report);

		assertEquals(Optional.of(new LaunchRetry.State.Downloading()), seen.getFirst());
		assertEquals(Optional.of(new LaunchRetry.State.Downloading()), seen.getLast());
		assertEquals(Optional.empty(), retry.state(worldId));
	}

	@Test
	void aFailedRetryIsClearedAndLeavesThePendingUpload() throws Exception {
		String worldId = crashedSession("Survival");
		cloud.failUploads();

		retry.run(coordinator, report);

		assertEquals(Optional.empty(), retry.state(worldId));
		assertEquals(1, retry.endedCount());
		assertEquals(List.of("started " + worldId + " at Uploading[percent=0]",
				"failed " + worldId + ": Network down (simulated)"), reports);
		assertTrue(coordinator.listSharedWorlds().getFirst().pendingUpload());
	}

	@Test
	void everyWorldIsBeingRetriedFromTheStartUntilItsOwnRetryEnds() throws Exception {
		String first = crashedSession("Survival");
		String second = crashedSession("Creative");
		String untouched = coordinator.share(saves, "Skyblock", ProgressListener.NONE).id();
		CompletableFuture<Void> running;
		try (BlockedUpload blocked = new BlockedUpload(cloud)) {
			running = CompletableFuture.runAsync(() -> run());
			blocked.awaitStarted();

			assertTrue(retry.state(first).orElseThrow() instanceof LaunchRetry.State.Uploading);
			assertEquals(Optional.of(new LaunchRetry.State.Waiting()), retry.state(second));
			assertEquals(Optional.empty(), retry.state(untouched));
		}
		running.get(10, TimeUnit.SECONDS);

		assertEquals(Optional.empty(), retry.state(first));
		assertEquals(Optional.empty(), retry.state(second));
		assertEquals(List.of("started " + first + " at Uploading[percent=0]", "succeeded " + first,
				"started " + second + " at Uploading[percent=0]", "succeeded " + second), reports);
	}

	@Test
	void playIsUnavailableWhileAWorldsRetryRunsAndUntilTheListShowsHowItEnded() throws Exception {
		String worldId = crashedSession("Survival");
		long listedBefore = retry.endedCount();
		try (BlockedUpload blocked = new BlockedUpload(cloud)) {
			CompletableFuture<Void> running = CompletableFuture.runAsync(this::run);
			blocked.awaitStarted();

			assertTrue(retry.blocksPlay(worldId, listedBefore));

			blocked.close();
			running.get(10, TimeUnit.SECONDS);
		}

		assertTrue(retry.blocksPlay(worldId, listedBefore), "the list on screen is older than the retry's end");
		assertFalse(retry.blocksPlay(worldId, retry.endedCount()), "listed again since");
	}

	@Test
	void playIsAvailableAgainAfterAFailedRetry() throws Exception {
		String worldId = crashedSession("Survival");
		cloud.failUploads();

		retry.run(coordinator, report);

		assertFalse(retry.blocksPlay(worldId, retry.endedCount()));
	}

	private void run() {
		try {
			retry.run(coordinator, report);
		} catch (IOException e) {
			throw new java.io.UncheckedIOException(e);
		}
	}
}
