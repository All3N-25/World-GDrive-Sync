package net.aii3n.worldgdrivesync.session;

/**
 * Whether anyone is hosting a Shared World. Later tickets add Hosted (by whom, since when) and
 * Stale (by whom, last Heartbeat) once Locks exist.
 */
public sealed interface WorldStatus {
	Free FREE = new Free();

	/** Nobody holds the Lock; anyone may press Play. */
	record Free() implements WorldStatus {
	}
}
