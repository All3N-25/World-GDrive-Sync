package net.aii3n.worldgdrivesync.drive;

import com.google.gson.JsonObject;
import net.aii3n.worldgdrivesync.google.GoogleAccount;
import net.aii3n.worldgdrivesync.google.GoogleEndpoints;
import net.aii3n.worldgdrivesync.google.OAuthClient;
import net.aii3n.worldgdrivesync.session.StorageFullException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static net.aii3n.worldgdrivesync.drive.FakeDriveUploads.dropped;
import static net.aii3n.worldgdrivesync.drive.FakeDriveUploads.rateLimited;
import static net.aii3n.worldgdrivesync.drive.FakeDriveUploads.received;
import static net.aii3n.worldgdrivesync.drive.FakeDriveUploads.status;
import static net.aii3n.worldgdrivesync.drive.FakeDriveUploads.storageFull;
import static net.aii3n.worldgdrivesync.drive.FakeDriveUploads.stored;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A resumable upload on a bad connection: it resumes where Drive left off, and always ends. */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class ResumableUploadTest {
	@TempDir
	Path dir;
	FakeDriveUploads fake;
	DriveHttp drive;
	/** How long each back-off would have waited. */
	final List<Long> pauses = new ArrayList<>();
	final List<Long> progress = new ArrayList<>();

	@BeforeEach
	void startDrive() throws Exception {
		fake = new FakeDriveUploads();
		Path signIn = Files.writeString(dir.resolve("google-token.json"),
				"{\"refreshToken\":\"refresh\",\"accountEmail\":\"shared-worlds@example.com\"}");
		GoogleEndpoints endpoints = new GoogleEndpoints(null, fake.tokenEndpoint(), null, null);
		GoogleAccount account = new GoogleAccount(signIn, new OAuthClient("test-client-id", "test-client-secret"), endpoints);
		drive = new DriveHttp(account, DriveHttp.API, fake.uploadApi(), pauses::add);
	}

	@AfterEach
	void stopDrive() {
		fake.close();
	}

	/** Uploads a 10-byte zip. */
	private JsonObject upload() throws IOException {
		Path zip = Files.write(dir.resolve("current.zip"), new byte[10]);
		return drive.uploadNewFile(new JsonObject(), zip, progress::add);
	}

	@Test
	void aFailedStatusCheckIsRetriedAfterABackOff() throws Exception {
		fake.then(dropped(), dropped(), status(503), received(4), stored("file-1"));

		assertEquals("file-1", upload().get("id").getAsString());

		assertEquals(List.of("bytes 0-9/10", "bytes */10", "bytes */10", "bytes */10", "bytes 4-9/10"), fake.puts);
		assertEquals(3, pauses.size());
	}

	@Test
	void beingRateLimitedIsRetried() throws Exception {
		fake.then(rateLimited(), rateLimited(), stored("file-1"));

		assertEquals("file-1", upload().get("id").getAsString());

		assertEquals(2, pauses.size());
	}

	@Test
	void anUploadAFullDriveWontStartSaysItIsFullAndIsNotRetried() {
		fake.refusesToStart = storageFull();

		assertThrows(StorageFullException.class, this::upload);

		assertEquals(1, fake.starts.get());
		assertEquals(List.of(), pauses);
	}

	@Test
	void anUploadTheDriveFillsUpUnderSaysItIsFullAndIsNotRetried() {
		fake.otherwise = storageFull();

		assertThrows(StorageFullException.class, this::upload);

		assertEquals(1, fake.puts.size());
		assertEquals(List.of(), pauses);
	}

	@Test
	void aFullDriveRefusingAnyOtherRequestSaysSoAndIsNotRetried() {
		fake.refusesToStart = storageFull();

		assertThrows(StorageFullException.class,
				() -> drive.createSmallFile(new JsonObject(), "application/json", new byte[2]));

		assertEquals(1, fake.starts.get());
		assertEquals(List.of(), pauses);
	}

	@Test
	void interruptingAStalledChunkEndsTheUploadAtOnce() throws Exception {
		fake.otherwise = fake.stalled();

		assertInterruptEndsTheUpload();
	}

	@Test
	void interruptingAStalledSignInRenewalEndsTheUploadAtOnce() throws Exception {
		fake.tokenStalls = true;

		assertInterruptEndsTheUpload();
	}

	/** Starts an upload that stalls, interrupts it, and expects it to end well before any time-out. */
	private void assertInterruptEndsTheUpload() throws Exception {
		CompletableFuture<Throwable> ended = new CompletableFuture<>();
		Thread uploading = Thread.ofPlatform().daemon().start(() -> ended.complete(assertThrows(IOException.class, this::upload)));
		assertTrue(fake.stalling.await(5, TimeUnit.SECONDS));

		uploading.interrupt();

		ended.get(5, TimeUnit.SECONDS);
	}

	@Test
	void anOutageLongerThanTheAttemptsEndsTheUpload() {
		fake.otherwise = dropped();

		assertThrows(IOException.class, this::upload);

		assertEquals(4, fake.puts.size());
		assertEquals(3, pauses.size());
	}

	@Test
	void anUploadThatMakesNoProgressGivesUp() {
		fake.otherwise = received(0);

		assertThrows(IOException.class, this::upload);

		assertEquals(4, fake.puts.size());
		assertEquals(3, pauses.size());
	}

	@Test
	void theSameBytesReportedAgainAfterAFailureAreNotProgress() {
		fake.then(received(4), dropped(), received(4), received(4), received(2), received(4));
		fake.otherwise = received(4);

		assertThrows(IOException.class, this::upload);

		assertEquals(List.of("bytes 0-9/10", "bytes 4-9/10", "bytes */10", "bytes 4-9/10", "bytes 4-9/10",
				"bytes 2-9/10"), fake.puts);
	}

	@Test
	void progressStartsTheAttemptsAfresh() throws Exception {
		fake.then(received(0), received(0), received(0), received(2),
				received(2), received(2), received(2), received(4),
				dropped(), dropped(), dropped(), received(6), stored("file-1"));

		assertEquals("file-1", upload().get("id").getAsString());
	}

	@Test
	void everyByteReportedReceivedAsksForTheStoredFile() throws Exception {
		fake.then(received(10), stored("file-1"));

		assertEquals("file-1", upload().get("id").getAsString());

		assertEquals(List.of("bytes 0-9/10", "bytes */10"), fake.puts);
		assertEquals(10, progress.getLast());
	}

	@Test
	void aFailedAskForTheStoredFileIsRetried() throws Exception {
		fake.then(received(10), dropped(), received(10), stored("file-1"));

		assertEquals("file-1", upload().get("id").getAsString());

		assertEquals(List.of("bytes 0-9/10", "bytes */10", "bytes */10", "bytes */10"), fake.puts);
	}

	@Test
	void theStoredFileNeverComingGivesUp() {
		fake.otherwise = received(10);

		assertThrows(IOException.class, this::upload);

		assertEquals(5, fake.puts.size());
		assertEquals(3, pauses.size());
	}
}
