package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.aii3n.worldgdrivesync.session.NotEnoughSpaceException;
import net.aii3n.worldgdrivesync.session.OpenWorldCopier;
import net.aii3n.worldgdrivesync.session.ProgressListener;
import net.aii3n.worldgdrivesync.session.WhileHosted;
import net.aii3n.worldgdrivesync.session.WorldMetadata;
import net.aii3n.worldgdrivesync.session.WorldZip;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.function.BooleanSupplier;

/**
 * Uploads the hosted Shared World now and then while it is being played. Once a minute (wall clock,
 * so a paused game still counts) it asks the coordinator, on the network thread, whether a periodic
 * upload is due; if so the integrated server saves the world and copies it on its own thread (a
 * brief hitch, but a consistent copy), and the copy is zipped and uploaded on the network thread.
 * Nobody is kicked and the Lock stays held. A failed upload is logged and retried a few minutes
 * later; the final upload on save-and-quit happens regardless. Once the world closes, an unfinished
 * periodic upload is given up (see {@link WhileHosted}) so the final upload needn't wait for it.
 */
final class PeriodicUpload {
	private static final Duration CHECK_EVERY = Duration.ofMinutes(1);
	private static final Duration RETRY_AFTER_FAILURE = Duration.ofMinutes(5);
	private static final Duration SAVE_TIMEOUT = Duration.ofMinutes(2);

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

		// False from the moment save-and-quit halts the server.
		BooleanSupplier hosted = () -> server.isRunning() && worldId.equals(HostingSession.hostedWorldId());
		OpenWorldCopier copier = WhileHosted.copier(server, hosted, SAVE_TIMEOUT, folder -> saveWorldInto(server, folder));
		SharedWorlds.get().run(coordinator -> WhileHosted.upload(hosted, ProgressListener.NONE,
				progress -> coordinator.periodicUploadDue(worldId) ? coordinator.periodicUpload(worldId, copier, progress) : null)
		).whenCompleteAsync((WorldMetadata uploaded, Throwable error) -> {
			inFlight = false;
			if (error != null) {
				WorldGDriveSync.LOGGER.warn("Periodic upload of Shared World {} failed: {}", worldId, SharedWorlds.describe(error), error);
				nextCheck = Instant.now().plus(RETRY_AFTER_FAILURE);
				NotEnoughSpaceException.in(error).ifPresent(refused -> StorageMessages.showNotEnoughSpaceToast(minecraft, refused));
			} else if (uploaded != null) {
				WorldGDriveSync.LOGGER.info("Periodic upload of Shared World {} done: Revision {}", worldId, uploaded.revision());
			}
		}, minecraft);
	}

	/** Server thread. */
	private static void saveWorldInto(MinecraftServer server, Path folder) throws IOException {
		server.saveEverything(true, true, true);
		WorldZip.copyForUpload(server.getWorldPath(LevelResource.ROOT), folder);
	}
}
