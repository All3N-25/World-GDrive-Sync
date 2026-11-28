package net.aii3n.worldgdrivesync.client.screen;

import net.aii3n.worldgdrivesync.session.Lock;
import net.aii3n.worldgdrivesync.session.SharedWorld;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.network.chat.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Play found a Stale Lock: Take Over or Wait. Take Over first warns that the previous Host's
 * unsaved progress may be lost, then runs the Takeover with progress ({@link PlayScreen}). Wait (or
 * Escape) goes back to the Shared Worlds screen and leaves the Lock untouched.
 */
public class TakeOverScreen extends ConfirmScreen {
	public TakeOverScreen(SharedWorldsScreen lastScreen, SharedWorld world, Lock stale) {
		super(choice -> {
			Minecraft minecraft = Minecraft.getInstance();
			minecraft.gui.setScreen(choice ? warning(lastScreen, world, stale) : lastScreen);
		},
				Component.translatable("world-gdrive-sync.takeOver.title"),
				Component.translatable("world-gdrive-sync.takeOver.message", world.name(), stale.hostName(),
						SharedWorldsScreen.duration(Duration.between(stale.lastHeartbeat(), Instant.now()))),
				Component.translatable("world-gdrive-sync.takeOver.takeOver"),
				Component.translatable("world-gdrive-sync.takeOver.wait"));
	}

	private static ConfirmScreen warning(SharedWorldsScreen lastScreen, SharedWorld world, Lock stale) {
		return new ConfirmScreen(proceed -> {
			Minecraft minecraft = Minecraft.getInstance();
			minecraft.gui.setScreen(proceed ? PlayScreen.takeOver(lastScreen, world) : lastScreen);
		},
				Component.translatable("world-gdrive-sync.takeOver.warning.title"),
				Component.translatable("world-gdrive-sync.takeOver.warning", stale.hostName()),
				Component.translatable("world-gdrive-sync.takeOver.takeOver"),
				Component.translatable("world-gdrive-sync.takeOver.cancel"));
	}
}
