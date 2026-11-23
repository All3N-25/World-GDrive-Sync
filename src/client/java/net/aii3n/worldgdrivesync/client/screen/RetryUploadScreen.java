package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.session.RetryResult;
import net.aii3n.worldgdrivesync.session.SessionCoordinator;
import net.aii3n.worldgdrivesync.session.SharedWorld;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Retry on the Shared Worlds screen: uploads this computer's Pending Upload as the next Revision,
 * then releases the Lock, with the same blocking progress as the final upload. If someone uploaded
 * a newer Revision meanwhile, the Pending Upload becomes an Unmerged Backup instead, and says so.
 */
public class RetryUploadScreen extends CoordinatorProgressScreen<RetryResult> {
	private final Screen lastScreen;
	private final SharedWorld world;

	public RetryUploadScreen(Screen lastScreen, SharedWorld world) {
		super(Component.translatable("world-gdrive-sync.upload.title"), true);
		this.lastScreen = lastScreen;
		this.world = world;
	}

	@Override
	protected RetryResult run(SessionCoordinator coordinator) throws Exception {
		return coordinator.retryPendingUpload(world.id(), this);
	}

	@Override
	protected void onSuccess(RetryResult result) {
		switch (result) {
			case RetryResult.Uploaded uploaded -> minecraft.gui.setScreen(lastScreen);
			case RetryResult.KeptAsUnmergedBackup unmerged -> minecraft.gui.setScreen(new AlertScreen(() -> minecraft.gui.setScreen(lastScreen),
					Component.translatable("world-gdrive-sync.play.unmerged.title"),
					Component.translatable("world-gdrive-sync.retry.unmerged", world.name())));
		}
	}

	@Override
	protected Component failureMessage(String error) {
		return Component.translatable("world-gdrive-sync.retry.failed", error);
	}

	@Override
	protected Screen afterFailure() {
		return lastScreen;
	}
}
