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

	private Path file() {
		return config.resolve("world-gdrive-sync/settings.properties");
	}

	@Test
	void withNoSettingsFileTenBackupsAreKept() {
		Settings settings = new SettingsFile(file()).current();

		assertEquals(Settings.DEFAULTS, settings);
		assertEquals(10, settings.backupCount());
	}

	@Test
	void aChangedBackupCountIsStillThereAfterARestart() throws Exception {
		new SettingsFile(file()).update(Settings.DEFAULTS.withBackupCount(4));

		SettingsFile afterRestart = new SettingsFile(file());

		assertEquals(4, afterRestart.current().backupCount());
	}

	@Test
	void savingChangesTheCurrentSettingsAtOnce() throws Exception {
		SettingsFile settings = new SettingsFile(file());

		settings.update(Settings.DEFAULTS.withBackupCount(25));

		assertEquals(25, settings.current().backupCount());
	}

	@Test
	void anUnreadableBackupCountFallsBackToItsDefault() throws Exception {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "backupCount=lots\n");

		assertEquals(Settings.DEFAULTS, new SettingsFile(file()).current());
	}

	@Test
	void theBackupCountIsAtLeastOne() {
		assertEquals(1, Settings.DEFAULTS.withBackupCount(0).backupCount());
	}

	@Test
	void withNoSettingsFileLocksGoStaleAfterFiveMinutes() {
		assertEquals(Duration.ofMinutes(5), new SettingsFile(file()).current().staleTimeout());
	}

	@Test
	void aChangedStaleTimeoutIsStillThereAfterARestart() throws Exception {
		new SettingsFile(file()).update(Settings.DEFAULTS.withStaleTimeout(Duration.ofMinutes(12)));

		SettingsFile afterRestart = new SettingsFile(file());

		assertEquals(Duration.ofMinutes(12), afterRestart.current().staleTimeout());
	}

	@Test
	void anUnreadableOrZeroStaleTimeoutFallsBackToItsDefault() throws Exception {
		Files.createDirectories(file().getParent());
		Files.writeString(file(), "staleTimeoutMinutes=soon\n");
		assertEquals(Settings.DEFAULTS, new SettingsFile(file()).current());

		Files.writeString(file(), "staleTimeoutMinutes=0\n");
		assertEquals(Settings.DEFAULTS, new SettingsFile(file()).current());
	}
}
