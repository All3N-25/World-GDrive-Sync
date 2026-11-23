package net.aii3n.worldgdrivesync.settings;

import java.time.Duration;

/**
 * The player's mod settings. Immutable; change one with a {@code with...} copy and save it with
 * {@link SettingsFile}. To add a setting: add a component, give it a default in {@link #DEFAULTS},
 * add a {@code with...} method, and read and write it in {@link SettingsFile}.
 *
 * @param staleTimeout how long a Lock may go without a Heartbeat before it counts as Stale
 */
public record Settings(Duration staleTimeout) {
	public static final Settings DEFAULTS = new Settings(Duration.ofMinutes(5));

	public Settings withStaleTimeout(Duration staleTimeout) {
		return new Settings(staleTimeout);
	}
}
