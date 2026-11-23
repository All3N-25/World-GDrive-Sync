package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.session.Backup;
import net.aii3n.worldgdrivesync.session.RestoreResult;
import net.aii3n.worldgdrivesync.session.SessionCoordinator;
import net.aii3n.worldgdrivesync.session.SharedWorld;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Restores a Backup: the Current Save becomes a Backup and the chosen one the Current Save, at a
 * new Revision. Refused, with an explanation, while anyone holds a Lock (fresh or Stale).
 */
public class RestoreScreen extends CoordinatorProgressScreen<RestoreResult> {
	private final Screen sharedWorldsScreen;
	private final Screen backupsScreen;
	private final SharedWorld world;
	private final Backup backup;

	public RestoreScreen(Screen sharedWorldsScreen, Screen backupsScreen, SharedWorld world, Backup backup) {
		super(Component.translatable("world-gdrive-sync.restore.title", world.name()), false);
		this.sharedWorldsScreen = sharedWorldsScreen;
		this.backupsScreen = backupsScreen;
		this.world = world;
		this.backup = backup;
	}

	@Override
	protected RestoreResult run(SessionCoordinator coordinator) throws Exception {
		return coordinator.restoreBackup(world.id(), backup.id());
	}

	@Override
	protected void onSuccess(RestoreResult result) {
		switch (result) {
			case RestoreResult.Restored restored -> minecraft.gui.setScreen(sharedWorldsScreen);
			case RestoreResult.Refused refused -> minecraft.gui.setScreen(new AlertScreen(() -> minecraft.gui.setScreen(backupsScreen),
					Component.translatable("world-gdrive-sync.restore.refused.title"),
					Component.translatable("world-gdrive-sync.restore.refused", world.name(), refused.heldBy().hostName())));
		}
	}

	@Override
	protected Component failureMessage(String error) {
		return Component.translatable("world-gdrive-sync.restore.failed", error);
	}

	@Override
	protected Screen afterFailure() {
		return backupsScreen;
	}
}
