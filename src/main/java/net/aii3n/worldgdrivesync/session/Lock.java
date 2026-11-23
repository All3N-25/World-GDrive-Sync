package net.aii3n.worldgdrivesync.session;

import java.time.Instant;

/**
 * A marker in the cloud store saying who is hosting a Shared World. Each Host writes their own
 * Lock, keyed by {@link #hostId}; while one exists, nobody else may start hosting.
 *
 * @param hostId        the Host's {@link Player#id()}
 * @param hostName      the Host's {@link Player#name()}
 * @param startedAt     when the Host took the Lock
 * @param lastHeartbeat when the Host last proved they are still hosting (equal to {@code startedAt}
 *                      until the first Heartbeat)
 */
public record Lock(String hostId, String hostName, Instant startedAt, Instant lastHeartbeat) {
	/** A fresh Lock for {@code host}, taken at {@code now}. */
	static Lock takenBy(Player host, Instant now) {
		return new Lock(host.id(), host.name(), now, now);
	}

	boolean heldBy(Player player) {
		return hostId.equals(player.id());
	}
}
