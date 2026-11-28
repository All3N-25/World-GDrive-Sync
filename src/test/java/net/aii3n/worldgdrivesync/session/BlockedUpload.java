package net.aii3n.worldgdrivesync.session;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Holds the next upload that replaces the Current Save until the test lets it go, like a very slow one. */
final class BlockedUpload implements AutoCloseable {
	private final CountDownLatch started = new CountDownLatch(1);
	private final CountDownLatch released = new CountDownLatch(1);

	BlockedUpload(InMemoryCloudStore cloud) {
		cloud.beforeNext(InMemoryCloudStore.Call.REPLACE, () -> {
			started.countDown();
			await(released);
		});
	}

	/** Returns once the upload is in progress. */
	void awaitStarted() throws IOException {
		await(started);
	}

	/** Lets the upload finish. */
	@Override
	public void close() {
		released.countDown();
	}

	private static void await(CountDownLatch latch) throws IOException {
		try {
			if (!latch.await(10, TimeUnit.SECONDS)) throw new IOException("Waited too long");
		} catch (InterruptedException e) {
			throw new InterruptedIOException();
		}
	}
}
