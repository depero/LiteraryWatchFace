# Literary Watch Face

The awake face shows a passage matching the watch's local hour and minute. Its time phrase is highlighted in a separate color, battery percentage and date appear above, and the book title and author appear below. Ambient mode keeps the compact white digital clock and battery percentage on black.

This is a **Watch Face Format (WFF) version 3** resource-only app. Gradle parses the literature file on your computer and generates XML and strings; Wear OS selects and renders them without an app timer or runtime CSV parser. Version 3 provides automatic text sizing. The existing minimum Android API 36 is unchanged and supports this format.

## Main files

| Edit this | To change |
| --- | --- |
| `literature/litclock_annotated.csv` | Passages, time phrases, book titles, authors, and time mappings |
| `watchface/src/main/watchface-template.xml` | Layout, fonts, colors, backgrounds, date, attribution, fallback, and ambient appearance |
| `watchface/src/main/res/values/strings.xml` | Displayed face name, editor labels, and missing-passage message |
| `watchface/src/main/res/drawable/` | Images and the static picker preview |
| `watchface/src/main/res/font/` | Optional custom fonts; create the folder when needed |
| `watchface/src/main/res/xml/watch_face_info.xml` | Face metadata and picker-preview reference |
| `watchface/build.gradle.kts` | Input filename, generator wiring, app ID, SDK requirements, and version |
| `buildSrc/src/main/java/literary/LiteratureGenerator.java` | Parsing, highlighting, daily rotation, missing-minute handling, and resource escaping |

**Edit the template and source data, not the generated watchface.xml.** Each Android build runs `generateLiteratureResources` automatically when its inputs change. Generated files are under `watchface/build/generated/literature/res/` and are intentionally ignored by Git. Build logic stays in buildSrc and is not packaged into the watch APK.

## Literature file format and selection

Each nonblank UTF-8 line has exactly six literal pipe-separated fields, with no header:

```text
HH:mm|time phrase|full passage|book title|author|sfw/nsfw
```

Quotation marks belong to the passage; this is not a quoted RFC CSV dialect. A literal `|` inside a field is unsupported. Times must be zero-padded 24-hour values from `00:00` to `23:59`. The sixth field is ignored as requested, so all supplied content is eligible.

The full passage is retained. Time-phrase matching ignores case, preserves the actual wording/case in the passage, and highlights every matching occurrence. XML-sensitive characters, apostrophes, percent signs, quotation marks, and spaces at inline color boundaries are escaped for Android resources. Source `<br>`, `<br/>`, and `<br />` tags become line breaks; the file's stray `<time/>` / `</time>` markers are removed.

For minutes with multiple entries, the selected row is `DAY_OF_YEAR % number_of_entries`, in original file order. Selection is stable throughout that minute and rotates on different dates. It follows the watch's local date and time, independently of the 12/24-hour notation used by the fallback clock.

The supplied file currently has **3,626 passages covering 1,432 minutes**. These eight times have no entries:

`06:07`, `06:18`, `08:21`, `10:28`, `11:46`, `12:31`, `13:36`, `18:44`.

At those times, the central area shows “No passage for this minute.” and the actual digital time. It does not substitute a quote describing a different time or attribute the fallback to a book. Add rows for those times to replace the fallback. The build writes coverage details to `watchface/build/reports/literature/generation.json`.

Invalid rows fail the build with a source line number instead of silently losing passages. After editing data, rebuild and reinstall the APK. To rename the input file, update `literatureFile` in the module's build.gradle.kts.

## Layout and text size

The template uses a 450 by 450 logical canvas, scaled by Wear OS. `(0, 0)` is the upper-left; `(225, 225)` is the center. Group coordinates are relative to their parents. Increasing `x` moves right and increasing `y` moves down. A group's bounds do not automatically resize its children or fonts.

| Element in the template | Position and bounds | Styling |
| --- | --- | --- |
| `battery` | x=165, y=20, 120 by 24 | Live percentage, size 16; also visible in ambient mode |
| `date` | x=105, y=46, 240 by 30 | Original date formatting, size 20 |
| `quote_body` | x=50, y=92, 350 by 266 | Full passage; size up to 32, pre-fitted during generation with native auto-size as backup |
| `attribution` | x=85, y=364, 280 by 52 | Parent for title and author |
| `book_title` | x=0, y=0 within attribution, 280 by 32 | Up to size 14, up to two lines, pre-fitted with automatic sizing |
| `book_author` | x=20, y=36 within attribution, 240 by 16 | Up to size 11, pre-fitted on one separate line |
| `ambient_time` | x=50, y=170, 350 by 110 | Thin white time, size 96 |

`literature_entry_template` is cloned once per passage. Keep its `quote_body`, `book_title`, and `book_author` names: the generator uses them to locate the fields. The two Font elements in quote_body are prototypes for ordinary and highlighted text. Keep them in that order and use the same starting size/family so inline segments scale together.

The generator measures Unicode wrapping, explicit line breaks, and bold highlight runs, then chooses a font size that fits with spare room for device font differences. The passage also uses `isAutoSize="TRUE"`, `maxLines="32"`, and `ellipsis="FALSE"` as a device adjustment, without deliberately truncating the quote. Some native runtimes can clip mixed-style text when relying only on auto-size, which is why the build pre-fits each entry. The body has a 12-unit minimum; an entry that cannot fit at that size fails generation with its line number. Titles can wrap to two lines; authors occupy a separate smaller line. Test long additions or custom fonts on your watch.

The date's coordinates, font, and `%s, %s %s` template remain as before. Its month/day names follow the watch locale, while its ordering and punctuation come from the template. The footer visibility option retains its internal ID `show_caption` so existing saved settings still work; its displayed label is now **Show title and author**. The theme and Show date options remain available in the watch face editor.

