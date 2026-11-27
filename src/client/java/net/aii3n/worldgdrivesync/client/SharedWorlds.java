package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.aii3n.worldgdrivesync.drive.DriveCloudStore;
import net.aii3n.worldgdrivesync.settings.SettingsFile;
import com.google.common.base.Suppliers;
import net.aii3n.worldgdrivesync.session.CoordinatorRunner;
import net.aii3n.worldgdrivesync.session.CoordinatorRunner.CoordinatorTask;
import net.aii3n.worldgdrivesync.session.LaunchRetry;
import net.aii3n.worldgdrivesync.session.Player;
import net.aii3n.worldgdrivesync.session.SessionClock;
import net.aii3n.worldgdrivesync.session.SessionCoordinator;
import net.aii3n.worldgdrivesync.session.SharedWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.User;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/**
 * The game's session coordinator, wired to Google Drive, the system clock and
 * {@code <gameDir>/sharedsaves/} (ADR-0002), plus the background threads Drive calls run on (see
 * {@link CoordinatorRunner}), so the render thread never waits on the network. Also holds the
 * player's settings and how far the launch retry of Pending Uploads has got.
 */
public final class SharedWorlds {
	private static @Nullable SharedWorlds instance;

	private final Supplier<SessionCoordinator> coordinator;
	private final SettingsFile settings;
	private final CoordinatorRunner runner;
	private final LaunchRetry launchRetry = new LaunchRetry();

	private SharedWorlds(Supplier<SessionCoordinator> coordinator, SettingsFile settings) {
		this.coordinator = coordinator;
		this.settings = settings;
		this.runner = new CoordinatorRunner(coordinator);
	}

	static void init() {
		Path localCopies = FabricLoader.getInstance().getGameDir().resolve("sharedsaves");
		SettingsFile settings = new SettingsFile(
				FabricLoader.getInstance().getConfigDir().resolve(WorldGDriveSync.MOD_ID).resolve("settings.properties"));
		// Built on first use: the signed-in Minecraft profile isn't guaranteed to exist during mod init.
		instance = new SharedWorlds(Suppliers.memoize(() -> {
			User user = Minecraft.getInstance().getUser();
			return new SessionCoordinator(new DriveCloudStore(GoogleConnection.get().account()), SessionClock.system(),
					localCopies, new Player(user.getProfileId().toString(), user.getName()), settings::current);
		}), settings);
	}

	/** The player's settings, saved in the mod's config folder. */
	public SettingsFile settings() {
		return settings;
	}

	public static SharedWorlds get() {
		if (instance == null) throw new IllegalStateException("SharedWorlds used before client init");
		return instance;
	}

	/**
	 * The coordinator itself, for the few calls made off the network thread (Heartbeats, which must
	 * not wait behind a long upload). Blocks; never call it on the render thread.
	 */
	SessionCoordinator coordinator() {
		return coordinator.get();
	}

	/** Runs {@code task} with the coordinator on the network thread. */
	public <T> CompletableFuture<T> run(CoordinatorTask<T> task) {
		return runner.run(task);
	}

	/** Every Shared World with its status. Never waits behind {@link #run} tasks such as uploads. */
	public CompletableFuture<List<SharedWorld>> listSharedWorlds() {
		return runner.listSharedWorlds();
	}

	/** How far the launch retry of Pending Uploads has got; safe to read on the render thread. */
	public LaunchRetry launchRetry() {
		return launchRetry;
	}

	/** The message to show the player for a failed {@link #run} future. */
	public static String describe(Throwable error) {
		Throwable cause = error;
		while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
		String message = cause.getMessage();
		return message == null || message.isBlank() ? cause.getClass().getSimpleName() : message;
	}
}
