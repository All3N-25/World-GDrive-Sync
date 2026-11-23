package net.aii3n.worldgdrivesync.session;

/** What happened when this player pressed Play. */
public sealed interface PlayResult {
	/** This player now holds the Lock and the Local Copy is the Current Revision: open it. */
	record Ready(LocalCopy localCopy) implements PlayResult {
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
