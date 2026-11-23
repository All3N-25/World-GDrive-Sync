package net.aii3n.worldgdrivesync.session;

import java.time.Instant;

/**
 * A Host's session that ended without a successful final upload, after which someone else Took
 * Over. An Unmerged Backup is labelled with it.
 *
 * @param hostId    the Host's {@link Player#id()}
 * @param hostName  the Host's {@link Player#name()}, shown in the Backups list
 * @param startedAt when that session took the Lock
 */
public record CrashedSession(String hostId, String hostName, Instant startedAt) {
}
