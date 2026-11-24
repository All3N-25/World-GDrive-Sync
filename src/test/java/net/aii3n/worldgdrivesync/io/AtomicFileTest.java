package net.aii3n.worldgdrivesync.io;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AtomicFileTest {
	@TempDir
	Path folder;

	@Test
	void aWriteThatFailsPartwayLeavesThePreviousContents() throws Exception {
		Path file = folder.resolve("world-gdrive-sync.properties");
		AtomicFile.write(file, out -> out.write("revision=3\n"));

		assertThrows(IOException.class, () -> AtomicFile.write(file, out -> {
			out.write("revi");
			out.flush();
			throw new IOException("the game crashed");
		}));

		assertEquals("revision=3\n", Files.readString(file));
	}
}
