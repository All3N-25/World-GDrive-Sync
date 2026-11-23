package net.aii3n.worldgdrivesync.session;

import java.time.Instant;

/**
 * A Shared World as the Shared Worlds screen shows it.
 *
 * @param id       the cloud store's id for the world; also the Local Copy's folder name
 * @param revision the Current Save's Revision
 * @param savedAt  when the Current Save was uploaded
 */
public record SharedWorld(String id, String name, int revision, Instant savedAt, WorldStatus status) {
}
