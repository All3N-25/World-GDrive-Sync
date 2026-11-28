package net.aii3n.worldgdrivesync.session;

import java.util.Locale;

/** Byte counts as players read them. */
public final class ByteSize {
	private static final long MB = 1024 * 1024;
	private static final long GB = 1024 * MB;

	private ByteSize() {
	}

	/** "12.3 MB", or from 1 GB up "1.5 GB". */
	public static String format(long bytes) {
		return bytes < GB
				? String.format(Locale.ROOT, "%.1f MB", (double) bytes / MB)
				: String.format(Locale.ROOT, "%.1f GB", (double) bytes / GB);
	}
}
