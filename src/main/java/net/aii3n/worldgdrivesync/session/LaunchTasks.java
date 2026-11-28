package net.aii3n.worldgdrivesync.session;

import java.util.concurrent.CompletableFuture;

/**
 * What the network thread does when the game starts, and in which order: first
 * {@link SessionCoordinator#recoverLocalCopiesAndDeleteLeftovers}, then, if a Google account is
 * connected, the {@link LaunchRetry}. Queued before anything else runs there, so the clean-up is
 * over before anything uses the Local Copy directory, the retry included.
 */
public final class LaunchTasks {
	/** Told how the launch tasks go, on the network thread. */
	public interface Report extends LaunchRetry.Report {
		/** The clean-up of the Local Copy directory couldn't be done; the retry still runs. */
		void cleanUpFailed(Throwable failure);

		/** The Pending Uploads couldn't be looked up or retried at all. */
		void retryFailed(Throwable failure);
	}

	private LaunchTasks() {
	}

	/**
	 * Queues the launch tasks on {@code runner}'s network thread. The clean-up needs no account and
	 * no network.
	 *
	 * @return done once both are over, however they went
	 */
	public static CompletableFuture<Void> queue(CoordinatorRunner runner, boolean accountConnected, LaunchRetry retry, Report report) {
		CompletableFuture<Void> cleanUp = runner.<Void>run(coordinator -> {
			coordinator.recoverLocalCopiesAndDeleteLeftovers();
			return null;
		}).exceptionally(failure -> {
			report.cleanUpFailed(failure);
			return null;
		});
		if (!accountConnected) return cleanUp;
		CompletableFuture<Void> retried = runner.<Void>run(coordinator -> {
			retry.run(coordinator, report);
			return null;
		}).exceptionally(failure -> {
			report.retryFailed(failure);
			return null;
		});
		return CompletableFuture.allOf(cleanUp, retried);
	}
}
