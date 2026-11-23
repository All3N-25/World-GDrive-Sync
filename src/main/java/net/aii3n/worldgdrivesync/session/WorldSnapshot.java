package net.aii3n.worldgdrivesync.session;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Takes a consistent copy of a world that is open and still being played: saves it to disk, then
 * copies the world folder into {@code folder} before the game writes to it again.
 */
@FunctionalInterface
public interface WorldSnapshot {
	/** @param folder an empty folder to copy the saved world into */
	void saveTo(Path folder) throws IOException;

	/**
	 * Copies a saved world folder into {@code folder}, leaving out the open world's
	 * {@code session.lock} and the Local Copy's own record, as every upload does.
	 */
	static void copyWorld(Path worldFolder, Path folder) throws IOException {
		WorldZip.copy(worldFolder, folder);
	}
}
