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

	/**
	 * Stores {@code zip} as the world's new Current Save and moves the previous Current Save into the
	 * world's Backups, labelled {@code previous}. The previous one stays the Current Save until the
	 * new zip is fully stored, so a failed upload leaves the Current Save and the Backups as they were.
	 *
	 * @param bytesSent told how many bytes of the zip have been sent so far
	 * @return the Backup the previous Current Save became
	 */
	Backup replaceCurrentSave(String worldId, Path zip, BackupLabel previous, LongConsumer bytesSent) throws IOException;

	/** Every Backup of the world, in no particular order. */
	List<Backup> listBackups(String worldId) throws IOException;

	/**
	 * Makes Backup {@code backupId} the world's Current Save and moves the previous Current Save into
	 * the world's Backups, labelled {@code previous}. The restored Backup leaves the Backups. Nothing
	 * is re-uploaded.
	 *
	 * @return the Backup the previous Current Save became
	 */
	Backup restoreBackup(String worldId, String backupId, BackupLabel previous) throws IOException;

	/** Deletes one Backup for good. */
	void deleteBackup(String worldId, String backupId) throws IOException;

	/**
	 * Saves the world's Current Save zip to {@code zip}, replacing that file.
	 *
	 * @param progress told how many bytes have arrived so far, out of how many
	 */
	void downloadCurrentSave(String worldId, Path zip, BytesProgress progress) throws IOException;

	/** How far a transfer has got, in bytes. */
	@FunctionalInterface
	interface BytesProgress {
		void onProgress(long done, long total);
	}

	/**
	 * Every Lock in the world's folder. Normally none or one; briefly more when two players press
	 * Play together (the coordinator settles that). Oldest first, by when the store itself created
	 * each Lock (not by any player's clock), so every player agrees which Lock is earliest.
	 */
	List<Lock> readLocks(String worldId) throws IOException;

	/**
	 * Creates or replaces the Lock of {@code lock.hostId()}, leaving other players' Locks alone. A
	 * replaced Lock (a Heartbeat) keeps its place in {@link #readLocks}'s order.
	 */
	void writeLock(String worldId, Lock lock) throws IOException;

	/** Deletes the Lock of {@code hostId}, if there is one. */
	void deleteLock(String worldId, String hostId) throws IOException;
}
