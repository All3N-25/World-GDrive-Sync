package net.aii3n.worldgdrivesync.settings;

import net.aii3n.worldgdrivesync.io.AtomicFile;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

/**
 * The player's {@link Settings}, kept in a properties file in the mod's config folder so they
 * survive restarts. Holds the current settings in memory; {@link #current()} is cheap and safe from
 * any thread. A missing or unreadable value falls back to its default.
 */
public final class SettingsFile {
	private static final String PERIODIC_UPLOAD_MINUTES = "periodicUploadMinutes";
	private static final String BACKUP_COUNT = "backupCount";
	private static final String STALE_TIMEOUT_MINUTES = "staleTimeoutMinutes";

	private final Path file;
	private volatile Settings current;

	public SettingsFile(Path file) {
		this.file = file;
		this.current = read(file);
	}

	public Settings current() {
		return current;
	}

	/** Makes {@code settings} current and saves them (atomically, so a crash can't half-write them). */
	public synchronized void update(Settings settings) throws IOException {
		current = settings;
		Properties properties = new Properties();
		properties.setProperty(PERIODIC_UPLOAD_MINUTES, Long.toString(settings.periodicUploadInterval().toMinutes()));
		properties.setProperty(BACKUP_COUNT, Integer.toString(settings.backupCount()));
		properties.setProperty(STALE_TIMEOUT_MINUTES, Long.toString(settings.staleTimeout().toMinutes()));
		AtomicFile.write(file,
				out -> properties.store(out, "World GDrive Sync settings (periodicUploadMinutes=0 disables periodic upload)"));
	}

	private static Settings read(Path file) {
		Settings settings = Settings.DEFAULTS;
		if (!Files.isRegularFile(file)) return settings;
		Properties properties = new Properties();
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(in);
		} catch (IOException e) {
			return settings;
		}
		try {
			String minutes = properties.getProperty(PERIODIC_UPLOAD_MINUTES);
			if (minutes != null) settings = settings.withPeriodicUploadInterval(Duration.ofMinutes(Long.parseLong(minutes.trim())));
		} catch (RuntimeException ignored) {
			// keep the default for this setting
		}
		try {
			String count = properties.getProperty(BACKUP_COUNT);
			if (count != null) settings = settings.withBackupCount(Integer.parseInt(count.trim()));
		} catch (RuntimeException ignored) {
			// keep the default for this setting
		}
		try {
			String minutes = properties.getProperty(STALE_TIMEOUT_MINUTES);
			if (minutes != null) settings = settings.withStaleTimeout(Duration.ofMinutes(Long.parseLong(minutes.trim())));
		} catch (RuntimeException ignored) {
			// keep the default for this setting
		}
		return settings;
	}
}
