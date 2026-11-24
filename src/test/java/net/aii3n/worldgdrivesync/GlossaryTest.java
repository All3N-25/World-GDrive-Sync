package net.aii3n.worldgdrivesync;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the words GLOSSARY.md lists under "_Avoid_" out of the text players read and out of the
 * names in the pure-Java rules code. Minecraft code is left out: its own API is full of avoided
 * words such as Server, Client and save.
 */
class GlossaryTest {
	private static final Path GLOSSARY = Path.of("GLOSSARY.md");
	private static final Path LANG = Path.of("src/main/resources/assets/world-gdrive-sync/lang/en_us.json");
	private static final List<Path> RULES_CODE = List.of(
			Path.of("src/main/java/net/aii3n/worldgdrivesync/session"),
			Path.of("src/main/java/net/aii3n/worldgdrivesync/settings"));

	/**
	 * Phrases where an avoided word means something else, such as "save" the action rather than the
	 * world. Add one only when the word doesn't stand for a glossary concept.
	 */
	private static final List<String> ALLOWED_PHRASES = List.of(
			"save and quit", "save it", "save properly", "OAuth client");

	@Test
	void playerFacingTextUsesGlossaryTerms() throws IOException {
		Glossary glossary = Glossary.read();
		JsonObject lang = JsonParser.parseString(Files.readString(LANG)).getAsJsonObject();
		List<String> hits = new ArrayList<>();
		for (Map.Entry<String, com.google.gson.JsonElement> entry : lang.entrySet()) {
			String text = glossary.withoutTerms(entry.getValue().getAsString());
			for (String phrase : ALLOWED_PHRASES) text = replaceIgnoringCase(text, phrase);
			for (String avoided : glossary.avoided()) {
				if (containsWord(text, avoided)) hits.add(entry.getKey() + ": \"" + avoided + "\"");
			}
		}
		assertTrue(hits.isEmpty(), "Avoided glossary words in en_us.json (see GLOSSARY.md):\n" + String.join("\n", hits));
	}

	@Test
	void rulesCodeNamesUseGlossaryTerms() throws IOException {
		Glossary glossary = Glossary.read();
		List<String> hits = new ArrayList<>();
		for (Path dir : RULES_CODE) {
			try (Stream<Path> files = Files.walk(dir)) {
				for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted(Comparator.naturalOrder()).toList()) {
					for (String name : declaredNames(Files.readString(file))) {
						String words = glossary.withoutTerms(splitCamelCase(name));
						for (String avoided : glossary.avoided()) {
							if (containsWord(words, avoided)) hits.add(file.getFileName() + ": " + name + " (\"" + avoided + "\")");
						}
					}
				}
			}
		}
		assertTrue(hits.isEmpty(), "Avoided glossary words in rules code names (see GLOSSARY.md):\n" + String.join("\n", hits));
	}

	/**
	 * The names this file chooses: every identifier except comments, strings, annotations, imported
	 * names and members reached with a dot, which belong to other code such as the JDK.
	 */
	private static TreeSet<String> declaredNames(String source) {
		String code = source.replaceAll("(?s)/\\*.*?\\*/", " ")
				.replaceAll("//[^\\n]*", " ")
				.replaceAll("\"(?:\\\\.|[^\"\\\\])*\"", " ")
				.replaceAll("'(?:\\\\.|[^'\\\\])'", " ");
		TreeSet<String> imported = new TreeSet<>();
		Matcher imports = Pattern.compile("(?m)^import\\s+(?:static\\s+)?[\\w.]*?(\\w+)\\s*;").matcher(code);
		while (imports.find()) imported.add(imports.group(1));
		code = code.replaceAll("(?m)^(?:package|import)\\b[^;]*;", " ")
				.replaceAll("@\\w+", " ")
				.replaceAll("(?:\\.|::)\\s*(?:<[^>]*>\\s*)?\\w+", " ");
		TreeSet<String> names = new TreeSet<>();
		Matcher identifiers = Pattern.compile("\\b[A-Za-z_]\\w*\\b").matcher(code);
		while (identifiers.find()) {
			if (!imported.contains(identifiers.group())) names.add(identifiers.group());
		}
		return names;
	}

	private static String splitCamelCase(String name) {
		return name.replace('_', ' ')
				.replaceAll("([a-z0-9])([A-Z])", "$1 $2")
				.replaceAll("([A-Z]+)([A-Z][a-z])", "$1 $2");
	}

	private static boolean containsWord(String text, String word) {
		String spaced = word.replace("-", "[- ]?");
		return Pattern.compile("\\b" + spaced.replace(" ", "\\s+") + "s?\\b", Pattern.CASE_INSENSITIVE).matcher(text).find();
	}

	private static String replaceIgnoringCase(String text, String phrase) {
		return Pattern.compile(Pattern.quote(phrase), Pattern.CASE_INSENSITIVE).matcher(text).replaceAll(" ");
	}

	/** The glossary's terms, and the words it says to avoid. */
	private record Glossary(List<String> terms, List<String> avoided) {
		static Glossary read() throws IOException {
			String text = Files.readString(GLOSSARY);
			List<String> terms = new ArrayList<>();
			Matcher term = Pattern.compile("(?m)^\\*\\*(.+?)\\*\\*:").matcher(text);
			while (term.find()) terms.add(term.group(1));
			terms.sort(Comparator.comparingInt(String::length).reversed());
			List<String> avoided = new ArrayList<>();
			Matcher avoid = Pattern.compile("(?m)^_Avoid_:(.*)$").matcher(text);
			while (avoid.find()) {
				for (String word : avoid.group(1).split(",")) {
					if (!word.isBlank() && !avoided.contains(word.strip().toLowerCase())) avoided.add(word.strip().toLowerCase());
				}
			}
			assertTrue(!terms.isEmpty() && !avoided.isEmpty(), "GLOSSARY.md has no terms or no _Avoid_ lines");
			return new Glossary(terms, avoided);
		}

		/** Blanks out the glossary's own terms, so "Current Save" doesn't count as the avoided "save". */
		String withoutTerms(String text) {
			for (String t : terms) text = replaceIgnoringCase(text, t);
			return text;
		}
	}
}
