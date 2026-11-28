package net.aii3n.worldgdrivesync.session;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/**
 * The coordinator's time: what time it is, and waiting. Tests use a clock that only moves when told
 * to, so waiting takes no real time there.
 */
public interface SessionClock extends InstantSource {
	/** Blocks for {@code duration}. */
	void sleep(Duration duration) throws InterruptedException;

	/** The real clock. */
	static SessionClock system() {
		return new SessionClock() {
			@Override
			public Instant instant() {
				return Instant.now();
			}

			@Override
			public void sleep(Duration duration) throws InterruptedException {
				Thread.sleep(duration);
			}
		};
	}
}
