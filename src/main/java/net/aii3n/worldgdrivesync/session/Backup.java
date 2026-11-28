package net.aii3n.worldgdrivesync.session;

import java.time.Instant;
import java.util.Optional;

/**
 * A previous Current Save, kept in the Shared World's Backups after a newer one replaced it, or an
 * Unmerged Backup of a returning Host's Local Copy. Only the most recent few are kept.
 *
 * @param id    the cloud store's id for this Backup, unique within its Shared World
 * @param label what the Backup is
 */
public record Backup(String id, BackupLabel label) {
	/** The Revision this was the Current Save at (for an Unmerged Backup: the Revision it started from). */
	public int revision() {
		return label.revision();
	}

	/** When this world was saved: the Backup's timestamp. */
	public Instant savedAt() {
		return label.savedAt();
	}

	/** The crashed session this is an Unmerged Backup of; empty for an ordinary Backup. */
	public Optional<CrashedSession> unmerged() {
		return label.unmerged();
	}
}
