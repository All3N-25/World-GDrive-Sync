package net.aii3n.worldgdrivesync.session;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The retry of every Pending Upload when the game starts, and how far it has got with each Shared
 * World, for the Shared Worlds screen to show. {@link #run} is called once, on the network thread;
 * everything else may be read from any thread while it runs.
 */
public final class LaunchRetry {
	/** What the retry is doing with a world it hasn't finished. */
	public sealed interface State {
		/** Another world's Pending Upload is going up first. */
		record Waiting() implements State {
		}

		/**
		 * This world's Pending Upload is going up.
		 *
		 * @param percent how much of the zip has been sent; 0 while it is still being packed
		 */
		record Uploading(int percent) implements State {
		}

		/**
		 * Someone uploaded a newer Revision, so this world's Pending Upload is now an Unmerged Backup
		 * and that Revision is being downloaded in its place.
		 */
		record Downloading() implements State {
		}
	}

	/** Told about each world's retry, on the thread that runs it. */
	public interface Report {
		void started(String worldId);

		void succeeded(String worldId, RetryResult result);

		void failed(String worldId, Exception failure);
	}

	private final Map<String, State> states = new ConcurrentHashMap<>();
	/** For each world whose retry has ended, what {@link #endedCount} became when it did. */
	private final Map<String, Long> ended = new ConcurrentHashMap<>();
	private volatile long endedCount;

	/**
	 * Retries each of {@link SessionCoordinator#pendingUploads} in turn. A world whose retry fails
	 * keeps its Pending Upload, and the others are still tried.
	 *
	 * @throws IOException if the Pending Uploads couldn't be looked up
	 */
	public void run(SessionCoordinator coordinator, Report report) throws IOException {
		List<String> worlds = coordinator.pendingUploads();
		worlds.forEach(worldId -> states.put(worldId, new State.Waiting()));
		try {
			for (String worldId : worlds) retry(coordinator, worldId, report);
		} finally {
			// Only left over if something unexpected stopped the loop; no world may stay unplayable.
			worlds.stream().filter(states::containsKey).forEach(this::end);
		}
	}

	private void retry(SessionCoordinator coordinator, String worldId, Report report) {
		states.put(worldId, new State.Uploading(0));
		report.started(worldId);
		try {
			RetryResult result = coordinator.retryPendingUpload(worldId, (step, done, total) -> {
				switch (step) {
					case ZIPPING -> {
					}
					case UPLOADING -> {
						if (total > 0) states.put(worldId, new State.Uploading((int) Math.min(100, done * 100 / total)));
					}
					case DOWNLOADING, UNZIPPING -> states.put(worldId, new State.Downloading());
				}
			});
			report.succeeded(worldId, result);
		} catch (IOException | RuntimeException e) {
			report.failed(worldId, e);
		} finally {
			end(worldId);
		}
	}

	private void end(String worldId) {
		long count = endedCount + 1; // only the thread in run() writes it
		// In this order, so a reader that sees the state gone also sees that the retry ended.
		ended.put(worldId, count);
		endedCount = count;
		states.remove(worldId);
	}

	/** What the retry is doing with this world; empty if it has nothing (left) to do with it. */
	public Optional<State> state(String worldId) {
		return Optional.ofNullable(states.get(worldId));
	}

	/** How many worlds' retries have ended. When it changes, the list of Shared Worlds is out of date. */
	public long endedCount() {
		return endedCount;
	}

	/**
	 * Whether Play has to be unavailable for a world, on a list of Shared Worlds asked for when
	 * {@link #endedCount} was {@code listedAt}: its retry hasn't ended, or the list is older than
	 * that end (see {@link #endedSince}).
	 */
	public boolean blocksPlay(String worldId, long listedAt) {
		return states.containsKey(worldId) || endedSince(worldId, listedAt);
	}

	/**
	 * Whether this world's retry ended after {@link #endedCount} was {@code mark}: a list of Shared
	 * Worlds asked for at that point may show the world as it was before.
	 */
	public boolean endedSince(String worldId, long mark) {
		return ended.getOrDefault(worldId, 0L) > mark;
	}
}
