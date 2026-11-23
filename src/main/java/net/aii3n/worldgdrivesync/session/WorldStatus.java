package net.aii3n.worldgdrivesync.session;

import java.time.Instant;

/** Whether anyone is hosting a Shared World. Stale (by whom, last Heartbeat) comes with Heartbeats. */
public sealed interface WorldStatus {
	Free FREE = new Free();

	/** Nobody holds the Lock; anyone may press Play. */
	record Free() implements WorldStatus {
	}

	/** {@code host} holds the Lock, and has since {@code since}. */
	record Hosted(String host, Instant since) implements WorldStatus {
	}
}
