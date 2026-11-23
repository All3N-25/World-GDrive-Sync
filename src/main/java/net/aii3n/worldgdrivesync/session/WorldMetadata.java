package net.aii3n.worldgdrivesync.session;

import java.time.Instant;

/** What the cloud store records about a Shared World's Current Save. */
public record WorldMetadata(int revision, Instant savedAt) {
}
