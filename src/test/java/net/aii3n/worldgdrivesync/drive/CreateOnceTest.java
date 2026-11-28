package net.aii3n.worldgdrivesync.drive;

import com.google.gson.JsonObject;
import net.aii3n.worldgdrivesync.google.GoogleAccount;
import net.aii3n.worldgdrivesync.google.GoogleEndpoints;
import net.aii3n.worldgdrivesync.google.OAuthClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Making a folder when Drive's answers get lost: one folder, or none. */
@Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class CreateOnceTest {
	@TempDir
	Path dir;
	FakeDriveFiles fake;
	DriveHttp drive;

	@BeforeEach
	void startDrive() throws Exception {
		fake = new FakeDriveFiles();
		Path signIn = Files.writeString(dir.resolve("google-token.json"),
				"{\"refreshToken\":\"refresh\",\"accountEmail\":\"shared-worlds@example.com\"}");
		GoogleEndpoints endpoints = new GoogleEndpoints(null, fake.tokenEndpoint(), null, null);
		GoogleAccount account = new GoogleAccount(signIn, new OAuthClient("test-client-id", "test-client-secret"), endpoints);
		drive = new DriveHttp(account, fake.api(), fake.api(), millis -> {
		});
	}

	@AfterEach
	void stopDrive() {
		fake.close();
	}

	private JsonObject create() throws IOException {
		JsonObject folder = new JsonObject();
		folder.addProperty("name", "Survival");
		return drive.createOnce(folder);
	}

	@Test
	void aFolderWhoseCreationIsAnsweredIsMadeOnce() throws Exception {
		String id = create().get("id").getAsString();

		assertEquals(Map.of(id, "Survival"), fake.files);
	}

	@Test
	void aLostAnswerDoesntLeaveASecondFolder() throws Exception {
		fake.answersToLose = 1;

		JsonObject folder = create();

		assertEquals(Map.of(folder.get("id").getAsString(), "Survival"), fake.files);
		assertEquals("Survival", folder.get("name").getAsString());
	}

	@Test
	void ifEveryAnswerIsLostTheFolderIsDeleted() {
		fake.answersToLose = Integer.MAX_VALUE;

		assertThrows(IOException.class, this::create);

		assertEquals(Map.of(), fake.files);
	}

	@Test
	void aFailedCleanUpDoesntHideWhyTheFolderCouldntBeMade() {
		fake.answersToLose = Integer.MAX_VALUE;
		fake.refusesDeletes = true;

		IOException failure = assertThrows(IOException.class, this::create);

		assertEquals(1, failure.getSuppressed().length);
		assertTrue(failure.getSuppressed()[0].getMessage().contains("HTTP 403"), failure.getSuppressed()[0].getMessage());
		assertEquals(1, fake.files.size());
	}
}
