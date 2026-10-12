# Pravaha brand assets

Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved.
**Proprietary and confidential** -- the Pravaha name, flow mark and slogan are not licensed
for third-party use. See `LICENSE`.

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
lockup, the landing page, the README, and the console's sign-in screen -- and, in the console, the
third line of the brand block in the top bar, under the name and the supporting line, and the
tagline line of the footer on every page. Nowhere else.

## Mark

Three streamlines, in phase, running past the edges of the frame. The overrun is the
whole idea: a continuous query has no last row, so the mark must not imply one. The two
outer lines sit at 55% opacity to give depth without adding a fourth element.

| File | Use |
|---|---|
| `mark.svg` | Primary icon, gradient. Anything 32px and larger. |
| `mark-mono.svg` | Single colour, inherits `currentColor`. Themed UI, print, embroidery. |
| `favicon.svg` | Tab icon: the logo itself -- the three streamlines in white on a crimson tile, drawn a little heavier and inset so they stay apart at 16px. The console serves it, with PNG, `favicon.ico`, Apple touch and manifest sizes rendered from it (`pravaha-console/web/static/img/`). |
| `logo.svg` | Horizontal lockup: mark, wordmark, slogan. |

**Clear space:** at least the height of one streamline gap (7 units at the 64-unit
viewBox) on every side.

**Minimum sizes:** `mark.svg` at 32px, `favicon.svg` at 16px, `logo.svg` at 160px wide.

**Do not:** recolour the gradient, add a fourth line, terminate the lines inside the
frame, rotate the mark, place it on a busy photograph, or apply a drop shadow.

## The console follows drishti

Chosen by the owner on 2026-10-12 (it followed MAYA from 2026-09-28): the console is drawn in the
design language of **drishti**, the sibling product -- drishti's seven themes under drishti's names
and ids, its token values exactly, its gradients on the chrome, its top bar, mega-menu panels, pill
buttons, panels, chips, footer and landing page. Pravaha keeps its own mark, name, creed and menus,
and **crimson remains Pravaha's brand**: the logo, the favicon and the deck are crimson, and the
console opens in the **Crimson** theme unless a person chooses another.

## Colour

The brand colour is Harvard crimson, `#A51C30`, with indigo `#293352` as its second colour. In the
console it is one of seven themes -- **Terminal** (`terminal`, navy and amber), **Parchment** (`light`), **Blue** (`blue`), **Wall Street** (`wallstreet`, Bloomberg black and amber), **Green** (`green`), **Crimson** (`crimson`, Pravaha's brand and the default) and **Crimson dark** (`crimson-dark`) -- and
`pravaha-console/web/static/css/tokens.css` is the only place a colour is defined.

| Token | Crimson | Crimson dark | Use |
|---|---|---|---|
| `d-accent` (`pv-crimson`) | `#a51c30` | `#de6b81` | Primary. The middle streamline. The primary action, the current place. |
| `d-accent-strong` | `#8a1626` | `#e07a8e` | Hover and pressed states; headings. |
| `d-link` | `#293352` | `#a9b6d6` | Links. |
| `d-ink` | `#1a1a1a` | `#ececef` | Body text, never pure `#000`. |
| `d-bg` (ground) | `#f7f5f2` | `#151517` | Page background. |
| `d-nav-from` · `via` · `to` | `#5c0e1b` · `#a51c30` · `#293352` | `#2e0810` · `#6e1120` · `#1b2138` | The bar's gradient, white on every stop. |
| `d-bad` | `#8a1626` | `#f08a9c` | Errors and critical state, always with a glyph and a word. |
| `pv-retract` | `#293352` | `#a9b6d6` | A weight of −1. A retraction is not an error, so it never wears the alert. |

Status colour (`ok`, `warn`, `bad`) never decorates and never stands alone: every status is a glyph
and a word as well (design section 23.4). In drishti's Crimson `bad` is the accent's own deeper step;
the console took drishti's values as they are, so a status is told from the brand by its glyph and
its word, not by hue.

## Typography

The console uses drishti's type: the system face for everything, a monospace for code and data,
with the wordmark set in Cinzel and the tagline and creed in Playfair Display (both SIL OFL, vendored).

| Role | Face | Fallback |
|---|---|---|
| Display, headings, body, UI | `system-ui` (console); Source Serif 4 / Source Sans 3 (deck) | `-apple-system, "Segoe UI", Roboto, sans-serif` |
| Code, data, metrics | `ui-monospace` (console); Source Code Pro (deck) | `SFMono-Regular, Menlo, Consolas, monospace` |
| प्रवाह | Noto Sans Devanagari | `sans-serif` |

Use `tabular-nums` everywhere digits align -- every metric, every table column, every
latency figure.
