package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.session.SessionCoordinator;
import net.aii3n.worldgdrivesync.session.SharedWorld;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Retry on the Shared Worlds screen: uploads this computer's Pending Upload as the next Revision,
 * then releases the Lock, with the same blocking progress as the final upload.
 */
public class RetryUploadScreen extends CoordinatorProgressScreen<Void> {
	private final Screen lastScreen;
	private final SharedWorld world;

	public RetryUploadScreen(Screen lastScreen, SharedWorld world) {
		super(Component.translatable("world-gdrive-sync.upload.title"), true);
		this.lastScreen = lastScreen;
		this.world = world;
	}

	@Override
	protected Void run(SessionCoordinator coordinator) throws Exception {
		coordinator.retryPendingUpload(world.id(), this);
		return null;
	}

	@Override
	protected void onSuccess(Void result) {
		minecraft.gui.setScreen(lastScreen);
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
