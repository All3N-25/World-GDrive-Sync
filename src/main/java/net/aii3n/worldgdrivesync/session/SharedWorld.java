package net.aii3n.worldgdrivesync.session;

import java.time.Instant;

/**
 * A Shared World as the Shared Worlds screen shows it.
 *
 * @param id            the cloud store's id for the world; also the Local Copy's folder name
 * @param revision      the Current Save's Revision
 * @param savedAt       when the Current Save was uploaded
 * @param pendingUpload this computer's Local Copy is a Pending Upload (only ever true on the
 *                      computer that has it; friends see the Host's Lock)
 */
public record SharedWorld(String id, String name, int revision, Instant savedAt, WorldStatus status, boolean pendingUpload) {
	/** A Shared World with no Pending Upload on this computer. */
	public SharedWorld(String id, String name, int revision, Instant savedAt, WorldStatus status) {
		this(id, name, revision, savedAt, status, false);
	}
}
