# Literature data

The source file is `litclock_annotated.csv`, encoded as UTF-8. Each nonblank line is:

```text
HH:mm|time phrase|full literature text|title|author|sfw/nsfw
```

There is no header or quoted-CSV processing. Quotation marks are literal content;
`|` separates fields. Times must use zero-padded 24-hour notation. The final field
is currently ignored, so no content filtering is applied.

Every Android build automatically runs `generateLiteratureResources`. It converts
the source into WFF Conditions and Android strings, with the date above, passage
in the center, colored time phrase, and book title/author below. Literal phrase
matching ignores case while preserving the passage's original spelling. All
matching occurrences are highlighted. HTML line breaks become real line breaks,
and the source's time marker tags are removed.

Multiple passages for the same minute rotate using the watch's local DAY_OF_YEAR
modulo the number of passages, in file order. Minutes with no entry show an
explicit missing-passage message and the actual clock time, with no attribution.
The original file has 3,626 passages covering 1,432 minutes.

Edit this file and rebuild/reinstall to update the watch. Generated files under
`watchface/build/generated/literature/res/` are build outputs; do not edit them.
Coverage is reported in `watchface/build/reports/literature/generation.json`.
Malformed rows fail with their line number. Keep time phrases present in the full
passage after markup cleanup, and visually verify unusually long additions.

The layout source is `watchface/src/main/watchface-template.xml`. The generator is
in `buildSrc/src/main/java/literary/LiteratureGenerator.java`. See the project-root
README for layout, font, color, build, testing, and device-validation instructions.
