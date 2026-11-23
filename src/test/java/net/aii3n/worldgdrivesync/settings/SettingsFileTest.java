package net.aii3n.worldgdrivesync.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsFileTest {
	@TempDir
	Path config;

	private Path file() {
		return config.resolve("world-gdrive-sync/settings.properties");
	}

	@Test
	void withNoSettingsFileTheDefaultsApply() {
		Settings settings = new SettingsFile(file()).current();

		assertEquals(Settings.DEFAULTS, settings);
		assertEquals(10, settings.backupCount());
	}

	@Test
	void aChangedBackupCountIsStillThereAfterARestart() throws Exception {
		new SettingsFile(file()).save(Settings.DEFAULTS.withBackupCount(4));

		SettingsFile afterRestart = new SettingsFile(file());

		assertEquals(4, afterRestart.current().backupCount());
	}

	@Test
	void savingChangesTheCurrentSettingsAtOnce() throws Exception {
		SettingsFile settings = new SettingsFile(file());

		settings.save(Settings.DEFAULTS.withBackupCount(25));

		assertEquals(25, settings.current().backupCount());
	}

	@Test
	void anUnreadableValueFallsBackToItsDefault() throws Exception {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "backupCount=lots\n");

		assertEquals(Settings.DEFAULTS, new SettingsFile(file()).current());
	}

	@Test
	void theBackupCountIsAtLeastOne() {
		assertEquals(1, Settings.DEFAULTS.withBackupCount(0).backupCount());
	}
}
