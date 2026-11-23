package net.aii3n.worldgdrivesync.session;

import java.time.Duration;
import java.time.Instant;
import java.time.InstantSource;

/** A clock that only moves when the test says so. */
final class TestClock implements InstantSource {
	private Instant now;

	TestClock(Instant start) {
		this.now = start;
	}

	@Override
	public synchronized Instant instant() {
		return now;
	}

	synchronized void advance(Duration duration) {
		now = now.plus(duration);
	}
}
