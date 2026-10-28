package literary;

import java.awt.Font;
import java.awt.font.FontRenderContext;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;

/** Converts the pipe-delimited source into minute-selected, styled WFF resources. */
public final class LiteratureGenerator {
    private static final Pattern TIME = Pattern.compile("([01][0-9]|2[0-3]):([0-5][0-9])");
    private static final Pattern BREAK = Pattern.compile("(?i)<br\\s*/?>");
    private static final Pattern TIME_TAG = Pattern.compile("(?i)</?time\\s*/?>");
    private static final FontRenderContext FONT_CONTEXT = new FontRenderContext(null, true, true);

    public record Passage(int line, int minute, String phrase, String text, String title, String author) {}
    public record Run(String text, boolean highlighted) {}
    public record Result(int passageCount, int coveredMinutes, List<String> missingTimes) {}

    private LiteratureGenerator() {}

    public static List<Passage> parse(Path source) throws IOException {
        List<Passage> passages = new ArrayList<>();
        List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (index == 0 && line.startsWith("\uFEFF")) line = line.substring(1);
            if (line.isBlank()) continue;
            // This dataset is literal pipe-delimited text, not RFC CSV: quotation marks
            // inside a field belong to the literature and must not be stripped.
            String[] fields = line.split("\\|", -1);
            if (fields.length != 6) throw invalid(index + 1, "expected exactly six pipe-separated fields");
            Matcher time = TIME.matcher(fields[0].strip());
            if (!time.matches()) throw invalid(index + 1, "time must be 24-hour HH:mm");
            int minute = Integer.parseInt(time.group(1)) * 60 + Integer.parseInt(time.group(2));
            Passage passage = new Passage(index + 1, minute, clean(fields[1]), clean(fields[2]),
                    clean(fields[3]), clean(fields[4]));
            if (passage.phrase().isEmpty() || passage.text().isEmpty()) {
                throw invalid(index + 1, "time phrase and full passage must not be empty");
            }
            if (runs(passage).stream().noneMatch(Run::highlighted)) {
                throw invalid(index + 1, "time phrase was not found in the full passage (ignoring case)");
            }
            passages.add(passage);
            // Field six is deliberately ignored, as requested.
        }
        if (passages.isEmpty()) throw new IOException("Literature file contains no passages: " + source);
        return List.copyOf(passages);
    }

    static String clean(String value) {
        String text = BREAK.matcher(value).replaceAll("\n");
        text = TIME_TAG.matcher(text).replaceAll("");
        return text.strip();
    }

    /** Highlight every literal occurrence, retaining the exact case and wording in the passage. */
    public static List<Run> runs(Passage passage) {
        Matcher match = Pattern.compile(Pattern.quote(passage.phrase()),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(passage.text());
        List<Run> runs = new ArrayList<>();
        int end = 0;
        while (match.find()) {
            if (match.start() > end) runs.add(new Run(passage.text().substring(end, match.start()), false));
            runs.add(new Run(passage.text().substring(match.start(), match.end()), true));
            end = match.end();
        }
        if (end < passage.text().length()) runs.add(new Run(passage.text().substring(end), false));
        return List.copyOf(runs);
    }

    static Map<Integer, List<Passage>> byMinute(List<Passage> passages) {
        Map<Integer, List<Passage>> minutes = new TreeMap<>();
        for (Passage passage : passages) {
            minutes.computeIfAbsent(passage.minute(), ignored -> new ArrayList<>()).add(passage);
        }
        return minutes;
    }

    public static Result generate(Path source, Path template, Path output, Path report) throws IOException {
        List<Passage> passages = parse(source);
        Map<Integer, List<Passage>> minutes = byMinute(passages);
        Document face = readXml(Files.readString(template, StandardCharsets.UTF_8));
        Element prototype = find(face.getDocumentElement(), "Group", "literature_entry_template");
        Element fallback = find(face.getDocumentElement(), "Group", "missing_passage_template");
        Node parent = prototype.getParentNode();
        if (fallback.getParentNode() != parent) throw new IOException("Literature templates must share a parent");

        Map<String, String> strings = new LinkedHashMap<>();
        Element hours = condition(face);
        parent.insertBefore(hours, prototype);
        for (int hour = 0; hour < 24; hour++) {
            Element hourCompare = branch(hours, "hour_" + hour, "[HOUR_0_23] == " + hour);
            Element minuteCondition = condition(face);
            hourCompare.appendChild(minuteCondition);
            for (int minute = 0; minute < 60; minute++) {
                List<Passage> choices = minutes.get(hour * 60 + minute);
                if (choices == null) continue;
                String minuteName = "hour_" + hour + "_minute_" + minute;
                Element minuteCompare = branch(minuteCondition, minuteName, "[MINUTE] == " + minute);
                if (choices.size() == 1) {
                    minuteCompare.appendChild(passageGroup(face, prototype, choices.get(0), strings));
                } else {
                    Element daily = condition(face);
                    minuteCompare.appendChild(daily);
                    for (int choice = 0; choice < choices.size(); choice++) {
                        Element selected = branch(daily, minuteName + "_choice_" + choice,
                                "[DAY_OF_YEAR] % " + choices.size() + " == " + choice);
                        selected.appendChild(passageGroup(face, prototype, choices.get(choice), strings));
                    }
                }
            }
            // Missing minutes use the real clock, never a quote about the wrong time.
            Element defaultBranch = face.createElement("Default");
            defaultBranch.appendChild(renamedClone(fallback, "missing_hour_" + hour));
            minuteCondition.appendChild(defaultBranch);
        }
        parent.removeChild(prototype);
        parent.removeChild(fallback);

        Path faceFile = output.resolve("raw/watchface.xml");
        Path stringsFile = output.resolve("values/literature.xml");
        Files.createDirectories(faceFile.getParent());
        Files.createDirectories(stringsFile.getParent());
        writeXml(face, faceFile);

        Document resources = readXml("<resources/>");
        for (Map.Entry<String, String> entry : strings.entrySet()) {
            Element string = resources.createElement("string");
            string.setAttribute("name", entry.getKey());
            string.setAttribute("formatted", "false");
            string.setTextContent(androidString(entry.getValue()));
            resources.getDocumentElement().appendChild(string);
        }
        writeXml(resources, stringsFile);

        List<String> missing = new ArrayList<>();
        for (int minute = 0; minute < 1440; minute++) {
            if (!minutes.containsKey(minute)) missing.add(String.format(Locale.ROOT, "%02d:%02d", minute / 60, minute % 60));
        }
        Result result = new Result(passages.size(), minutes.size(), List.copyOf(missing));
        Files.createDirectories(report.getParent());
        Files.writeString(report, "{\n  \"passages\": " + result.passageCount()
                + ",\n  \"coveredMinutes\": " + result.coveredMinutes()
                + ",\n  \"rotation\": \"DAY_OF_YEAR modulo passages for this minute\""
                + ",\n  \"missingTimes\": [" + (missing.isEmpty() ? "" : "\"" + String.join("\", \"", missing) + "\"")
                + "]\n}\n", StandardCharsets.UTF_8);
        return result;
    }

    private static Element passageGroup(Document face, Element prototype, Passage passage, Map<String, String> strings)
            throws IOException {
        String id = "lit_" + passage.line();
        Element group = renamedClone(prototype, id);
        Element body = find(group, "PartText", id + "_quote_body");
        Element text = (Element) body.getElementsByTagName("Text").item(0);
        Element normal = (Element) text.getElementsByTagName("Font").item(0).cloneNode(true);
        Element highlighted = (Element) text.getElementsByTagName("Font").item(1).cloneNode(true);
        List<Run> segments = runs(passage);
        int size = fitSize(segments, Integer.parseInt(normal.getAttribute("size")), 12,
                Integer.parseInt(body.getAttribute("width")), Integer.parseInt(body.getAttribute("height")),
                passage.line(), "passage");
        clear(text);
        int run = 0;
        for (Run segment : segments) {
            String resource = id + "_run_" + run++;
            strings.put(resource, segment.text());
            Element font = (Element) (segment.highlighted() ? highlighted : normal).cloneNode(true);
            font.setAttribute("size", Integer.toString(size));
            setResource(font, resource);
            text.appendChild(font);
        }
        setPartResource(group, id + "_book_title", id + "_title", passage.title(), passage.line(), strings);
        setPartResource(group, id + "_book_author", id + "_author", passage.author(), passage.line(), strings);
        return group;
    }

    private static void setPartResource(Element group, String name, String id, String value, int line, Map<String, String> strings)
            throws IOException {
        Element part = find(group, "PartText", name);
        Element font = (Element) part.getElementsByTagName("Font").item(0);
        int size = fitSize(List.of(new Run(value, false)), Integer.parseInt(font.getAttribute("size")),
                name.endsWith("book_title") ? 12 : 8,
                Integer.parseInt(part.getAttribute("width")), Integer.parseInt(part.getAttribute("height")),
                line, name);
        font.setAttribute("size", Integer.toString(size));
        setResource(font, id);
        strings.put(id, value);
    }

    /**
     * Native auto-size can clip mixed Font/Template runs on some runtimes. Pre-fit
     * using Unicode line breaking, bold spans, explicit line breaks, and spare room
     * for differences between the desktop and watch system fonts. Native auto-size
     * remains enabled as an additional adjustment for the actual device font.
     */
    static int fitSize(List<Run> runs, int maximum, int minimum, int width, int height, int line, String field)
            throws IOException {
        for (int size = maximum; size >= minimum; size--) {
            if (measuredHeight(runs, size, width * 0.94f) <= height - Math.min(6, height * 0.025f)) return size;
        }
        throw invalid(line, field + " cannot fit at font size " + minimum
                + "; shorten the entry or enlarge its template bounds");
    }

    static float measuredHeight(List<Run> runs, int size, float width) {
        String text = runs.stream().map(Run::text).reduce("", String::concat);
        if (text.isEmpty()) return 0;
        AttributedString styled = new AttributedString(text);
        int position = 0;
        for (Run run : runs) {
            if (run.text().isEmpty()) continue;
            styled.addAttribute(TextAttribute.FONT,
                    new Font(Font.SANS_SERIF, run.highlighted() ? Font.BOLD : Font.PLAIN, size),
                    position, position + run.text().length());
            position += run.text().length();
        }
        LineBreakMeasurer measurer = new LineBreakMeasurer(styled.getIterator(), FONT_CONTEXT);
        float height = 0;
        int paragraphStart = 0;
        while (paragraphStart <= text.length()) {
            int end = text.indexOf('\n', paragraphStart);
            if (end < 0) end = text.length();
            if (end == paragraphStart) {
                height += size * 1.28f;
            } else {
                measurer.setPosition(paragraphStart);
                while (measurer.getPosition() < end) {
                    var layout = measurer.nextLayout(width, end, false);
                    height += Math.max(size * 1.28f, layout.getAscent() + layout.getDescent() + layout.getLeading());
                }
            }
            if (end == text.length()) break;
            paragraphStart = end + 1;
        }
        return height;
    }

    private static void setResource(Element font, String resource) {
        clear(font);
        Document document = font.getOwnerDocument();
        Element template = document.createElement("Template");
        template.appendChild(document.createTextNode("%s"));
        Element parameter = document.createElement("Parameter");
        parameter.setAttribute("expression", resource);
        template.appendChild(parameter);
        font.appendChild(template);
    }

    private static Element condition(Document document) {
        Element condition = document.createElement("Condition");
        condition.appendChild(document.createElement("Expressions"));
        return condition;
    }

    private static Element branch(Element condition, String name, String expression) {
        Document document = condition.getOwnerDocument();
        Element value = document.createElement("Expression");
        value.setAttribute("name", name);
        value.appendChild(document.createCDATASection(expression));
        condition.getFirstChild().appendChild(value);
        Element compare = document.createElement("Compare");
        compare.setAttribute("expression", name);
        condition.appendChild(compare);
        return compare;
    }

    private static Element renamedClone(Element source, String prefix) {
        Element copy = (Element) source.cloneNode(true);
        rename(copy, prefix);
        copy.setAttribute("name", prefix);
        return copy;
    }

    private static void rename(Element element, String prefix) {
        if (element.hasAttribute("name")) element.setAttribute("name", prefix + "_" + element.getAttribute("name"));
        for (Node child = element.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child instanceof Element) rename((Element) child, prefix);
        }
    }

    static Element find(Element root, String tag, String name) throws IOException {
        if (root.getTagName().equals(tag) && root.getAttribute("name").equals(name)) return root;
        var nodes = root.getElementsByTagName(tag);
        for (int i = 0; i < nodes.getLength(); i++) {
            Element element = (Element) nodes.item(i);
            if (element.getAttribute("name").equals(name)) return element;
        }
        throw new IOException("Missing template element: " + tag + " name=" + name);
    }

    private static void clear(Element element) {
        while (element.hasChildNodes()) element.removeChild(element.getFirstChild());
    }

    static String androidString(String text) {
        // Quoted Android strings preserve leading/trailing whitespace between inline fonts.
        // Resource text is passed through %s, so literal %, quotes, @, and ? remain data.
        return "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"").replace("'", "\\'")
                .replace("\n", "\\n").replace("\t", "\\t") + "\"";
    }

    static Document readXml(String xml) throws IOException {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
        } catch (Exception exception) {
            throw new IOException("Could not read watch face XML", exception);
        }
    }

    private static void writeXml(Document document, Path file) throws IOException {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
            // No added indentation between inline Font/Template runs: whitespace is content.
            transformer.setOutputProperty(OutputKeys.INDENT, "no");
            transformer.transform(new DOMSource(document), new StreamResult(file.toFile()));
        } catch (Exception exception) {
            throw new IOException("Could not write generated watch face XML", exception);
        }
    }

    private static IOException invalid(int line, String message) {
        return new IOException("Literature line " + line + ": " + message);
    }
}
