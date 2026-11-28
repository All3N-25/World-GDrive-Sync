package net.aii3n.worldgdrivesync.session;

import java.util.List;
import java.util.OptionalLong;

/**
 * What the Shared Worlds screen shows.
 *
 * @param worlds              every Shared World on the account
 * @param nearlyFullFreeSpace set if the account is nearly full: its free space in bytes, less than
 *                            twice the size of the largest Current Save
 */
public record SharedWorldListing(List<SharedWorld> worlds, OptionalLong nearlyFullFreeSpace) {
}
