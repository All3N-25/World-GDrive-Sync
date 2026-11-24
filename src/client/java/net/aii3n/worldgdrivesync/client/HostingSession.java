package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.WorldGDriveSync;
import net.aii3n.worldgdrivesync.client.screen.SharedWorldsScreen;
import net.aii3n.worldgdrivesync.client.screen.UploadingScreen;
import net.aii3n.worldgdrivesync.session.HostingSessionState;
import net.aii3n.worldgdrivesync.session.LocalCopy;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.world.level.storage.LevelResource;
import org.jspecify.annotations.Nullable;

/**
 * Wires {@link HostingSessionState} to the game: feeds it Play, the integrated server's start and
 * stop, and client ticks, and does what it answers (shows {@link UploadingScreen} for the final
 * upload). Closing the game or a crash gives no chance to act: those leave the Lock held.
 */
public final class HostingSession {
	private static final HostingSessionState STATE = new HostingSessionState();

	private HostingSession() {
	}

	static void register() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> act(Minecraft.getInstance(), STATE.worldStarted(server.getWorldPath(LevelResource.ROOT))));
		ServerLifecycleEvents.SERVER_STOPPED.register(server -> act(Minecraft.getInstance(), STATE.worldStopped(server.getWorldPath(LevelResource.ROOT))));
		ClientTickEvents.END_CLIENT_TICK.register(minecraft -> act(minecraft, STATE.tick(minecraft.level != null, minecraft.hasSingleplayerServer(), minecraft.gui.screen() instanceof TitleScreen)));
	}

	/**
	 * Opens the Local Copy this player now holds the Lock for. Client thread only. If the player backs
	 * out before the world loads, the Lock is released and {@code onCancel} is shown. If the game
	 * gives up loading it, the Lock is released too.
	 */
	public static void open(Minecraft minecraft, String worldId, LocalCopy localCopy, Screen onCancel) {
		STATE.opened(worldId, localCopy.folder());
		SharedWorldLauncher.open(minecraft, localCopy, () -> {
			STATE.cancelled(worldId);
			leaveWithoutPlaying(worldId);
			minecraft.gui.setScreen(onCancel);
		});
	}

	private static void leaveWithoutPlaying(String worldId) {
		SharedWorlds.get().run(coordinator -> {
			coordinator.leaveWithoutPlaying(worldId);
			return null;
		}).exceptionally(e -> {
			WorldGDriveSync.LOGGER.warn("Couldn't release the Lock on {}", worldId, e);
			return null;
		});
	}

	// Any thread: what the game has to do is handed to the client thread.
	private static void act(Minecraft minecraft, HostingSessionState.Action action) {
		switch (action) {
			case HostingSessionState.Action.Nothing nothing -> {
			}
			case HostingSessionState.Action.FinalUpload upload -> minecraft.execute(() ->
					minecraft.gui.setScreen(new UploadingScreen(upload.worldId(), new SharedWorldsScreen(new TitleScreen()))));
			case HostingSessionState.Action.ReleaseLock release -> {
				WorldGDriveSync.LOGGER.warn("Shared World {} didn't load; releasing its Lock", release.worldId());
				leaveWithoutPlaying(release.worldId());
			}
		}
	}

	/** The id of the Shared World this player is hosting right now, if any. */
	public static @Nullable String hostedWorldId() {
		return STATE.hostedWorldId().orElse(null);
	}
}
