package net.aii3n.worldgdrivesync.session;

import java.nio.file.Path;

/**
 * This player's own unzipped copy of a Shared World, under the Local Copy directory
 * ({@code sharedsaves/}, ADR-0002).
 *
 * @param folder        the world folder, ready for Minecraft to open
 * @param revision      the Revision this copy came from
 * @param pendingUpload this copy should be the new Current Save but its upload failed; the Host
 *                      keeps the Lock until a retry succeeds
 */
public record LocalCopy(Path folder, int revision, boolean pendingUpload) {
	/** A Local Copy with nothing waiting to be uploaded. */
	public LocalCopy(Path folder, int revision) {
		this(folder, revision, false);
	}
}
