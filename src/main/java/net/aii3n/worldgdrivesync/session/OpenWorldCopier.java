package net.aii3n.worldgdrivesync.session;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Copies a world that is open and still being played: saves it to disk, then copies the world
 * folder (with {@link WorldZip#copy}) into {@code folder} before the game writes to it again, so
 * the copy is consistent.
 */
@FunctionalInterface
public interface OpenWorldCopier {
	/** @param folder an empty folder to copy the saved world into */
	void copyTo(Path folder) throws IOException;
}
