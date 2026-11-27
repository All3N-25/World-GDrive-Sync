package net.aii3n.worldgdrivesync.session;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.function.Supplier;

/** Runs coordinator calls off the caller's thread, so the render thread never waits on the network. */
public final class CoordinatorRunner {
	private final Supplier<SessionCoordinator> coordinator;
	private final ExecutorService network = Executors.newSingleThreadExecutor(daemon("WGDS-Network"));

	/** A pool, so a listing stuck on a slow connection doesn't hold up the next one. */
	private final ExecutorService listing = Executors.newCachedThreadPool(daemon("WGDS-Listing"));

	public CoordinatorRunner(Supplier<SessionCoordinator> coordinator) {
		this.coordinator = coordinator;
	}

	/** Runs {@code task} with the coordinator on the network thread, one task at a time. */
	public <T> CompletableFuture<T> run(CoordinatorTask<T> task) {
		return supply(task, network);
	}

	/**
	 * {@link SessionCoordinator#listSharedWorlds}, off the network thread: it never waits behind an
	 * upload or any other {@link #run} task.
	 */
	public CompletableFuture<List<SharedWorld>> listSharedWorlds() {
		return supply(SessionCoordinator::listSharedWorlds, listing);
	}

	private <T> CompletableFuture<T> supply(CoordinatorTask<T> task, ExecutorService thread) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return task.call(coordinator.get());
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, thread);
	}

	private static ThreadFactory daemon(String name) {
		return task -> {
			Thread thread = new Thread(task, name);
			thread.setDaemon(true);
			return thread;
		};
	}

	@FunctionalInterface
	public interface CoordinatorTask<T> {
		T call(SessionCoordinator coordinator) throws Exception;
	}
}
