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
	void withNoSettingsSavedYetEverySettingHasItsDefault() {
		SettingsFile file = new SettingsFile(config.resolve("world-gdrive-sync/settings.properties"));

		assertEquals(Duration.ofMinutes(5), file.settings().staleTimeout());
	}

	@Test
	void savedSettingsSurviveARestart() throws Exception {
		Path path = config.resolve("world-gdrive-sync/settings.properties");
		new SettingsFile(path).save(Settings.DEFAULTS.withStaleTimeout(Duration.ofMinutes(12)));

		SettingsFile afterRestart = new SettingsFile(path);

		assertEquals(Duration.ofMinutes(12), afterRestart.settings().staleTimeout());
	}

	@Test
	void aSettingThatCantBeReadFallsBackToItsDefault() throws Exception {
		Path path = config.resolve("settings.properties");
		Files.writeString(path, "staleTimeoutMinutes=soon\n");

		assertEquals(Duration.ofMinutes(5), new SettingsFile(path).settings().staleTimeout());
	}
}
