package net.aii3n.worldgdrivesync.session;

/** What happened when a player asked to restore a Backup. */
public sealed interface RestoreResult {
	/**
	 * The Backup is the Current Save now, at a new Revision, and the replaced Current Save became a
	 * Backup so the restore can be undone.
	 *
	 * @param current  the restored Current Save's metadata
	 * @param replaced the Backup the previous Current Save became
	 */
	record Restored(WorldMetadata current, Backup replaced) implements RestoreResult {
	}

	/** Someone holds a Lock (fresh or Stale), so nothing was changed. */
	record Refused(Lock heldBy) implements RestoreResult {
	}
}
