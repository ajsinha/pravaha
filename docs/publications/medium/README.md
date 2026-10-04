# The Medium post

Copyright © 2026 Ashutosh Sinha \<ajsinha@gmail.com\>. All rights reserved.
**Proprietary and confidential** — see [`../../../LICENSE`](../../../LICENSE).

[`pravaha-medium-post.md`](pravaha-medium-post.md) is a long-form article about Pravaha 2.0's
design: the maintained answer, weighted rows, one-cut checkpoints, lanes, the exact seam, blue/green at
a position, the equality index, identity, the connectors, operating it; then what 1.0 added and 2.0
keeps (queries on queries, alerts, the governed catalogue, Power BI and psql over the PostgreSQL
gateway, one `/opt/pravaha` in and out of Docker, standalone SDKs, the assistant, how it is tested,
what 2.x promises); then 2.0 itself: Java 25 (relaxed to Java 21 or later in 2.3.0), and the adversarial QA round run against the release
(its method and numbers, what held, two worked examples — the all-NULL `SUM` and the PostgreSQL
gateway's pre-authentication allocation — and the three waves of fixes); and three case studies
worked end to end. Its twenty diagrams are in [`images/`](images/). Each one is a hand-written SVG, which is the source, with a PNG exported from it
at 2x, which is what gets published on Medium.

[`pravaha-medium-post.html`](pravaha-medium-post.html) is the same post as one self-contained page in
the console's crimson identity, every diagram inlined (as its SVG), to send, attach or open offline. It
is generated, never edited:

```bash
console/.venv/bin/python tools/medium-page/render.py     # any Python with the markdown package
```

Every number, name and code sample in the post comes from this repository: the root README,
`docs/**/*.md` (the ADRs in `docs/design/adr/`, `docs/project/RELEASE_NOTES.md` and
`docs/guides/CLI.md` among them), `sdk/python/README.md` and the case studies' READMEs. If one of those changes, check the post against it before you publish.

## Publishing on Medium

Medium does not import markdown. Its *Import a story* feature takes a URL to a published web page,
so a `.md` file cannot be imported directly. You have two options:

1. **Paste it in section by section.** Open the rendered markdown (on GitHub, or in any markdown
   preview), copy one section, and paste it into Medium's editor, which keeps headings, bold,
   italics, links and block quotes. Code blocks usually arrive as plain paragraphs. Select each one
   and turn it into a code block (type three backticks on an empty line, or press Ctrl/Cmd+Alt+6).
2. **Use a markdown-to-Medium tool.** Converters exist that turn markdown into a Medium draft or a
   gist-backed page Medium can import. Whichever you use, check the draft afterwards: code blocks and
   tables are where conversions usually go wrong.

**Images.** Medium needs the images uploaded. It does not follow the relative `images/NN-name.png`
paths in the markdown. At each image reference, upload the matching **PNG** from `images/` (not the
SVG, which Medium does not accept). Paste the reference's alt text into the image's *alt text*
field, and use the italic line under the reference as the caption.

**Tables.** Medium has no tables. The post has one, the three states in the exact-seam section.
Either paste it as a short list, or leave it out: diagram 06 shows the same three states.

**Title and subtitle.** The `#` heading is the title. The `###` line under it is the subtitle, which
goes in Medium's subtitle field (the second line of a new story, styled as a kicker).

## Re-exporting the images

The SVGs use the console's palette and fonts (`console/web/templates/base.html`): crimson
`#A51C30` with `#8A1626` and `#C4384B`, the `#F7F5F2` canvas, `#1A1A1A` ink, `#293352` for a −1, and
the Source family (Source Sans 3, Source Serif 4 for titles, Source Code Pro). Machines without those
fonts fall back to Inter or another sans, and a serif such as Charis SIL or Georgia. The canvas is
800 px wide, so a 2x export is 1600 px.

Export with whichever of these tools you have, in this order of preference. Run from
`docs/publications/medium/images`:

```bash
# rsvg-convert (librsvg)
for f in *.svg; do rsvg-convert -z 2 -b '#F7F5F2' "$f" -o "${f%.svg}.png"; done

# Inkscape 1.x (what the committed PNGs were made with)
for f in *.svg; do
  inkscape "$f" --export-type=png --export-dpi=192 --export-background-opacity=1 \
    --export-filename="${f%.svg}.png"
done

# cairosvg, in a throwaway virtual environment that is not committed
uv venv /tmp/svgexport && uv pip install --python /tmp/svgexport cairosvg
for f in *.svg; do /tmp/svgexport/bin/cairosvg "$f" -s 2 -o "${f%.svg}.png"; done
```

After exporting, open every PNG. Look for text that overflows a box, labels that overlap, and
arrows that stop short of their target or run past it. Different renderers substitute fonts
differently, and a wider fallback font is usually what makes a label overflow.
