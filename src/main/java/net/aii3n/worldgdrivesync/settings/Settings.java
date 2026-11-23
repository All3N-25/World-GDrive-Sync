package net.aii3n.worldgdrivesync.settings;

/**
 * The player's mod settings. Immutable: change one with a {@code with...} method and save the
 * result through {@link SettingsFile}. The Google connection is kept separately, with its token.
 *
 * <p>To add a setting: add a component, its default to {@link #DEFAULTS}, a {@code with...} method,
 * and a key in {@link SettingsFile}.
 *
 * @param backupCount how many Backups each Shared World keeps; older ones are deleted (at least 1)
 */
public record Settings(int backupCount) {
	public static final int MIN_BACKUP_COUNT = 1;
	public static final int MAX_BACKUP_COUNT = 100;

	public static final Settings DEFAULTS = new Settings(10);

	public Settings {
		backupCount = Math.clamp(backupCount, MIN_BACKUP_COUNT, MAX_BACKUP_COUNT);
	}

	public Settings withBackupCount(int backupCount) {
		return new Settings(backupCount);
	}
}
