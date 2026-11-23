package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.client.HostingSession;
import net.aii3n.worldgdrivesync.session.PlayResult;
import net.aii3n.worldgdrivesync.session.SessionCoordinator;
import net.aii3n.worldgdrivesync.session.SharedWorld;
import net.minecraft.client.gui.screens.AlertScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Play: takes the Lock, downloads the Current Save if this computer's Local Copy is older (with
 * progress), then opens the world. Shows who is hosting if the Lock is someone else's.
 */
public class PlayScreen extends CoordinatorProgressScreen<PlayResult> {
	private final SharedWorldsScreen lastScreen;
	private final SharedWorld world;

	public PlayScreen(SharedWorldsScreen lastScreen, SharedWorld world) {
		super(Component.translatable("world-gdrive-sync.play.title", world.name()), false);
		this.lastScreen = lastScreen;
		this.world = world;
	}

	@Override
	protected PlayResult run(SessionCoordinator coordinator) throws Exception {
		return coordinator.play(world.id(), this);
	}

	@Override
	protected void onSuccess(PlayResult result) {
		switch (result) {
			case PlayResult.Ready ready -> HostingSession.open(minecraft, world.id(), ready.localCopy(), lastScreen);
			case PlayResult.Refused refused -> minecraft.gui.setScreen(new AlertScreen(() -> minecraft.gui.setScreen(lastScreen),
					Component.translatable("world-gdrive-sync.play.refused.title"),
					Component.translatable("world-gdrive-sync.play.refused", world.name(), refused.heldBy().hostName())));
		}
	}

	@Override
	protected Component failureMessage(String error) {
		return Component.translatable("world-gdrive-sync.play.failed", error);
	}

	@Override
	protected Screen afterFailure() {
		return lastScreen;
	}
}
