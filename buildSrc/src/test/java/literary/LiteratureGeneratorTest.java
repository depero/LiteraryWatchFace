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
    private final Map<Document, Map<String, Element>> modeRoots = new java.util.IdentityHashMap<>();

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

    @Test public void preservesLiteralSpacesQuotesPercentAndXmlCharacters() throws Exception {
        String passage = "Before \"MIDNIGHT\", O'Neil saw 100% & a \\ path.<br/>The end.";
        var generated = generate(input("00:00|midnight|" + passage + "|Book & title|O'Neil|sfw\n"));
        Element body = LiteratureGenerator.find(generated.face().getDocumentElement(), "PartText", "lit_1_quote_body");
        assertEquals(passage.replace("<br/>", "\n"), firstChild(body, "Text").getTextContent());
        assertEquals(0, body.getElementsByTagName("Parameter").getLength());
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
        assertEquals("lit_1", selectionId(selected(generated.face(), 0, 2)));
        assertEquals("lit_2", selectionId(selected(generated.face(), 0, 3)));
        assertEquals("lit_3", selectionId(selected(generated.face(), 1439, 3)));
        assertEquals("missing_hour_6", selectionId(selected(generated.face(), 367, 3)));
        Element quote = LiteratureGenerator.find(generated.face().getDocumentElement(), "PartText", "lit_1_quote_body");
        var fonts = quote.getElementsByTagName("Font");
        assertEquals(3, fonts.getLength());
        assertEquals("[CONFIGURATION.theme.4]", ((Element) fonts.item(1)).getAttribute("color"));
        assertEquals("BOLD", ((Element) fonts.item(1)).getAttribute("weight"));
        assertEquals("TRUE", ((Element) quote.getElementsByTagName("Text").item(0)).getAttribute("isAutoSize"));
        assertEquals("FALSE", ((Element) quote.getElementsByTagName("Text").item(0)).getAttribute("ellipsis"));
        // Adjacent inline runs must not gain indentation or lose boundary whitespace.
        assertEquals("At ", fonts.item(0).getTextContent());
        assertEquals("MIDNIGHT", fonts.item(1).getTextContent());
        assertEquals(", 100% & quiet.", fonts.item(2).getTextContent());
        assertEquals("Book One", LiteratureGenerator.find(generated.face().getDocumentElement(), "PartText", "lit_1_book_title").getElementsByTagName("Font").item(0).getTextContent());
        assertEquals("Author One", LiteratureGenerator.find(generated.face().getDocumentElement(), "PartText", "lit_1_book_author").getElementsByTagName("Font").item(0).getTextContent());
    }

    @Test public void centersShortAndWrappedPassagesWithinTheMainArea() throws Exception {
        Path source = input("00:00|midnight|At midnight.|Book|Author|sfw\n"
                + "00:01|midnight|" + ("At midnight the story continues with another sentence. ").repeat(8)
                + "|Book|Author|sfw\n");
        var generated = generate(source);
        Element shortBody = LiteratureGenerator.find(generated.face().getDocumentElement(), "PartText", "lit_1_quote_body");
        Element longBody = LiteratureGenerator.find(generated.face().getDocumentElement(), "PartText", "lit_2_quote_body");
        assertTrue(Integer.parseInt(shortBody.getAttribute("height")) < Integer.parseInt(longBody.getAttribute("height")));
        for (Element body : List.of(shortBody, longBody)) {
            int top = Integer.parseInt(body.getAttribute("y"));
            int height = Integer.parseInt(body.getAttribute("height"));
            assertEquals(225.0, top + height / 2.0, 0.5);
            assertTrue(top >= 92);
            assertTrue(top + height <= 358);
            assertEquals("350", body.getAttribute("width"));
        }
        Element date = LiteratureGenerator.find(generated.face().getDocumentElement(), "PartText", "date");
        Element battery = LiteratureGenerator.find(generated.face().getDocumentElement(), "PartText", "battery");
        Element attribution = LiteratureGenerator.find(generated.face().getDocumentElement(), "PartText", "lit_1_book_title");
        assertEquals("46", date.getAttribute("y"));
        assertEquals("20", battery.getAttribute("y"));
        assertEquals("364", attribution.getAttribute("y"));
    }

    @Test public void compactAmbientLayoutsKeepPersistentVisibilityAndIdenticalGeometry() throws Exception {
        Path source = input("00:00|a quarter past one|Before A QUARTER PAST ONE, the story continues.<br/>Later, a quarter past one again.|Book|Author|sfw\n"
                + "00:01|two minutes|After TWO MINUTES, another passage begins.|Another book|Another author|sfw\n");
        var generated = generate(source);
        Element active = LiteratureGenerator.find(generated.face().getDocumentElement(), "Group", "literature_active");
        Element idleParent = LiteratureGenerator.find(generated.face().getDocumentElement(), "Group", "literature_ambient");
        assertAmbientAlpha(active, "0");
        assertAmbientAlpha(idleParent, "255");
        assertEquals("0", idleParent.getAttribute("alpha"));
        for (int minute : new int[] {0, 1}) {
            Element quote = selected(generated.face(), minute, 1);
            Element idleQuote = selectedMode(generated.face(), "ambient_phrase", minute, 1);
            assertEquals("Minute-switched branches must inherit visibility", 0, quote.getElementsByTagName("Variant").getLength());
            assertEquals(0, idleQuote.getElementsByTagName("Variant").getLength());
            String id = "lit_" + (minute + 1);
            Element awake = LiteratureGenerator.find(quote, "PartText", id + "_quote_body");
            Element ambient = LiteratureGenerator.find(idleQuote, "PartText", id + "_quote_body_ambient");
            for (String attribute : List.of("x", "y", "width", "height")) {
                assertEquals(attribute, awake.getAttribute(attribute), ambient.getAttribute(attribute));
            }
            var awakeFonts = children(firstChild(awake, "Text"), "Font");
            var ambientFonts = children(firstChild(ambient, "Text"), "Font");
            assertEquals(awakeFonts.size(), ambientFonts.size());
            for (int index = 0; index < awakeFonts.size(); index++) {
                Element normal = awakeFonts.get(index);
                Element idle = ambientFonts.get(index);
                boolean time = normal.getAttribute("color").equals("[CONFIGURATION.theme.4]");
                for (String attribute : List.of("family", "size", "weight", "slant", "letterSpacing")) {
                    assertEquals(attribute, normal.getAttribute(attribute), idle.getAttribute(attribute));
                }
                assertEquals(normal.getTextContent(), idle.getTextContent());
                assertEquals(time ? "#FFFFFFFF" : "#00000000", resolvedAodColor(generated.face(), idle, "phrase"));
                assertEquals(time ? "#FFFFFFFF" : "#FF999999", resolvedAodColor(generated.face(), idle, "full"));
            }
            Element title = LiteratureGenerator.find(idleQuote, "PartText", id + "_book_title_ambient");
            Element author = LiteratureGenerator.find(idleQuote, "PartText", id + "_book_author_ambient");
            assertEquals("85", title.getAttribute("x"));
            assertEquals("364", title.getAttribute("y"));
            assertEquals("105", author.getAttribute("x"));
            assertEquals("400", author.getAttribute("y"));
            for (Element part : List.of(title, author)) {
                assertEquals("Per-entry visibility bindings must not exhaust the renderer heap", 0,
                        part.getElementsByTagName("Transform").getLength());
                Element font = (Element) part.getElementsByTagName("Font").item(0);
                assertEquals("#00000000", resolvedAodColor(generated.face(), font, "phrase"));
                assertEquals("#FF888888", resolvedAodColor(generated.face(), font, "full"));
            }
        }
        Element fallback = selectedMode(generated.face(), "ambient_full", 367, 1);
        assertEquals(0, fallback.getElementsByTagName("PartText").getLength());
        assertEquals(1, fallback.getElementsByTagName("TimeText").getLength());
        assertEquals("#FFFFFFFF", ((Element) fallback.getElementsByTagName("Font").item(0)).getAttribute("color"));
        var groups = generated.face().getElementsByTagName("Group");
        for (int index = 0; index < groups.getLength(); index++) {
            assertFalse("Redundant entry/attribution scene objects must be flattened",
                    ((Element) groups.item(index)).getAttribute("name").startsWith("lit_"));
        }
        for (String name : List.of("background", "supporting_content", "battery_status")) {
            assertAmbientAlpha(LiteratureGenerator.find(generated.face().getDocumentElement(), "Group", name), "0");
        }
        assertEquals("Generated scene formatting must be removed", 2, active.getChildNodes().getLength());
    }
    private static void assertAmbientAlpha(Element element, String value) {
        var variants = children(element, "Variant");
        assertEquals(1, variants.size());
        assertEquals("AMBIENT", variants.get(0).getAttribute("mode"));
        assertEquals("alpha", variants.get(0).getAttribute("target"));
        assertEquals(value, variants.get(0).getAttribute("value"));
    }

    private static String resolvedAodColor(Document document, Element font, String optionId) {
        String color = font.getAttribute("color");
        var reference = Pattern.compile("\\[CONFIGURATION\\.aod_mode\\.([0-9]+)]").matcher(color);
        if (!reference.matches()) return color;
        var configurations = document.getElementsByTagName("ColorConfiguration");
        for (int index = 0; index < configurations.getLength(); index++) {
            Element configuration = (Element) configurations.item(index);
            if (!configuration.getAttribute("id").equals("aod_mode")) continue;
            for (Element option : children(configuration, "ColorOption")) {
                if (option.getAttribute("id").equals(optionId)) {
                    return option.getAttribute("colors").split(" ")[Integer.parseInt(reference.group(1))];
                }
            }
        }
        throw new AssertionError("Missing AoD palette option " + optionId);
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
                assertEquals("Minute " + minute + ", day " + day, expected, selectionId(group));
            }
        }
        // Every passage survives generation, and all substring matches preserve the whole text.
        Map<String, Element> textParts = new HashMap<>();
        var allParts = generated.face().getElementsByTagName("PartText");
        assertEquals("Three text layouts per passage and mode, plus fallback messages and header",
                passages.size() * 6 + 26, allParts.getLength());
        for (int index = 0, count = allParts.getLength(); index < count; index++) {
            Element part = (Element) allParts.item(index);
            textParts.put(part.getAttribute("name"), part);
        }
        for (var passage : passages) {
            Element body = textParts.get("lit_" + passage.line() + "_quote_body");
            assertEquals(passage.text(), firstChild(body, "Text").getTextContent());
            assertEquals(passage.title(), textParts.get("lit_" + passage.line() + "_book_title").getElementsByTagName("Font").item(0).getTextContent());
            assertEquals(passage.author(), textParts.get("lit_" + passage.line() + "_book_author").getElementsByTagName("Font").item(0).getTextContent());
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
            assertFalse("Static literature must not allocate live expression bindings", expression.startsWith("lit_"));
        }
        assertEquals(1, generated.face().getElementsByTagName("WatchFace").getLength());
        assertEquals("46", LiteratureGenerator.find(generated.face().getDocumentElement(), "PartText", "date").getAttribute("y"));
    }

    private Path input(String text) throws Exception {
        Path path = temporary.newFile().toPath();
        Files.writeString(path, text, StandardCharsets.UTF_8);
        return path;
    }

    private record Generated(LiteratureGenerator.Result result, Document face) {}

    private Generated generate(Path source) throws Exception {
        Path output = temporary.newFolder().toPath();
        var result = LiteratureGenerator.generate(source, root.resolve("watchface/src/main/watchface-template.xml"),
                output, output.resolve("generation.json"));
        Document face = LiteratureGenerator.readXml(Files.readString(output.resolve("raw/watchface.xml")));
        assertFalse(Files.exists(output.resolve("values/literature.xml")));
        Element active = LiteratureGenerator.find(face.getDocumentElement(), "Group", "literature_active");
        selectionRoots.put(face, firstChild(active, "Condition"));
        Map<String, Element> roots = new HashMap<>();
        for (String mode : List.of("ambient_phrase", "ambient_full")) {
            Element container = LiteratureGenerator.find(face.getDocumentElement(), "Group", "ambient_quote");
            roots.put(mode, firstChild(container, "Condition"));
        }
        modeRoots.put(face, roots);
        return new Generated(result, face);
    }

    /** Evaluate the small generated selection grammar, including its Default branches. */
    private Element selected(Document face, int minuteOfDay, int day) throws Exception {
        return select(selectionRoots.get(face), minuteOfDay, day);
    }

    private Element selectedMode(Document face, String mode, int minuteOfDay, int day) throws Exception {
        return select(modeRoots.get(face).get(mode), minuteOfDay, day);
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
                Element content = firstElement(compare);
                selected = content.getTagName().equals("PartText") ? compare : content;
            }
        }
        if (selected == null) selected = firstElement(firstChild(condition, "Default"));
        return selected.getTagName().equals("Condition") ? select(selected, minuteOfDay, day) : selected;
    }

    private static String selectionId(Element selection) {
        if (selection.getTagName().equals("Group")) return selection.getAttribute("name");
        return firstChild(selection, "PartText").getAttribute("name").replaceFirst("_quote_body.*$", "");
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
