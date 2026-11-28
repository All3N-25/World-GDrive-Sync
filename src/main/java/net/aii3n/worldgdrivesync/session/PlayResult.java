package net.aii3n.worldgdrivesync.session;

import java.util.Optional;

/** What happened when this player pressed Play. */
public sealed interface PlayResult {
	/**
	 * This player now holds the Lock and the Local Copy is ready: open it.
	 *
	 * @param localCopy      the Local Copy, at the Current Save's Revision, or this player's own copy
	 *                       with a crashed session's progress on top of it
	 * @param unmergedBackup set if this player's Local Copy had progress from a crashed session or a
	 *                       Pending Upload, but someone else had uploaded a newer Revision since: that
	 *                       progress was kept as this Unmerged Backup and the Local Copy replaced
	 */
	record Ready(LocalCopy localCopy, Optional<Backup> unmergedBackup) implements PlayResult {
		public Ready(LocalCopy localCopy) {
			this(localCopy, Optional.empty());
		}
	}

	/** Someone else holds the Lock. Nothing was downloaded or changed. */
	record Refused(Lock heldBy) implements PlayResult {
	}

	/**
	 * Someone else holds a Stale Lock. Nothing was taken or changed. The player chooses Take Over
	 * ({@link SessionCoordinator#takeOver}) or Wait (do nothing).
	 */
	record Stale(Lock heldBy) implements PlayResult {
	}
}
