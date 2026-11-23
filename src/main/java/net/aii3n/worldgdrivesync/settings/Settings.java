package net.aii3n.worldgdrivesync.settings;

import java.time.Duration;

/**
 * The player's mod settings. Immutable: change one with a {@code with...} method and save the
 * result with {@link SettingsFile}. Add a component (and its default in {@link #DEFAULTS}) per
 * setting.
 *
 * @param periodicUploadInterval how often the world is uploaded while hosting;
 *                               {@link Duration#ZERO} means periodic upload is disabled
 */
public record Settings(Duration periodicUploadInterval) {
	public static final Settings DEFAULTS = new Settings(Duration.ofMinutes(30));

	public Settings {
		if (periodicUploadInterval.isNegative()) throw new IllegalArgumentException("negative upload interval");
	}

	public boolean periodicUploadEnabled() {
		return !periodicUploadInterval.isZero();
	}

	public Settings withPeriodicUploadInterval(Duration interval) {
		return new Settings(interval);
	}
}
