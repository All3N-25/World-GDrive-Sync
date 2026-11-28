package net.aii3n.worldgdrivesync.google;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import net.aii3n.worldgdrivesync.io.AtomicFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
		AtomicFile.writeOwnerOnly(file, out -> out.write(GSON.toJson(this)));
	}

	static void delete(Path file) throws IOException {
		Files.deleteIfExists(file);
	}
}
