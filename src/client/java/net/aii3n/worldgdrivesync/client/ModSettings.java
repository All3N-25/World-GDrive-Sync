package net.aii3n.worldgdrivesync.client;

import net.aii3n.worldgdrivesync.settings.SettingsFile;
import net.fabricmc.loader.api.FabricLoader;
import org.jspecify.annotations.Nullable;

/** The player's settings file, {@code config/world-gdrive-sync/settings.properties}. */
public final class ModSettings {
	private static @Nullable SettingsFile file;

	private ModSettings() {
	}

	static void init() {
		file = new SettingsFile(FabricLoader.getInstance().getConfigDir().resolve("world-gdrive-sync/settings.properties"));
	}

	public static SettingsFile get() {
		if (file == null) throw new IllegalStateException("ModSettings used before client init");
		return file;
	}
}
