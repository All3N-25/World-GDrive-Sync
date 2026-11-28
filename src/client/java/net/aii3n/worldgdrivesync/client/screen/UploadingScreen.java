package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.session.SessionCoordinator;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * The blocking "Uploading Shared World… don't close the game" screen after save-and-quit: uploads
 * the world as the next Revision, then releases the Lock.
 */
public class UploadingScreen extends CoordinatorProgressScreen<Void> {
	private final String worldId;
	private final Screen next;

	public UploadingScreen(String worldId, Screen next) {
		super(Component.translatable("world-gdrive-sync.upload.title"), true);
		this.worldId = worldId;
		this.next = next;
	}

	@Override
	protected Void run(SessionCoordinator coordinator) throws Exception {
		coordinator.quit(worldId, this);
		return null;
	}

	@Override
	protected void onSuccess(Void result) {
		minecraft.gui.setScreen(next);
	}

	@Override
	protected Component failureMessage(String error) {
		return Component.translatable("world-gdrive-sync.upload.failed", error);
	}

	@Override
	protected Component notEnoughSpaceMessage(Component notEnoughSpace) {
		return Component.translatable("world-gdrive-sync.upload.noSpace", notEnoughSpace);
	}

	@Override
	protected Screen afterFailure() {
		return next;
	}
}
