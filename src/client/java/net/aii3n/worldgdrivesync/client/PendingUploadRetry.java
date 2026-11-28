package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.aii3n.worldgdrivesync.session.LaunchTasks;
import net.aii3n.worldgdrivesync.session.RetryResult;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

/**
 * Retries every Pending Upload on this computer once the game has started, so a Host whose final
 * upload failed, or whose game crashed or was closed while hosting, doesn't have to remember. Queues
 * the {@link LaunchTasks} and logs them; a failed retry stays on the Shared Worlds screen with its
 * Retry button.
 */
final class PendingUploadRetry {
	private static final LaunchTasks.Report LOG = new LaunchTasks.Report() {
		@Override
		public void started(String worldId) {
			WorldGDriveSync.LOGGER.info("Retrying the Pending Upload of Shared World {}", worldId);
		}

		@Override
		public void succeeded(String worldId, RetryResult result) {
			switch (result) {
				case RetryResult.Uploaded uploaded -> WorldGDriveSync.LOGGER.info(
						"Uploaded the Pending Upload of Shared World {} as Revision {}", worldId, uploaded.current().revision());
				case RetryResult.KeptAsUnmergedBackup unmerged -> WorldGDriveSync.LOGGER.info(
						"Someone uploaded a newer Revision of Shared World {}, so the Pending Upload was kept as Unmerged Backup {}",
						worldId, unmerged.backup().id());
			}
		}

		@Override
		public void failed(String worldId, Exception failure) {
			WorldGDriveSync.LOGGER.warn("Retrying the Pending Upload of Shared World {} failed", worldId, failure);
		}

		@Override
		public void cleanUpFailed(Throwable failure) {
			WorldGDriveSync.LOGGER.warn("Couldn't clean up after earlier sessions in sharedsaves", failure);
		}

		@Override
		public void retryFailed(Throwable failure) {
			WorldGDriveSync.LOGGER.warn("Couldn't check for Pending Uploads", failure);
		}
	};

	private PendingUploadRetry() {
	}

	static void register() {
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> LaunchTasks.queue(SharedWorlds.get().runner(),
				GoogleConnection.get().account().isConnected(), SharedWorlds.get().launchRetry(), LOG));
	}
}
