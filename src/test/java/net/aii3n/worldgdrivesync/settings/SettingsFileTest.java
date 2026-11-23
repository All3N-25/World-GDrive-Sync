package net.aii3n.worldgdrivesync.settings;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SettingsFileTest {
	@TempDir
	Path config;

	@Test
	void withoutASavedFileTheDefaultsApply() {
		assertEquals(Duration.ofMinutes(30), new SettingsFile(config.resolve("settings.properties")).current().periodicUploadInterval());
	}

	@Test
	void aChangedSettingIsStillThereAfterARestart() throws Exception {
		Path file = config.resolve("world-gdrive-sync/settings.properties");
		new SettingsFile(file).update(Settings.DEFAULTS.withPeriodicUploadInterval(Duration.ofMinutes(10)));
		new SettingsFile(file).update(new SettingsFile(file).current().withPeriodicUploadInterval(Duration.ZERO));

		Settings afterRestart = new SettingsFile(file).current();

		assertEquals(Duration.ZERO, afterRestart.periodicUploadInterval());
		assertEquals(false, afterRestart.periodicUploadEnabled());
	}

	@Test
	void anUnreadableFileFallsBackToTheDefaults() throws Exception {
		Path file = config.resolve("settings.properties");
		Files.writeString(file, "periodicUploadMinutes=soon\n");

		assertEquals(Settings.DEFAULTS, new SettingsFile(file).current());
	}
}
