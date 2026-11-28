package net.aii3n.worldgdrivesync.io;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps one implementation of "write a temp file, then rename it over the real one": {@link
 * AtomicFile}. Copies of it drift apart (one had a fallback for file systems without atomic
 * renames, another didn't).
 */
class OneAtomicWriteTest {
	private static final Path ATOMIC_FILE = Path.of("src/main/java/net/aii3n/worldgdrivesync/io/AtomicFile.java");
	private static final List<Path> SOURCES = List.of(Path.of("src/main/java"), Path.of("src/client/java"));

	@Test
	void onlyAtomicFileRenamesFilesAtomically() throws IOException {
		List<String> hits = new ArrayList<>();
		for (Path dir : SOURCES) {
			try (Stream<Path> files = Files.walk(dir)) {
				for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
					if (!file.equals(ATOMIC_FILE) && Files.readString(file).contains("ATOMIC_MOVE")) hits.add(file.toString());
				}
			}
		}
		assertTrue(hits.isEmpty(), "Write files atomically with AtomicFile.write, not by hand:\n" + String.join("\n", hits));
	}
}
