package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.aii3n.worldgdrivesync.session.ProgressListener;
import net.aii3n.worldgdrivesync.session.WorldMetadata;
import net.aii3n.worldgdrivesync.session.WorldZip;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Uploads the hosted Shared World now and then while it is being played. Once a minute (wall clock,
 * so a paused game still counts) it asks the coordinator, on the network thread, whether a periodic
 * upload is due; if so the integrated server saves the world and copies it on its own thread (a
 * brief hitch, but a consistent copy), and the copy is zipped and uploaded on the network thread.
 * Nobody is kicked and the Lock stays held. A failed upload is logged and retried a few minutes
 * later; the final upload on save-and-quit happens regardless.
 */
final class PeriodicUpload {
	private static final Duration CHECK_EVERY = Duration.ofMinutes(1);
	private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(5);
	private static final long SAVE_TIMEOUT_MINUTES = 2;

	// Client thread only.
	private static Instant nextCheck = Instant.MIN;
	private static boolean inFlight;

	private PeriodicUpload() {
	}

	static void register() {
		ClientTickEvents.END_CLIENT_TICK.register(PeriodicUpload::onEndTick);
	}

	// Client thread.
	private static void onEndTick(Minecraft minecraft) {
		String worldId = HostingSession.hostedWorldId();
		MinecraftServer server = minecraft.getSingleplayerServer();
		if (worldId == null || server == null || inFlight) return;
		Instant now = Instant.now();
		if (now.isBefore(nextCheck)) return;
		nextCheck = now.plus(CHECK_EVERY);
		inFlight = true;

		SharedWorlds.get().run(coordinator -> coordinator.periodicUploadDue(worldId)
				? coordinator.periodicUpload(worldId, folder -> copyOpenWorld(server, worldId, folder), ProgressListener.NONE)
				: null
		).whenCompleteAsync((WorldMetadata uploaded, Throwable error) -> {
			inFlight = false;
			if (error != null) {
				WorldGDriveSync.LOGGER.warn("Periodic upload of Shared World {} failed: {}", worldId, SharedWorlds.describe(error), error);
				nextCheck = Instant.now().plus(RETRY_AFTER_FAILURE);
			} else if (uploaded != null) {
				WorldGDriveSync.LOGGER.info("Periodic upload of Shared World {} done: Revision {}", worldId, uploaded.revision());
			}
		}, minecraft);
	}

	/** Network thread: has the server thread save the world and copy it into {@code folder}, and waits. */
	private static void copyOpenWorld(MinecraftServer server, String worldId, Path folder) throws IOException {
		try {
			server.submit(() -> {
				if (!server.isRunning() || !worldId.equals(HostingSession.hostedWorldId())) {
					throw new UncheckedIOException(new IOException("The world closed before it could be saved"));
				}
				server.saveEverything(true, true, true);
				try {
					WorldZip.copy(server.getWorldPath(LevelResource.ROOT), folder);
				} catch (IOException e) {
					throw new UncheckedIOException(e);
				}
				return null;
			}).get(SAVE_TIMEOUT_MINUTES, TimeUnit.MINUTES);
		} catch (ExecutionException e) {
			if (e.getCause() instanceof UncheckedIOException unchecked) throw unchecked.getCause();
			throw new IOException("Couldn't save the world for upload", e.getCause());
		} catch (TimeoutException e) {
			throw new IOException("The world didn't save in time for upload", e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IOException("Interrupted while saving the world for upload", e);
		}
	}
}
