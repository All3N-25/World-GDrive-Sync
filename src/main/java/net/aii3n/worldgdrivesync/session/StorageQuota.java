package net.aii3n.worldgdrivesync.session;

import java.util.OptionalLong;

/**
 * The cloud storage account's room, in bytes.
 *
 * @param limit how much the account may hold in all; empty means unlimited
 * @param usage how much it holds now
 */
public record StorageQuota(OptionalLong limit, long usage) {
	/** How much more the account can hold; empty means unlimited. */
	public OptionalLong freeSpace() {
		return limit.isEmpty() ? limit : OptionalLong.of(Math.max(0, limit.getAsLong() - usage));
	}

	/**
	 * The free space, if the account is nearly full for uploads of {@code relevantSize} bytes: it
	 * can't take two of them.
	 */
	public OptionalLong freeSpaceIfNearlyFull(long relevantSize) {
		OptionalLong free = freeSpace();
		// Halved rather than doubling the size, which could overflow.
		boolean nearlyFull = free.isPresent() && free.getAsLong() / 2 < relevantSize;
		return nearlyFull ? free : OptionalLong.empty();
	}
}
