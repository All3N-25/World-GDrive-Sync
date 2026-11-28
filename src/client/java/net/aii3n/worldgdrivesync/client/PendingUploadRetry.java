package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.aii3n.worldgdrivesync.session.LaunchRetry;
import net.aii3n.worldgdrivesync.session.RetryResult;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;

/**
 * Retries every Pending Upload on this computer once the game has started, so a Host whose final
 * upload failed, or whose game crashed or was closed while hosting, doesn't have to remember. Runs
 * the {@link LaunchRetry} on the network thread and logs it; a failed one stays on the Shared Worlds
 * screen with its Retry button.
 */
final class PendingUploadRetry {
	private static final LaunchRetry.Report LOG = new LaunchRetry.Report() {
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
	};

	private PendingUploadRetry() {
	}

	static void register() {
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			// The network thread's first task, so it is over before anything there uses sharedsaves/,
			// the retry included. Needs no account.
			SharedWorlds.get().run(coordinator -> {
				coordinator.recoverLocalCopiesAndDeleteLeftovers();
				return null;
			}).exceptionally(e -> {
				WorldGDriveSync.LOGGER.warn("Couldn't clean up after earlier sessions in sharedsaves", e);
				return null;
			});
			if (!GoogleConnection.get().account().isConnected()) return;
			SharedWorlds.get().run(coordinator -> {
				SharedWorlds.get().launchRetry().run(coordinator, LOG);
				return null;
			}).exceptionally(e -> {
				WorldGDriveSync.LOGGER.warn("Couldn't check for Pending Uploads", e);
				return null;
			});
		});
	}
}