## Colors and fonts

Each theme's ColorOption now contains five `#AARRGGBB` colors:

| Index | Role |
| --- | --- |
| 0 | Awake background |
| 1 | Normal passage text |
| 2 | Date |
| 3 | Book title, author, and battery percentage |
| 4 | Highlighted time phrase |

Change the fifth color to change the highlight without changing the date. The Ink and Forest highlights use gold and green; the Monochrome theme uses a blue time highlight so it still differs from white body text. `FF` is opaque; `#FF000000` is black and `#FFFFFFFF` is white.

Add a theme by duplicating a ColorOption with a unique ID and five colors, adding its displayName string, and optionally changing the ColorConfiguration defaultValue. Saved editor choices can override new defaults.

For a custom font, add a licensed file such as `res/font/book_serif.ttf` and use `family="book_serif"`. Bare resource names are required in WFF. Apply it to both quote Font prototypes, and to the title/author if desired. Font `size`, `weight`, `slant`, and `color` control appearance; the highlighted prototype is bold by default. See [Font](https://developer.android.com/reference/wear-os/wff/group/part/text/font) and [Text](https://developer.android.com/reference/wear-os/wff/group/part/text/text).

## Backgrounds, shapes, and extra information

The background group appears before all text and disappears in ambient mode, exposing the black Scene. To add an image, put `background.png` in `watchface/src/main/res/drawable/` and add this inside the background group after its PartDraw:

```xml
<PartImage name="background_image" x="0" y="0" width="450" height="450">
    <Image resource="background" />
</PartImage>
```

Use the bare filename without extension, not `@drawable/background`. Later layers appear over earlier ones. Prepare an appropriately sized square image rather than a full-resolution camera photo.

For a divider between the passage and footer, add this inside supporting_content:

```xml
<PartDraw name="divider" x="175" y="360" width="100" height="2">
    <Rectangle x="0" y="0" width="100" height="2">
        <Fill color="[CONFIGURATION.theme.2]" />
    </Rectangle>
</PartDraw>
```

Other text can use Template/Parameter with live [data sources](https://developer.android.com/reference/wear-os/wff/common/attributes/source-type), such as BATTERY_PERCENT. Place it without overlapping the date, passage, or attribution. For user-selected app data, add a ComplicationSlot with appropriate bounds and supported rendering types; see [complications](https://developer.android.com/training/wearables/wff/complications).

## Ambient mode and fallback time

The literature_active group has an ambient alpha Variant of 0, hiding both the passage and attribution while idle. The supporting_content group hides the date. ambient_time has the opposite alpha pattern and displays the compact white clock. The battery percentage is outside these groups, so it remains visible while idle and when Show date is disabled. It reads the live BATTERY_PERCENT data source and uses palette color 3. To display literature in ambient mode too, change these groups' visibility and styling deliberately and verify the idle appearance on your device.

The ambient and missing-passage clocks use `format="hh:mm" hourFormat="SYNC_TO_DEVICE"`. For fixed 24-hour notation, use `hourFormat="24"`; for 12-hour notation use `"12"`. `format="h:mm"` removes a leading hour zero. These numeric format changes do not affect literature selection. Use seconds only for an awake fallback if desired, allowing extra width or reducing its font size. See [TimeText](https://developer.android.com/reference/wear-os/wff/clock/time-text).

## Build and verify

In Android Studio, sync, select a compatible Wear OS emulator/watch, and run the watch face configuration. Alternatively, in PowerShell at the project root:

```powershell
.\gradlew.bat :watchface:assembleDebug :watchface:lintDebug :watchface:assembleRelease
.\gradlew.bat -p buildSrc test
```

The tests cover record validation, exact-case highlighting, repeated phrases, Android escaping, conservative text fitting, generated references, midnight and day rotation, all 1,440 minutes on representative dates, and retention of every supplied passage. A malformed or missing input file stops generation.

Use Android Studio's bundled JDK as JAVA_HOME if your terminal has no Java configured. The debug APK is `watchface/build/outputs/apk/debug/watchface-debug.apk`. The release APK is unsigned until release signing is configured.

APK compilation is not a WFF schema check. Use Android Studio's WFF inspections or Google's [WFF validator](https://github.com/google/watchface/tree/main/third_party/wff) against the **generated** XML:

```powershell
java -jar C:\path\to\wff-validator.jar 3 .\watchface\build\generated\literature\res\raw\watchface.xml
```

Check long passages, all three themes, the visibility toggles, missing minutes, and active/ambient transitions on your device. Logcat's runtime logs help diagnose missing fonts/resources. For publishing, also run Google's memory-footprint checks; see [WFF debugging](https://developer.android.com/training/wearables/wff/debug) and [setup](https://developer.android.com/training/wearables/wff/setup).

`res/drawable/preview.png` remains the original static picker image, independent of the live layout. Replace it with a representative screenshot once the design is settled.

## Project settings

The template retains minSdk 36, compileSdk 37, and targetSdk 37. WFF v3 requires at least API 35, so the existing API 36 minimum remains sufficient. Check [WFF versions](https://developer.android.com/training/wearables/wff) before changing device support or copying newer XML attributes.

The displayed name is watch_face_name in strings.xml; applicationId in the module Gradle file is the installation identity. Change the example applicationId before publishing and keep it stable for subsequent updates. Increment versionCode for published updates. The manifest's hasCode=false is intentional.

raw/keep.xml preserves WFF resources looked up by bare name during release shrinking. Do not remove generated strings because ordinary Android code does not reference them. If a source edit appears ineffective, rebuild/reinstall, verify active versus ambient mode, and check saved editor settings.

