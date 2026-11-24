package net.aii3n.worldgdrivesync.io;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Writes small text files so that a crash or failure partway through never leaves them half-written. */
public final class AtomicFile {
	private AtomicFile() {
	}

	/** What goes into the file. */
	@FunctionalInterface
	public interface Contents {
		void writeTo(Writer out) throws IOException;
	}

	/**
	 * Replaces {@code file} with what {@code contents} writes, as UTF-8. If writing fails, the file
	 * keeps its previous contents (or stays missing). Creates the file's folder if needed.
	 */
	public static void write(Path file, Contents contents) throws IOException {
		Path folder = file.toAbsolutePath().getParent();
		Files.createDirectories(folder);
		Path temp = Files.createTempFile(folder, file.getFileName().toString(), ".tmp");
		try {
			try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				contents.writeTo(out);
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} finally {
			Files.deleteIfExists(temp);
		}
	}
}
