package net.aii3n.worldgdrivesync.session;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldZipTest {
	/** Text that any deflate level shrinks a lot, so an entry that stayed this big wasn't deflated. */
	private static final byte[] COMPRESSIBLE = "stone stone dirt grass ".repeat(20_000).getBytes(StandardCharsets.UTF_8);

	@TempDir
	Path temp;

	@ParameterizedTest
	@ValueSource(strings = {"data/map.sqlite", "icon.png", "a.gz", "a.zip", "datapacks/pack.jar", "a.ogg", "a.mp3",
			"a.jpg", "a.jpeg", "a.webp", "PHOTO.PNG", "nested/deeper/Data.SqLite"})
	void alreadyCompressedFormatsGoIntoTheZipWithoutCompression(String name) throws IOException {
		ZipEntry entry = zippedEntry(name, COMPRESSIBLE);

		assertTrue(entry.getCompressedSize() >= COMPRESSIBLE.length, name + " took " + entry.getCompressedSize() + " bytes");
	}

	@ParameterizedTest
	@ValueSource(strings = {"region/r.0.0.mca", "level.dat", "stats/uuid.json", "png", "notes.png.txt"})
	void otherFilesAreCompressed(String name) throws IOException {
		ZipEntry entry = zippedEntry(name, COMPRESSIBLE);

		assertTrue(entry.getCompressedSize() < COMPRESSIBLE.length / 10, name + " took " + entry.getCompressedSize() + " bytes");
	}

	@Test
	void otherFilesUseTheFastestDeflateLevel() throws IOException {
		ZipEntry entry = zippedEntry("region/r.0.0.mca", COMPRESSIBLE);

		assertEquals(deflatedSize(COMPRESSIBLE, 1), entry.getCompressedSize());
		assertNotEquals(deflatedSize(COMPRESSIBLE, 6), entry.getCompressedSize());
	}

	@Test
	void aZippedWorldUnzipsByteForByte() throws IOException {
		// Names alternate between compressed and uncompressed formats, in the order they are packed.
		Map<String, byte[]> files = Map.of(
				"a.png", random(200_000),
				"b.mca", COMPRESSIBLE,
				"c.zip", COMPRESSIBLE,
				"d/e/level.dat", random(70_000),
				"d/e/map.sqlite", random(1),
				"d/empty.json", new byte[0],
				"d/empty.ogg", new byte[0],
				"f.txt", "hello".getBytes(StandardCharsets.UTF_8));
		Path world = temp.resolve("world");
		for (Map.Entry<String, byte[]> file : files.entrySet()) write(world.resolve(file.getKey()), file.getValue());
		Path zip = temp.resolve("world.zip");

		WorldZip.zip(world, zip, ProgressListener.NONE);
		Path unzipped = temp.resolve("unzipped");
		WorldZip.unzip(zip, unzipped, ProgressListener.NONE);

		assertContains(unzipped, files);
	}

	@Test
	void aZipPackedAtTheDefaultCompressionStillUnzips() throws IOException {
		// How worlds were packed before: ZipOutputStream's defaults for every file.
		Map<String, byte[]> files = Map.of("level.dat", random(1_000), "region/r.0.0.mca", COMPRESSIBLE, "icon.png", COMPRESSIBLE);
		Path zip = temp.resolve("old.zip");
		try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(zip))) {
			for (Map.Entry<String, byte[]> file : files.entrySet()) {
				out.putNextEntry(new ZipEntry(file.getKey()));
				out.write(file.getValue());
				out.closeEntry();
			}
		}

		Path unzipped = temp.resolve("unzipped");
		WorldZip.unzip(zip, unzipped, ProgressListener.NONE);

		assertContains(unzipped, files);
	}

	private static void assertContains(Path folder, Map<String, byte[]> files) throws IOException {
		try (Stream<Path> walk = Files.walk(folder)) {
			assertEquals(files.size(), walk.filter(Files::isRegularFile).count());
		}
		for (Map.Entry<String, byte[]> file : files.entrySet()) {
			assertArrayEquals(file.getValue(), Files.readAllBytes(folder.resolve(file.getKey())), file.getKey());
		}
	}

	private static byte[] random(int length) {
		byte[] bytes = new byte[length];
		new Random(length).nextBytes(bytes);
		return bytes;
	}

	/** The size of {@code content} as a raw deflate stream, which is what a zip entry holds. */
	private static long deflatedSize(byte[] content, int level) {
		Deflater deflater = new Deflater(level, true);
		try {
			deflater.setInput(content);
			deflater.finish();
			byte[] buffer = new byte[8192];
			while (!deflater.finished()) deflater.deflate(buffer);
			return deflater.getBytesWritten();
		} finally {
			deflater.end();
		}
	}

	private ZipEntry zippedEntry(String name, byte[] content) throws IOException {
		Path world = temp.resolve("world");
		write(world.resolve(name), content);
		Path zip = temp.resolve("world.zip");
		WorldZip.zip(world, zip, ProgressListener.NONE);
		try (ZipFile zipFile = new ZipFile(zip.toFile())) {
			return zipFile.getEntry(name);
		}
	}

	private static void write(Path file, byte[] content) throws IOException {
		Files.createDirectories(file.getParent());
		Files.write(file, content);
	}
}
