package net.aii3n.worldgdrivesync.google;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;

/**
 * The sign-in kept on disk between game sessions: the refresh token and which account it belongs
 * to. The short-lived access token is never written.
 */
record StoredSignIn(String refreshToken, String accountEmail) {
	private static final Gson GSON = new Gson();

	static Optional<StoredSignIn> load(Path file) {
		if (!Files.isRegularFile(file)) return Optional.empty();
		try {
			StoredSignIn stored = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), StoredSignIn.class);
			if (stored == null || stored.refreshToken() == null || stored.refreshToken().isBlank()) return Optional.empty();
			return Optional.of(stored);
		} catch (IOException | JsonParseException e) {
			return Optional.empty();
		}
	}

	void save(Path file) throws IOException {
		Files.createDirectories(file.toAbsolutePath().getParent());
		Path temp = Files.createTempFile(file.toAbsolutePath().getParent(), file.getFileName().toString(), ".tmp");
		try {
			restrictToOwner(temp);
			Files.writeString(temp, GSON.toJson(this), StandardCharsets.UTF_8);
			try {
				Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch (AtomicMoveNotSupportedException e) {
				Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
			}
		} finally {
			Files.deleteIfExists(temp);
		}
	}

	static void delete(Path file) throws IOException {
		Files.deleteIfExists(file);
	}

	private static void restrictToOwner(Path path) {
		try {
			Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
		} catch (UnsupportedOperationException | IOException ignored) {
			// Not a POSIX filesystem (Windows); the user's profile folder is private anyway.
		}
	}
}
