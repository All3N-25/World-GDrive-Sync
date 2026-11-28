package net.aii3n.worldgdrivesync.session;

import java.time.Instant;
import java.util.Optional;

/**
 * Everything the cloud store records about a {@link Backup} besides its id.
 *
 * @param revision the Revision the world was at
 * @param savedAt  when the world was saved: the Backup's timestamp, which rotation goes by
 * @param unmerged for an Unmerged Backup, the crashed session it keeps; empty otherwise
 */
public record BackupLabel(int revision, Instant savedAt, Optional<CrashedSession> unmerged) {
	/** The label for a Current Save that is being replaced. */
	static BackupLabel of(WorldMetadata currentSave) {
		return new BackupLabel(currentSave.revision(), currentSave.savedAt(), Optional.empty());
	}
}
