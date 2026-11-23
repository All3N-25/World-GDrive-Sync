package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.aii3n.worldgdrivesync.client.screen.SharedWorldsScreen;
import net.aii3n.worldgdrivesync.client.screen.UploadingScreen;
import net.aii3n.worldgdrivesync.session.LocalCopy;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

import java.nio.file.Path;

/**
 * Tracks the Shared World this player is hosting, from Play to save-and-quit, and starts the final
 * upload once the world has closed.
 *
 * <p>States: {@link #open} records the world being opened; {@code SERVER_STARTED} for that folder
 * makes it hosted; {@code SERVER_STOPPED} (the world is fully saved and closed) makes the final
 * upload due; the next client tick with no world loaded shows {@link UploadingScreen}. This catches
 * every way out of the world (pause menu, hardcore death screen), but not closing the game or a
 * crash: those leave the Lock held. Later tickets hook Heartbeats and periodic uploads in here while
 * {@link #hosting} is set.
 */
public final class HostingSession {
	/** A Shared World being opened or hosted. */
	private record Session(String worldId, Path folder) {
	}

	private static @Nullable Session opening;
	private static @Nullable Session hosting;
	private static volatile @Nullable Session finalUploadDue;

	private HostingSession() {
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(HostingSession::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPED.register(HostingSession::onServerStopped);
		ClientTickEvents.END_CLIENT_TICK.register(HostingSession::onEndTick);
	}

	/**
	 * Opens the Local Copy this player now holds the Lock for. Client thread only. If the player backs
	 * out before the world loads, the Lock is released and {@code onCancel} is shown.
	 */
	public static void open(Minecraft minecraft, String worldId, LocalCopy localCopy, Screen onCancel) {
		Session session = new Session(worldId, normalize(localCopy.folder()));
		opening = session;
		SharedWorldLauncher.open(minecraft, localCopy, () -> {
			if (opening == session) opening = null;
			SharedWorlds.get().run(coordinator -> {
				coordinator.leaveWithoutPlaying(worldId);
				return null;
			}).exceptionally(e -> {
				WorldGDriveSync.LOGGER.warn("Couldn't release the Lock on {}", worldId, e);
				return null;
			});
			minecraft.gui.setScreen(onCancel);
		});
	}

	// Server thread.
	private static void onServerStarted(MinecraftServer server) {
		Session session = opening;
		if (session == null) return;
		if (normalize(server.getWorldPath(LevelResource.ROOT)).equals(session.folder())) {
			hosting = session;
			opening = null;
		}
	}

	// Server thread, after the world is saved and closed.
	private static void onServerStopped(MinecraftServer server) {
		Session session = hosting;
		if (session == null) return;
		hosting = null;
		finalUploadDue = session;
	}

	// Client thread.
	private static void onEndTick(Minecraft minecraft) {
		Session due = finalUploadDue;
		if (due == null || minecraft.level != null || minecraft.hasSingleplayerServer()) return;
		finalUploadDue = null;
		minecraft.gui.setScreen(new UploadingScreen(due.worldId(), new SharedWorldsScreen(new TitleScreen())));
	}

	private static Path normalize(Path path) {
		return path.toAbsolutePath().normalize();
	}
}
