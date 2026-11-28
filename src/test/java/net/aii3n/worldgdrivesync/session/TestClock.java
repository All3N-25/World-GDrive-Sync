package net.aii3n.worldgdrivesync.session;

import java.time.Duration;
import java.time.Instant;

/** A clock that only moves when the test says so. */
final class TestClock implements SessionClock {
	private Instant now;

	TestClock(Instant start) {
		this.now = start;
	}

	@Override
	public synchronized Instant instant() {
		return now;
	}

	/**
	 * Waiting returns at once and the clock stays put, so timestamps in tests don't depend on how
	 * often the coordinator waits. Another player's calls "during" a wait are acted out with
	 * {@link InMemoryCloudStore#beforeNextLockWrite}.
	 */
	@Override
	public void sleep(Duration duration) {
	}

	synchronized void advance(Duration duration) {
		now = now.plus(duration);
	}
}
