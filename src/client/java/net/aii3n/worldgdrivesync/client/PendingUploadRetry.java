package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.aii3n.worldgdrivesync.session.ProgressListener;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

/**
 * Retries every Pending Upload on this computer once the game has started, so a Host whose final
 * upload failed doesn't have to remember. Runs on the network thread; a failure is only logged and
 * the Pending Upload stays on the Shared Worlds screen with its Retry button.
 */
final class PendingUploadRetry {
	private PendingUploadRetry() {
	}

	static void register() {
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			if (!GoogleConnection.get().account().isConnected()) return;
			SharedWorlds.get().run(coordinator -> {
				for (String worldId : coordinator.pendingUploads()) {
					try {
						coordinator.retryPendingUpload(worldId, ProgressListener.NONE);
						WorldGDriveSync.LOGGER.info("Uploaded the Pending Upload of Shared World {}", worldId);
					} catch (Exception e) {
						WorldGDriveSync.LOGGER.warn("Retrying the Pending Upload of Shared World {} failed", worldId, e);
					}
				}
				return null;
			}).exceptionally(e -> {
				WorldGDriveSync.LOGGER.warn("Couldn't check for Pending Uploads", e);
				return null;
			});
		});
	}
}
