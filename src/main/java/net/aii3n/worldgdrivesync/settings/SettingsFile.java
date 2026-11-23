package net.aii3n.worldgdrivesync.settings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/**
 * The player's {@link Settings}, kept in a properties file in the mod's config folder so they
 * survive restarts. Read once when created; {@link #current()} is then cheap and safe from any
 * thread. A missing file, or a missing or unreadable value, means the default.
 */
public final class SettingsFile {
	private static final String BACKUP_COUNT = "backupCount";

	private final Path file;
	private volatile Settings current;

	public SettingsFile(Path file) {
		this.file = file;
		this.current = load(file);
	}

	public Settings current() {
		return current;
	}

	/** Makes {@code settings} current and writes them to disk (atomically, so a crash can't half-write them). */
	public synchronized void save(Settings settings) throws IOException {
		Properties properties = new Properties();
		properties.setProperty(BACKUP_COUNT, Integer.toString(settings.backupCount()));
		Files.createDirectories(file.getParent());
		Path temp = Files.createTempFile(file.getParent(), file.getFileName().toString(), ".tmp");
		try {
			try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				properties.store(out, "World GDrive Sync settings");
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(temp);
		}
		current = settings;
	}

	private static Settings load(Path file) {
		Properties properties = new Properties();
		if (Files.isRegularFile(file)) {
			try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				properties.load(in);
			} catch (IOException | RuntimeException e) {
				return Settings.DEFAULTS;
			}
		}
		Settings defaults = Settings.DEFAULTS;
		return new Settings(intOr(properties, BACKUP_COUNT, defaults.backupCount()));
	}

	private static int intOr(Properties properties, String key, int fallback) {
		try {
			String value = properties.getProperty(key);
			return value == null ? fallback : Integer.parseInt(value.trim());
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
