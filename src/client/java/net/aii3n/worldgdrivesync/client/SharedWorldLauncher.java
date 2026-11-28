package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.session.LocalCopy;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.worldselection.WorldOpenFlows;
import net.minecraft.world.level.storage.LevelStorageSource;

import java.nio.file.Path;

/**
 * Opens a Local Copy from {@code sharedsaves/} as a normal singleplayer world (ADR-0002).
 *
 * <p>This is the only code that launches a world itself, and it depends on Minecraft internals
 * ({@link WorldOpenFlows}, {@link LevelStorageSource}), so it's the first place to look when a
 * Minecraft update breaks Play. Keep it this small. Don't move Local Copies into {@code saves/}
 * to avoid it: that would let players open a Shared World without the Lock.
 */
public final class SharedWorldLauncher {
	private SharedWorldLauncher() {
	}

	/**
	 * Loads into the Local Copy. Client thread only. Vanilla handles everything after this: version
	 * and backup prompts, loading, and saving into the Local Copy's own folder, since the integrated
	 * server's storage access points there. Quitting returns to the title screen as usual.
	 *
	 * @param onCancel run on the client thread if the player backs out of a vanilla prompt before the
	 *                 world loads
	 */
	public static void open(Minecraft minecraft, LocalCopy localCopy, Runnable onCancel) {
		Path folder = localCopy.folder();
		// createDefault(dir) puts any pre-upgrade backups in <dir>/../backups, the vanilla backups/ folder.
		LevelStorageSource sharedsaves = LevelStorageSource.createDefault(folder.getParent());
		new WorldOpenFlows(minecraft, sharedsaves)
				.openWorld(folder.getFileName().toString(), onCancel);
	}
}
