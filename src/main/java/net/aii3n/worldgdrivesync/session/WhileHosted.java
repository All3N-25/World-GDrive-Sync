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
	 * likewise. Work given up on never runs. Work already under way is always waited for, since the
	 * folder is deleted as soon as this returns.
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
					claimed.set(true);
					Thread.currentThread().interrupt();
					throw new IOException("Interrupted while saving the world for upload", e);
				}
			}
		};
	}

	/**
	 * Gives the upload up, by throwing {@link UncheckedIOException} from a progress report, once the
	 * world is no longer hosted: while it is still packing, or sending with bytes left to send, so the
	 * Current Save is as it was. With every byte sent the cloud store may already have replaced the
	 * Current Save, so from then on it is left to finish.
	 */
	public static ProgressListener progress(BooleanSupplier hosted, ProgressListener next) {
		return (step, done, total) -> {
			boolean unsent = step != ProgressListener.Step.UPLOADING || done < total;
			if (unsent && !hosted.getAsBoolean()) throw new UncheckedIOException(closed());
			next.onProgress(step, done, total);
		};
	}

	private static IOException closed() {
		return new IOException("The world closed, so its periodic upload was given up");
	}
}
