package net.aii3n.worldgdrivesync.session;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.LongConsumer;

/**
 * Where Shared Worlds live between sessions. The session coordinator owns every rule; a cloud store
 * only stores and fetches. The game uses Google Drive ({@code drive.DriveCloudStore}); tests use an
 * in-memory store.
 *
 * <p>Every call blocks and may be slow, so callers run the coordinator off the render thread.
 */
public interface CloudStore {
	/** Every Shared World folder on the account, including ones whose sharing never finished. */
	List<CloudWorld> listWorlds() throws IOException;

	/** Makes a new, empty folder for one Shared World. */
	CloudWorld createWorld(String name) throws IOException;

	/** The world's metadata, or empty if none has been written yet. */
	Optional<WorldMetadata> readMetadata(String worldId) throws IOException;

	void writeMetadata(String worldId, WorldMetadata metadata) throws IOException;

	/**
	 * Stores {@code zip} as the world's Current Save, replacing any previous one.
	 *
	 * @param bytesSent told how many bytes of the zip have been sent so far
	 */
	void uploadCurrentSave(String worldId, Path zip, LongConsumer bytesSent) throws IOException;
}
