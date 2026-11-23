package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.drive.DriveCloudStore;
import com.google.common.base.Suppliers;
import net.aii3n.worldgdrivesync.session.Player;
import net.aii3n.worldgdrivesync.session.SessionCoordinator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.time.InstantSource;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;

/**
 * The game's session coordinator, wired to Google Drive, the system clock and
 * {@code <gameDir>/sharedsaves/} (ADR-0002), plus the one background thread every Drive call
 * runs on, so the render thread never waits on the network.
 */
public final class SharedWorlds {
	private static @Nullable SharedWorlds instance;

	private final Supplier<SessionCoordinator> coordinator;
	private final ExecutorService network = Executors.newSingleThreadExecutor(task -> {
		Thread thread = new Thread(task, "WGDS-Network");
		thread.setDaemon(true);
		return thread;
	});

	private SharedWorlds(Supplier<SessionCoordinator> coordinator) {
		this.coordinator = coordinator;
	}

	static void init() {
		Path localCopies = FabricLoader.getInstance().getGameDir().resolve("sharedsaves");
		// Built on first use: the signed-in Minecraft profile isn't guaranteed to exist during mod init.
		instance = new SharedWorlds(Suppliers.memoize(() -> {
			User user = Minecraft.getInstance().getUser();
			return new SessionCoordinator(new DriveCloudStore(GoogleConnection.get().account()), InstantSource.system(),
					localCopies, new Player(user.getProfileId().toString(), user.getName()));
		}));
	}

	public static SharedWorlds get() {
		if (instance == null) throw new IllegalStateException("SharedWorlds used before client init");
		return instance;
	}

	/** Runs {@code task} with the coordinator on the network thread. */
	public <T> CompletableFuture<T> run(CoordinatorTask<T> task) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				return task.call(coordinator.get());
			} catch (Exception e) {
				throw new CompletionException(e);
			}
		}, network);
	}

	@FunctionalInterface
	public interface CoordinatorTask<T> {
		T call(SessionCoordinator coordinator) throws Exception;
	}

	/** The message to show the player for a failed {@link #run} future. */
	public static String describe(Throwable error) {
		Throwable cause = error;
		while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}
}
