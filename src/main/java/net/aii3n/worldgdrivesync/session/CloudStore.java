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
	/** How much the account may hold and how much it holds now. */
	StorageQuota storageQuota() throws IOException;

	/** Every Shared World folder on the account, including ones whose sharing never finished. */
	List<CloudWorld> listWorlds() throws IOException;

	/**
	 * Makes a new, empty folder for one Shared World. Makes only one, however often the request is
	 * sent, and if it fails, leaves none behind (best effort).
	 */
	CloudWorld createWorld(String name) throws IOException;

	/** Deletes one Shared World folder and everything in it, for good. */
	void deleteWorld(String worldId) throws IOException;

	/**
	 * The Current Save's metadata (its Revision and when it was saved), or empty if the world has no
	 * Current Save yet.
	 *
	 * <p>The metadata is stored with the Current Save itself and changes in the same step that makes
	 * a zip the Current Save. So it always names the Revision of the zip a download would get, even
	 * when the call that made it current failed afterwards.
	 */
	Optional<WorldMetadata> readMetadata(String worldId) throws IOException;

	/** The size in bytes of the Current Save's zip; 0 if the world has no Current Save yet. */
	long currentSaveSize(String worldId) throws IOException;

	/**
	 * Stores {@code zip} as the world's first Current Save, carrying {@code metadata}.
	 *
	 * @param bytesSent told how many bytes of the zip have been sent so far
	 */
	void uploadCurrentSave(String worldId, Path zip, WorldMetadata metadata, LongConsumer bytesSent) throws IOException;

	/**
	 * Stores {@code zip} as the world's new Current Save, carrying {@code next}, and moves the
	 * previous Current Save into the world's Backups, labelled {@code previous}. The previous one
	 * stays the Current Save until the new zip is fully stored, so a failed upload leaves the Current
	 * Save and the Backups as they were.
	 *
	 * @param bytesSent told how many bytes of the zip have been sent so far
	 * @return the Backup the previous Current Save became
	 */
	Backup replaceCurrentSave(String worldId, Path zip, WorldMetadata next, BackupLabel previous, LongConsumer bytesSent) throws IOException;

	/**
	 * Stores {@code zip} straight into the world's Backups, labelled {@code label}, leaving the
	 * Current Save alone. Used for an Unmerged Backup. A failed upload adds no Backup.
	 *
	 * @param bytesSent told how many bytes of the zip have been sent so far
	 */
	Backup uploadBackup(String worldId, Path zip, BackupLabel label, LongConsumer bytesSent) throws IOException;

	/** Every Backup of the world, in no particular order. */
	List<Backup> listBackups(String worldId) throws IOException;

	/**
	 * Makes Backup {@code backupId} the world's Current Save, carrying {@code next}, and moves the
	 * previous Current Save into the world's Backups, labelled {@code previous}. The restored Backup
	 * leaves the Backups. Nothing is re-uploaded.
	 *
	 * @return the Backup the previous Current Save became
	 */
	Backup restoreBackup(String worldId, String backupId, WorldMetadata next, BackupLabel previous) throws IOException;

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
	 * Creates the Lock of {@code lock.hostId()}, or replaces it if that player already has one,
	 * leaving other players' Locks alone. Used to take the Lock, never for a Heartbeat.
	 */
	void writeLock(String worldId, Lock lock) throws IOException;

	/**
	 * Updates the existing Lock of {@code lock.hostId()} in place (a Heartbeat); it keeps its place in
	 * {@link #readLocks}'s order. Never creates a Lock: if that Lock is gone, nothing is written.
	 *
	 * @return whether the Lock still existed and was updated
	 */
	boolean refreshLock(String worldId, Lock lock) throws IOException;

	/**
	 * Deletes the Lock of {@code hostId}, if there is one. The store may hold it more than once (a
	 * create that was retried after its answer was lost), and one call may delete only some of those
	 * copies, so call until it returns false.
	 *
	 * @return whether anything was deleted
	 */
	boolean deleteLock(String worldId, String hostId) throws IOException;
}
