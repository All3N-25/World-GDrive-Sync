package net.aii3n.worldgdrivesync.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WhileHostedTest {
	private static final Path FOLDER = Path.of("staging");
	private static final Duration SAVE_TIMEOUT = Duration.ofMinutes(2);
	private static final Duration AT_ONCE = Duration.ofSeconds(5);

	/** What was handed to the game thread; it runs only when a test runs it. */
	private final List<Runnable> gameThread = new ArrayList<>();
	private final List<Path> copied = new ArrayList<>();

	@Test
	void aWorldNoLongerHostedIsGivenUpOnWithoutWaitingForTheGameThread() {
		OpenWorldCopier copier = WhileHosted.copier(gameThread::add, () -> false, SAVE_TIMEOUT, copied::add);

		assertTimeoutPreemptively(AT_ONCE, () -> assertThrows(IOException.class, () -> copier.copyTo(FOLDER)));

		assertEquals(List.of(), copied);
	}

	@Test
	void aWorldThatClosesBeforeTheGameThreadGetsToItIsGivenUpOnAndNeverCopied() {
		AtomicBoolean hosted = new AtomicBoolean(true);
		// Takes the work just as the world closes, and doesn't run it.
		Executor closing = work -> {
			gameThread.add(work);
			hosted.set(false);
		};
		OpenWorldCopier copier = WhileHosted.copier(closing, hosted::get, SAVE_TIMEOUT, copied::add);

		assertTimeoutPreemptively(AT_ONCE, () -> assertThrows(IOException.class, () -> copier.copyTo(FOLDER)));
		hosted.set(true); // even if it is hosted again by then
		gameThread.forEach(Runnable::run);

		assertEquals(List.of(), copied, "its folder is gone by now");
	}

	@Test
	void aHostedWorldIsCopiedOnTheGameThread() {
		OpenWorldCopier copier = WhileHosted.copier(work -> new Thread(work, "game").start(), () -> true, SAVE_TIMEOUT,
				folder -> copied.add(Path.of(Thread.currentThread().getName()).resolve(folder)));

		assertTimeoutPreemptively(AT_ONCE, () -> copier.copyTo(FOLDER));

		assertEquals(List.of(Path.of("game", "staging")), copied);
	}

	@Test
	void aFailedCopyFailsTheUpload() {
		IOException full = new IOException("Disk full");
		OpenWorldCopier copier = WhileHosted.copier(Runnable::run, () -> true, SAVE_TIMEOUT, folder -> {
			throw full;
		});

		assertSame(full, assertThrows(IOException.class, () -> copier.copyTo(FOLDER)));
	}

	@Test
	void aCopyAlreadyUnderWayIsWaitedForEvenIfTheWorldClosesOrTimeRunsOut() throws Exception {
		AtomicBoolean hosted = new AtomicBoolean(true);
		CountDownLatch underWay = new CountDownLatch(1);
		CountDownLatch finish = new CountDownLatch(1);
		OpenWorldCopier copier = WhileHosted.copier(work -> new Thread(work, "game").start(), hosted::get, Duration.ofMillis(50), folder -> {
			underWay.countDown();
			try {
				finish.await();
			} catch (InterruptedException e) {
				throw new InterruptedIOException();
			}
			copied.add(folder);
		});
		CompletableFuture<Void> upload = CompletableFuture.runAsync(() -> assertDoesNotThrow(() -> copier.copyTo(FOLDER)));
		assertTrue(underWay.await(5, TimeUnit.SECONDS));

		hosted.set(false);

		assertThrows(TimeoutException.class, () -> upload.get(500, TimeUnit.MILLISECONDS), "the folder is still being written");
		finish.countDown();
		upload.get(5, TimeUnit.SECONDS);
		assertEquals(List.of(FOLDER), copied);
	}

	@ParameterizedTest
	@EnumSource(names = {"ZIPPING", "UPLOADING"})
	void aPeriodicUploadStillPackingOrSendingWhenTheWorldClosesMakesWayForTheFinalUpload(ProgressListener.Step closingDuring,
			@TempDir Path saves, @TempDir Path sharedsaves) throws Exception {
		InMemoryCloudStore cloud = new InMemoryCloudStore();
		SessionCoordinator coordinator = new SessionCoordinator(cloud, new TestClock(Instant.parse("2026-11-24T18:00:00Z")),
				sharedsaves, new Player("uuid-alice", "Alice"));
		Files.writeString(saves.resolve("level.dat"), "level data");
		Files.writeString(saves.resolve("icon.png"), "a second file to pack");
		SharedWorld shared = coordinator.share(saves, "Survival", ProgressListener.NONE);
		Path local = ((PlayResult.Ready) coordinator.play(shared.id(), ProgressListener.NONE)).localCopy().folder();
		AtomicBoolean hosted = new AtomicBoolean(true);
		List<ProgressListener.Step> reported = new ArrayList<>();
		ProgressListener closes = (step, done, total) -> {
			reported.add(step);
			if (step == closingDuring) hosted.set(false);
		};
		OpenWorldCopier copier = folder -> WorldZip.copyForUpload(local, folder);

		assertThrows(UncheckedIOException.class, () -> coordinator.periodicUpload(shared.id(), copier,
				WhileHosted.progress(hosted::get, closes)));

		assertEquals(1, coordinator.listSharedWorlds().getFirst().revision(), "nothing replaced the Current Save");
		assertEquals(closingDuring, reported.getLast(), "no further than where it was when the world closed");
		assertEquals(1, reported.stream().filter(closingDuring::equals).count(), "no more work once it had closed");
		assertEquals(2, coordinator.quit(shared.id(), ProgressListener.NONE).revision());
	}

	@Test
	void aPeriodicUploadWhoseBytesAreAllSentIsFinishedEvenIfTheWorldHasClosed() {
		List<Long> reported = new ArrayList<>();
		ProgressListener progress = WhileHosted.progress(() -> false, (step, done, total) -> reported.add(done));

		progress.onProgress(ProgressListener.Step.UPLOADING, 10, 10);

		assertEquals(List.of(10L), reported, "the cloud store may already have made it the Current Save");
	}

	@Test
	void aGameThreadThatNeverGetsToItIsGivenUpOnAfterTheSaveTimeout() {
		OpenWorldCopier copier = WhileHosted.copier(gameThread::add, () -> true, Duration.ofMillis(200), copied::add);

		assertTimeoutPreemptively(AT_ONCE, () -> assertThrows(IOException.class, () -> copier.copyTo(FOLDER)));
		gameThread.forEach(Runnable::run);

		assertEquals(List.of(), copied);
	}
}
