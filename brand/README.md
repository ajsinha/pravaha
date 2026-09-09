# Pravaha brand assets

Copyright 2026 Ashutosh Sinha <ajsinha@gmail.com>. Released with the project under
Apache License 2.0.

## The name

**Pravaha** (प्रवाह) is Sanskrit for *continuous, uninterrupted flow*. Pronounced
*pruh-VAA-huh*. Always capitalised as "Pravaha", never "PRAVAHA" in prose and never
"pravaha" at the start of a sentence.

## Slogan

> ### Ask once. Answer always.

It says what a continuous query is, in four words, to someone who has never heard the
term: you register the question once and the answer stays current forever. It also
quietly separates Pravaha from a database, where you ask every time you want to know.

**Supporting line**, where a sentence of explanation is warranted:

> Continuous SQL where your data already lives.

**Usage.** The slogan is a full sentence pair -- keep both full stops. Do not translate it,
do not extend it, and do not use it as a heading in running prose. It belongs on the logo
lockup, the landing page, the README, and the console's sign-in screen. Nowhere else.

## Mark

Three streamlines, in phase, running past the edges of the frame. The overrun is the
whole idea: a continuous query has no last row, so the mark must not imply one. The two
outer lines sit at 55% opacity to give depth without adding a fourth element.

| File | Use |
|---|---|
| `mark.svg` | Primary icon, gradient. Anything 32px and larger. |
| `mark-mono.svg` | Single colour, inherits `currentColor`. Themed UI, print, embroidery. |
| `favicon.svg` | Tab icon. **Two** lines and a heavier stroke -- at 16px the three-line mark closes into a solid block. |
| `logo.svg` | Horizontal lockup: mark, wordmark, slogan. |

**Clear space:** at least the height of one streamline gap (7 units at the 64-unit
viewBox) on every side.

**Minimum sizes:** `mark.svg` at 32px, `favicon.svg` at 16px, `logo.svg` at 160px wide.

**Do not:** recolour the gradient, add a fourth line, terminate the lines inside the
frame, rotate the mark, place it on a busy photograph, or apply a drop shadow.

## Colour

The palette is drawn from verdigris -- oxidised copper, the colour of water on metal.

| Token | Light | Dark | Use |
|---|---|---|---|
| `accent` | `#0E7C7B` | `#3FB3AB` | Primary. The middle streamline. |
| `accent-deep` | `#0A5C5B` | `#6FD0C8` | Gradient start, links, pressed states. |
| `accent-wash` | `#DCEBEA` | `#12302F` | Selected rows, hover surfaces. |
| `ink` | `#0F1A1C` | `#E4ECEC` | Body text. A near-black biased toward the accent, never pure `#000`. |
| `ground` | `#EEF1F2` | `#0C1416` | Page background. |
| `alert` | `#A8402A` | `#E08466` | **Semantic only** -- errors and critical state. Never decorative. |

Status colour (`ok`, `warn`, `critical`, `degraded`) is a separate ramp and never reuses
the accent: a running query and a branded button must not share a hue (design section 23.4).

## Typography

| Role | Face | Fallback |
|---|---|---|
| Display, headings | IBM Plex Sans Condensed | `system-ui, sans-serif` |
| Body, UI | IBM Plex Sans | `system-ui, -apple-system, sans-serif` |
| Code, data, metrics | IBM Plex Mono | `ui-monospace, SFMono-Regular, Menlo, monospace` |
| प्रवाह | IBM Plex Sans Devanagari | `Noto Sans Devanagari, sans-serif` |

The Devanagari cut is why this superfamily and not another: the name renders correctly in
its own script, in a face that matches the Latin.

Use `tabular-nums` everywhere digits align -- every metric, every table column, every
latency figure.
