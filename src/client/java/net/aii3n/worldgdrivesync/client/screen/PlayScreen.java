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
 * progress), then opens the world. Shows who is hosting if the Lock is someone else's, and offers
 * Take Over or Wait ({@link TakeOverScreen}) if that Lock is Stale.
 */
public class PlayScreen extends CoordinatorProgressScreen<PlayResult> {
	private final SharedWorldsScreen lastScreen;
	private final SharedWorld world;
	private final boolean takeOver;

	public PlayScreen(SharedWorldsScreen lastScreen, SharedWorld world) {
		this(lastScreen, world, false);
	}

	/** Takeover: like Play, but replaces a Stale Lock (the player already accepted the warning). */
	static PlayScreen takeOver(SharedWorldsScreen lastScreen, SharedWorld world) {
		return new PlayScreen(lastScreen, world, true);
	}

	private PlayScreen(SharedWorldsScreen lastScreen, SharedWorld world, boolean takeOver) {
		super(Component.translatable("world-gdrive-sync.play.title", world.name()), false);
		this.lastScreen = lastScreen;
		this.world = world;
		this.takeOver = takeOver;
	}

	@Override
	protected PlayResult run(SessionCoordinator coordinator) throws Exception {
		return takeOver ? coordinator.takeOver(world.id(), this) : coordinator.play(world.id(), this);
	}

	@Override
	protected void onSuccess(PlayResult result) {
		switch (result) {
			case PlayResult.Ready ready -> HostingSession.open(minecraft, world.id(), ready.localCopy(), lastScreen);
			case PlayResult.Refused refused -> minecraft.gui.setScreen(new AlertScreen(() -> minecraft.gui.setScreen(lastScreen),
					Component.translatable("world-gdrive-sync.play.refused.title"),
					Component.translatable("world-gdrive-sync.play.refused", world.name(), refused.heldBy().hostName())));
			case PlayResult.Stale stale -> minecraft.gui.setScreen(new TakeOverScreen(lastScreen, world, stale.heldBy()));
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
