package net.aii3n.worldgdrivesync.settings;

import java.time.Duration;

/**
 * The player's mod settings. Immutable: change one with a {@code with...} method and save the
 * result with {@link SettingsFile}. Add a component (its default in {@link #DEFAULTS}, a
 * {@code with...} method, and a key in {@link SettingsFile}) per setting. The Google connection is
 * kept separately, with its token.
 *
 * @param periodicUploadInterval how often the world is uploaded while hosting;
 *                               {@link Duration#ZERO} means periodic upload is disabled
 * @param backupCount            how many Backups each Shared World keeps; older ones are deleted
 *                               (clamped to {@link #MIN_BACKUP_COUNT}..{@link #MAX_BACKUP_COUNT})
 */
public record Settings(Duration periodicUploadInterval, int backupCount) {
	public static final int MIN_BACKUP_COUNT = 1;
	public static final int MAX_BACKUP_COUNT = 100;

	public static final Settings DEFAULTS = new Settings(Duration.ofMinutes(30), 10);

	public Settings {
		if (periodicUploadInterval.isNegative()) throw new IllegalArgumentException("negative upload interval");
		backupCount = Math.clamp(backupCount, MIN_BACKUP_COUNT, MAX_BACKUP_COUNT);
	}

	public boolean periodicUploadEnabled() {
		return !periodicUploadInterval.isZero();
	}

	public Settings withPeriodicUploadInterval(Duration interval) {
		return new Settings(interval, backupCount);
	}

	public Settings withBackupCount(int backupCount) {
		return new Settings(periodicUploadInterval, backupCount);
	}
}
