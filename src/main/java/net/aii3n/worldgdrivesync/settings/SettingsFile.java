package net.aii3n.worldgdrivesync.settings;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Properties;

/**
 * The player's {@link Settings}, kept in a properties file in the mod's config folder. A missing
 * file, or a value that can't be read, means that setting's default.
 */
public final class SettingsFile {
	private static final String STALE_TIMEOUT_MINUTES = "staleTimeoutMinutes";

	private final Path file;
	private volatile Settings settings;

	/** Loads the settings from {@code file}, if it exists. */
	public SettingsFile(Path file) {
		this.file = file;
		this.settings = load(file);
	}

	/** The current settings. */
	public Settings settings() {
		return settings;
	}

	/** Makes {@code settings} current and writes them to the file. */
	public void save(Settings settings) throws IOException {
		this.settings = settings;
		Properties properties = new Properties();
		properties.setProperty(STALE_TIMEOUT_MINUTES, Long.toString(settings.staleTimeout().toMinutes()));

		Path folder = file.toAbsolutePath().getParent();
		Files.createDirectories(folder);
		Path temp = Files.createTempFile(folder, file.getFileName().toString(), ".tmp");
		try {
			try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				properties.store(out, "World GDrive Sync settings");
			}
			try {
				Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private static Settings load(Path file) {
		Properties properties = new Properties();
		if (Files.isRegularFile(file)) {
			try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				properties.load(in);
			} catch (IOException | IllegalArgumentException e) {
				return Settings.DEFAULTS;
			}
		}
		Settings defaults = Settings.DEFAULTS;
		return new Settings(
				minutes(properties, STALE_TIMEOUT_MINUTES, defaults.staleTimeout()));
	}

	private static Duration minutes(Properties properties, String key, Duration fallback) {
		String value = properties.getProperty(key);
		if (value == null) return fallback;
		try {
			long minutes = Long.parseLong(value.trim());
			return minutes > 0 ? Duration.ofMinutes(minutes) : fallback;
		} catch (NumberFormatException e) {
			return fallback;
		}
	}
}
