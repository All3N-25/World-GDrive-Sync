package net.aii3n.worldgdrivesync.session;

import java.time.Instant;

/** Whether anyone is hosting a Shared World. */
public sealed interface WorldStatus {
	Free FREE = new Free();

	/** Nobody holds the Lock; anyone may press Play. */
	record Free() implements WorldStatus {
	}

	/** {@code host} holds the Lock, and has since {@code since}. */
	record Hosted(String host, Instant since) implements WorldStatus {
	}

	/**
	 * {@code host} holds a Stale Lock: no Heartbeat since {@code lastSeen}, for longer than the stale
	 * timeout. Play offers a Takeover.
	 */
	record Stale(String host, Instant lastSeen) implements WorldStatus {
	}
}
