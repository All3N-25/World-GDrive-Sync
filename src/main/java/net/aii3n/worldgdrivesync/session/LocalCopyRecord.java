package net.aii3n.worldgdrivesync.session;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Properties;

/**
 * What a Local Copy remembers about itself, kept in a small file inside the world folder so it
 * moves and is replaced together with the world. Never zipped (see {@link WorldZip}).
 */
record LocalCopyRecord(int revision) {
	static final String FILE_NAME = "world-gdrive-sync.properties";

	static Optional<LocalCopyRecord> read(Path worldFolder) {
		Path file = worldFolder.resolve(FILE_NAME);
		if (!Files.isRegularFile(file)) return Optional.empty();
		Properties properties = new Properties();
		try (Reader in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
			properties.load(in);
			return Optional.of(new LocalCopyRecord(Integer.parseInt(properties.getProperty("revision"))));
		} catch (IOException | RuntimeException e) {
			return Optional.empty();
		}
	}

	void write(Path worldFolder) throws IOException {
		Properties properties = new Properties();
		properties.setProperty("revision", Integer.toString(revision));
		try (Writer out = Files.newBufferedWriter(worldFolder.resolve(FILE_NAME), StandardCharsets.UTF_8)) {
			properties.store(out, "World GDrive Sync: what this Local Copy came from");
		}
	}
}
