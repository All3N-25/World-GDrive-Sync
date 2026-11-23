package net.aii3n.worldgdrivesync.session;

import java.nio.file.Path;

/**
 * This player's own unzipped copy of a Shared World, under the Local Copy directory
 * ({@code sharedsaves/}, ADR-0002).
 *
 * @param folder   the world folder, ready for Minecraft to open
 * @param revision the Revision this copy came from
 */
public record LocalCopy(Path folder, int revision) {
}
