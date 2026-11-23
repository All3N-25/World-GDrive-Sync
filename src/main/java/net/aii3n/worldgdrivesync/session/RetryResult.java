package net.aii3n.worldgdrivesync.session;

/** What retrying a Pending Upload did. */
public sealed interface RetryResult {
	/** The Pending Upload is now the Current Save, and the Lock is released. */
	record Uploaded(WorldMetadata current) implements RetryResult {
	}

	/**
	 * Someone else had uploaded a newer Revision since (after a Takeover), so the Pending Upload was
	 * kept as this Unmerged Backup instead, and the Local Copy replaced with the newer Revision.
	 */
	record KeptAsUnmergedBackup(Backup backup) implements RetryResult {
	}
}
