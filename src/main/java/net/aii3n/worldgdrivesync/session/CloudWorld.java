package net.aii3n.worldgdrivesync.session;

/** One Shared World's folder in the cloud store. {@code id} is safe to use as a folder name. */
public record CloudWorld(String id, String name) {
}
