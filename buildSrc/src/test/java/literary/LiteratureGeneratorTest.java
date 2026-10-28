package literary;

import static org.junit.Assert.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

public class LiteratureGeneratorTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final Path root = Path.of(System.getProperty("literature.projectRoot"));
    private final Map<Document, Element> selectionRoots = new java.util.IdentityHashMap<>();

    @Test public void parsesLiteralPipesAndPreservesQuotationMarksAndContentRating() throws Exception {
        Path source = input("\uFEFF23:59|a minute|\"A minute\", she said.<br/>Then 100% silence.|Book & sequel|O'Neil|nsfw\n");
        var passage = LiteratureGenerator.parse(source).get(0);
        assertEquals(1439, passage.minute());
        assertEquals("\"A minute\", she said.\nThen 100% silence.", passage.text());
        assertEquals("Book & sequel", passage.title());
        assertEquals("O'Neil", passage.author());
    }

    @Test public void literalHighlightPreservesCaseAndAllRepeatedOccurrences() {
        var passage = new LiteratureGenerator.Passage(1, 0, "a.m. (12)",
                "Before A.M. (12), then a.m. (12) afterward.", "Title", "Author");
        var runs = LiteratureGenerator.runs(passage);
        assertEquals(passage.text(), runs.stream().map(LiteratureGenerator.Run::text).reduce("", String::concat));
        assertEquals(List.of("A.M. (12)", "a.m. (12)"), runs.stream()
                .filter(LiteratureGenerator.Run::highlighted).map(LiteratureGenerator.Run::text).toList());
    }

    @Test public void rejectsBrokenRecordsWithLineNumbers() throws Exception {
        for (String row : List.of(
                "24:00|midnight|At midnight.|T|A|sfw",
                "1:00|one|It is one.|T|A|sfw",
                "01:00|two|It is one.|T|A|sfw",
                "01:00||It is one.|T|A|sfw",
                "01:00|one|It is one.|T|A")) {
            IOException error = assertThrows(IOException.class, () -> LiteratureGenerator.parse(input(row)));
            assertTrue(error.getMessage(), error.getMessage().contains("line 1"));
        }
        assertThrows(IOException.class, () -> LiteratureGenerator.parse(input("\n")));
    }

    @Test public void quotesAndroidStringsWithoutLosingSpacesOrSpecialCharacters() {
        assertEquals("\" leading \\\"quote\\\" O\\'Neil 100% \\\\ @test\\nend \"",
                LiteratureGenerator.androidString(" leading \"quote\" O'Neil 100% \\ @test\nend "));
    }

    @Test public void fitsLongMultilinePassagesAndMeasuresHighlightedText() throws Exception {
        var passage = new LiteratureGenerator.Passage(1, 0, "midnight",
                ("A long passage with MIDNIGHT in the middle, and more words to follow. ").repeat(8)
                        + "\nA final line.", "Title", "Author");
        var runs = LiteratureGenerator.runs(passage);
        int size = LiteratureGenerator.fitSize(runs, 32, 12, 350, 266, 1, "passage");
        assertTrue(size >= 12 && size < 32);
        assertTrue(LiteratureGenerator.measuredHeight(runs, size, 350 * 0.94f) <= 260);
        assertThrows(IOException.class, () -> LiteratureGenerator.fitSize(runs, 32, 12, 30, 20, 1, "passage"));
    }

    @Test public void generatesCorrectMinuteDailyRotationFallbackAndInlineText() throws Exception {
        Path source = input("00:00|midnight|At MIDNIGHT, 100% & quiet.|Book One|Author One|sfw\n"
                + "00:00|midnight|It was midnight.|Book Two|Author Two|nsfw\n"
                + "23:59|one minute|Only one minute remained.|Last Book|Last Author|unknown\n");
        var generated = generate(source);
        assertEquals(3, generated.result().passageCount());
        assertEquals(2, generated.result().coveredMinutes());
        assertEquals(1438, generated.result().missingTimes().size());
        assertEquals("lit_1", selected(generated.face(), 0, 2).getAttribute("name"));
        assertEquals("lit_2", selected(generated.face(), 0, 3).getAttribute("name"));
        assertEquals("lit_3", selected(generated.face(), 1439, 3).getAttribute("name"));
        assertEquals("missing_hour_6", selected(generated.face(), 367, 3).getAttribute("name"));
        Element quote = LiteratureGenerator.find(generated.face().getDocumentElement(), "PartText", "lit_1_quote_body");
        var fonts = quote.getElementsByTagName("Font");
        assertEquals(3, fonts.getLength());
        assertEquals("[CONFIGURATION.theme.4]", ((Element) fonts.item(1)).getAttribute("color"));
        assertEquals("BOLD", ((Element) fonts.item(1)).getAttribute("weight"));
        assertEquals("TRUE", ((Element) quote.getElementsByTagName("Text").item(0)).getAttribute("isAutoSize"));
        assertEquals("FALSE", ((Element) quote.getElementsByTagName("Text").item(0)).getAttribute("ellipsis"));
        // Adjacent inline runs must not gain indentation or lose boundary whitespace.
        assertEquals("\"At \"", generated.strings().get("lit_1_run_0"));
        assertEquals("\"MIDNIGHT\"", generated.strings().get("lit_1_run_1"));
        assertEquals("\", 100% & quiet.\"", generated.strings().get("lit_1_run_2"));
        assertEquals("\"Book One\"", generated.strings().get("lit_1_title"));
        assertEquals("\"Author One\"", generated.strings().get("lit_1_author"));
    }

    @Test public void realDatasetKeepsEveryPassageAndSelectsTheCorrectTimeAcrossTheDay() throws Exception {
        Path source = root.resolve("literature/litclock_annotated.csv");
        List<LiteratureGenerator.Passage> passages = LiteratureGenerator.parse(source);
        var minutes = LiteratureGenerator.byMinute(passages);
        var generated = generate(source);
        assertEquals(passages.size(), generated.result().passageCount());
        assertEquals(minutes.size(), generated.result().coveredMinutes());
        assertEquals(1440 - minutes.size(), generated.result().missingTimes().size());

        for (int minute = 0; minute < 1440; minute++) {
            for (int day : new int[] {1, 2, 31, 365, 366}) {
                Element group = selected(generated.face(), minute, day);
                var choices = minutes.get(minute);
                String expected = choices == null ? "missing_hour_" + minute / 60
                        : "lit_" + choices.get(day % choices.size()).line();
                assertEquals("Minute " + minute + ", day " + day, expected, group.getAttribute("name"));
            }
        }
        // Every passage survives generation, and all substring matches preserve the whole text.
        for (var passage : passages) {
            assertTrue(generated.strings().containsKey("lit_" + passage.line() + "_title"));
            var runs = LiteratureGenerator.runs(passage);
            assertEquals(passage.text(), runs.stream().map(LiteratureGenerator.Run::text).reduce("", String::concat));
            assertTrue(runs.stream().anyMatch(LiteratureGenerator.Run::highlighted));
        }
        Set<String> names = new HashSet<>();
        var groups = generated.face().getElementsByTagName("Group");
        for (int index = 0; index < groups.getLength(); index++) {
            assertTrue("Duplicate group name", names.add(((Element) groups.item(index)).getAttribute("name")));
        }
        var parameters = generated.face().getElementsByTagName("Parameter");
        for (int index = 0; index < parameters.getLength(); index++) {
            String expression = ((Element) parameters.item(index)).getAttribute("expression");
            if (expression.startsWith("lit_")) assertTrue("Missing resource " + expression, generated.strings().containsKey(expression));
        }
        assertEquals(1, generated.face().getElementsByTagName("WatchFace").getLength());
        assertEquals("46", LiteratureGenerator.find(generated.face().getDocumentElement(), "PartText", "date").getAttribute("y"));
    }

    private Path input(String text) throws Exception {
        Path path = temporary.newFile().toPath();
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    private record Generated(LiteratureGenerator.Result result, Document face, Map<String, String> strings) {}

    private Generated generate(Path source) throws Exception {
        Path output = temporary.newFolder().toPath();
        var result = LiteratureGenerator.generate(source, root.resolve("watchface/src/main/watchface-template.xml"),
                output, output.resolve("generation.json"));
        Document face = LiteratureGenerator.readXml(Files.readString(output.resolve("raw/watchface.xml")));
        Document resourceDocument = LiteratureGenerator.readXml(Files.readString(output.resolve("values/literature.xml")));
        Map<String, String> strings = new HashMap<>();
        var resources = resourceDocument.getElementsByTagName("string");
        for (int index = 0; index < resources.getLength(); index++) {
            Element resource = (Element) resources.item(index);
            assertNull("Duplicate string resource", strings.put(resource.getAttribute("name"), resource.getTextContent()));
        }
        Element active = LiteratureGenerator.find(face.getDocumentElement(), "Group", "literature_active");
        selectionRoots.put(face, firstChild(active, "Condition"));
        return new Generated(result, face, strings);
    }

    /** Evaluate the small generated selection grammar, including its Default branches. */
    private Element selected(Document face, int minuteOfDay, int day) throws Exception {
        return select(selectionRoots.get(face), minuteOfDay, day);
    }

    private Element select(Element condition, int minuteOfDay, int day) throws Exception {
        Map<String, Boolean> values = new HashMap<>();
        for (Element expression : children(firstChild(condition, "Expressions"), "Expression")) {
            String value = expression.getTextContent();
            var equal = Pattern.compile("\\[(HOUR_0_23|MINUTE)] == ([0-9]+)").matcher(value);
            var rotation = Pattern.compile("\\[DAY_OF_YEAR] % ([0-9]+) == ([0-9]+)").matcher(value);
            boolean matches;
            if (equal.matches()) {
                int actual = equal.group(1).equals("HOUR_0_23") ? minuteOfDay / 60 : minuteOfDay % 60;
                matches = actual == Integer.parseInt(equal.group(2));
            } else {
                assertTrue(value, rotation.matches());
                matches = day % Integer.parseInt(rotation.group(1)) == Integer.parseInt(rotation.group(2));
            }
            values.put(expression.getAttribute("name"), matches);
        }
        Element selected = null;
        for (Element compare : children(condition, "Compare")) {
            if (values.get(compare.getAttribute("expression"))) {
                assertNull("More than one matching branch", selected);
                selected = firstElement(compare);
            }
        }
        if (selected == null) selected = firstElement(firstChild(condition, "Default"));
        return selected.getTagName().equals("Condition") ? select(selected, minuteOfDay, day) : selected;
    }

    private static List<Element> children(Element parent, String tag) {
        var result = new java.util.ArrayList<Element>();
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element && ((Element) child).getTagName().equals(tag)) result.add((Element) child);
        }
        return result;
    }

    private static Element firstChild(Element parent, String tag) { return children(parent, tag).get(0); }
    private static Element firstElement(Element parent) {
        for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) return (Element) child;
        }
        throw new AssertionError("Missing child element");
    }
}
