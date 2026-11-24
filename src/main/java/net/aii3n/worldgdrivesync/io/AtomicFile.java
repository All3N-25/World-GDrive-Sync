package net.aii3n.worldgdrivesync.io;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;

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
		write(file, contents, false);
	}

	/** As {@link #write}, for secrets: on POSIX file systems only the owner can read the file. */
	public static void writeOwnerOnly(Path file, Contents contents) throws IOException {
		write(file, contents, true);
	}

	private static void write(Path file, Contents contents, boolean ownerOnly) throws IOException {
		Path folder = file.toAbsolutePath().getParent();
		Files.createDirectories(folder);
		Path temp = Files.createTempFile(folder, file.getFileName().toString(), ".tmp");
		try {
			if (ownerOnly) restrictToOwner(temp); // before the secret is written into it
			try (Writer out = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				contents.writeTo(out);
			}
			try {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
			} catch (AtomicMoveNotSupportedException e) {
				// Rare (some network or FUSE file systems). The temp file is in the same folder, so a plain
				// replace is still a rename in practice, just without the guarantee.
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	private static void restrictToOwner(Path path) {
		try {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException | IOException ignored) {
			// Not a POSIX file system (Windows); the user's profile folder is private anyway.
		}
	}
}
