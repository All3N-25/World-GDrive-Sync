package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.session.ProgressListener;
import net.aii3n.worldgdrivesync.session.SessionCoordinator;
import net.aii3n.worldgdrivesync.session.SharedWorld;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.nio.file.Path;

/** Shares one world: zips it and uploads it to Drive as Revision 1, showing progress. */
public class ShareProgressScreen extends CoordinatorProgressScreen<SharedWorld> implements ProgressListener {
	private final SharedWorldsScreen lastScreen;
	private final Path worldFolder;
	private final String name;

	public ShareProgressScreen(SharedWorldsScreen lastScreen, Path worldFolder, String name) {
		super(Component.translatable("world-gdrive-sync.share.progressTitle", name), true);
		this.lastScreen = lastScreen;
		this.worldFolder = worldFolder;
		this.name = name;
	}

	@Override
	protected SharedWorld run(SessionCoordinator coordinator) throws Exception {
		return coordinator.share(worldFolder, name, this);
	}

	@Override
	protected void onSuccess(SharedWorld result) {
		minecraft.gui.setScreen(lastScreen);
	}

	@Override
	protected Component failureMessage(String error) {
		return Component.translatable("world-gdrive-sync.share.failed", error);
	}

	@Override
	protected Screen afterFailure() {
		return lastScreen;
	}
}
