package net.aii3n.worldgdrivesync.drive;

import com.google.gson.JsonParser;
import net.aii3n.worldgdrivesync.session.StorageQuota;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.OptionalLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Reading Drive's answer to {@code about.get?fields=storageQuota}, whose numbers are strings. */
class StorageQuotaAnswerTest {
	private static StorageQuota read(String answer) throws IOException {
		return DriveCloudStore.storageQuota(JsonParser.parseString(answer).getAsJsonObject());
	}

	@Test
	void theLimitAndTheUsageAreInBytes() throws Exception {
		StorageQuota quota = read("{\"storageQuota\":{\"limit\":\"16106127360\",\"usage\":\"106127360\","
				+ "\"usageInDrive\":\"100\",\"usageInDriveTrash\":\"0\"}}");

		assertEquals(new StorageQuota(OptionalLong.of(16106127360L), 106127360L), quota);
		assertEquals(OptionalLong.of(16000000000L), quota.freeSpace());
	}

	@Test
	void noLimitMeansUnlimitedStorage() throws Exception {
		assertEquals(OptionalLong.empty(), read("{\"storageQuota\":{\"usage\":\"106127360\"}}").freeSpace());
	}

	@Test
	void anAccountOverItsLimitHasNoFreeSpace() throws Exception {
		assertEquals(OptionalLong.of(0), read("{\"storageQuota\":{\"limit\":\"10\",\"usage\":\"25\"}}").freeSpace());
	}

	@Test
	void anAnswerWithoutAQuotaIsAFailedRead() {
		assertThrows(IOException.class, () -> read("{}"));
	}
}
