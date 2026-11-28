package net.aii3n.worldgdrivesync.session;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * Ties a periodic upload to its world being hosted: once the world closes, the upload is given up so
 * that the final upload, which waits behind it, can start. No game types, so it can be tested on its
 * own.
 */
public final class WhileHosted {
	/** How often a waiting upload checks that the world is still hosted. */
	private static final long CHECK_EVERY_MILLIS = 100;

	private WhileHosted() {
	}

	/**
	 * An {@link OpenWorldCopier} that has the game thread run {@code copyOpenWorld}, and waits for it.
	 * It gives up at once if the world is not hosted, or stops being hosted before the game thread
	 * gets to the work (a world that has stopped may never run it), and after {@code timeout}
	 * likewise, or if its thread is interrupted. Work given up on never runs. Work already under way
	 * is always waited for, even by an interrupted thread (which stays interrupted), since the folder
	 * is deleted as soon as this returns.
	 *
	 * @param gameThread    runs work on the thread that owns the open world
	 * @param hosted        whether the world is still being hosted; asked from both threads
	 * @param copyOpenWorld saves the open world and copies it, on the game thread
	 */
	public static OpenWorldCopier copier(Executor gameThread, BooleanSupplier hosted, Duration timeout, OpenWorldCopier copyOpenWorld) {
		return folder -> {
			if (!hosted.getAsBoolean()) throw closed();
			// Set by whichever comes first: the game thread starting the work, or this thread giving up on it.
			AtomicBoolean claimed = new AtomicBoolean();
			CompletableFuture<Void> done = new CompletableFuture<>();
			gameThread.execute(() -> {
				if (!claimed.compareAndSet(false, true)) return;
				try {
					if (!hosted.getAsBoolean()) throw closed();
					copyOpenWorld.copyTo(folder);
					done.complete(null);
				} catch (Throwable e) {
					done.completeExceptionally(e);
				}
			});
			long deadline = System.nanoTime() + timeout.toNanos();
			boolean interrupted = false;
			try {
				while (true) {
					try {
						done.get(CHECK_EVERY_MILLIS, TimeUnit.MILLISECONDS);
						return;
					} catch (TimeoutException e) {
						if (!hosted.getAsBoolean() && claimed.compareAndSet(false, true)) throw closed();
						if (System.nanoTime() - deadline >= 0 && claimed.compareAndSet(false, true)) {
							throw new IOException("The world didn't save in time for upload", e);
						}
					} catch (ExecutionException e) {
						if (e.getCause() instanceof IOException failure) throw failure;
						throw new IOException("Couldn't save the world for upload", e.getCause());
					} catch (InterruptedException e) {
						interrupted = true;
						if (claimed.compareAndSet(false, true)) throw new IOException("Interrupted while saving the world for upload", e);
					}
				}
			} finally {
				if (interrupted) Thread.currentThread().interrupt();
			}
		};
	}

	/** A periodic upload, reporting to {@code progress}. */
	@FunctionalInterface
	public interface Upload<T> {
		T run(ProgressListener progress) throws IOException;
	}

	/**
	 * Runs a periodic upload, giving it up once the world is no longer hosted: while it is still
	 * packing, or sending with bytes left to send, so the Current Save is as it was. The thread
	 * running it is then interrupted, which ends a cloud call that is stalled or backing off without
	 * reporting progress; its next progress report throws too. With every byte sent the cloud store
	 * may already have replaced the Current Save, so from then on it is left to finish.
	 *
	 * <p>The interrupt never outlives this call, so the thread can go on to the final upload.
	 *
	 * @param hosted whether the world is still being hosted; asked from more than one thread
	 * @param next   told the upload's progress
	 * @return what {@code upload} returns
	 * @throws IOException as {@code upload} does, or because it was given up
	 */
	public static <T> T upload(BooleanSupplier hosted, ProgressListener next, Upload<T> upload) throws IOException {
		Attempt attempt = new Attempt(Thread.currentThread());
		Thread watcher = Thread.ofVirtual().name("WGDS-WhileHosted").start(() -> attempt.abandonWhenClosed(hosted));
		try {
			return upload.run((step, done, total) -> {
				boolean allSent = step == ProgressListener.Step.UPLOADING && done >= total;
				if (allSent ? !attempt.leaveToFinish() : !hosted.getAsBoolean() && attempt.abandon()) {
					throw new UncheckedIOException(closed());
				}
				next.onProgress(step, done, total);
			});
		} catch (IOException | RuntimeException e) {
			if (!attempt.abandoned()) throw e;
			IOException givenUp = closed();
			givenUp.initCause(e);
			throw givenUp;
		} finally {
			watcher.interrupt();
			attempt.end();
		}
	}

	/** One periodic upload on {@code thread}, and whether it has been given up. */
	private static final class Attempt {
		private final Thread thread;
		private boolean abandoned;
		/** Set once it may no longer be given up. */
		private boolean leftToFinish;
		private boolean over;

		Attempt(Thread thread) {
			this.thread = thread;
		}

		/** Runs on the watching thread until the upload is over or the world closes. */
		void abandonWhenClosed(BooleanSupplier hosted) {
			try {
				while (hosted.getAsBoolean()) Thread.sleep(CHECK_EVERY_MILLIS);
				abandon();
			} catch (InterruptedException e) {
				// The upload is over.
			}
		}

		/** Gives the upload up, interrupting it; false if it is too late for that. */
		synchronized boolean abandon() {
			if (leftToFinish || over) return false;
			if (!abandoned) {
				abandoned = true;
				thread.interrupt();
			}
			return true;
		}

		/** From now on the upload isn't given up; false if it already was. */
		synchronized boolean leaveToFinish() {
			if (abandoned) return false;
			leftToFinish = true;
			return true;
		}

		synchronized boolean abandoned() {
			return abandoned;
		}

		/** Called on the upload's own thread: no interrupt after this, and none of ours left behind. */
		synchronized void end() {
			over = true;
			if (abandoned) Thread.interrupted();
		}
	}

	private static IOException closed() {
		return new IOException("The world closed, so its periodic upload was given up");
	}
}
