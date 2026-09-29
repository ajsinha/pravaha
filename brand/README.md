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
lockup, the landing page, the README, and the console's sign-in screen -- and, because the console
follows MAYA's design language (below), where MAYA carries its own creed: the third line of the
brand block in the console's top bar, under the name and the supporting line, and the start of the
closing line of every page. Nowhere else.

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

## The console follows MAYA

Chosen by the owner on 2026-09-28: the console is drawn in the design language of **MAYA**, the
sibling product -- MAYA's tokens, its four themes, its fixed top bar with mega-menu panels, its
banners, flashes and footer. `console/web/static/css/tokens.css` and `theme.css` are MAYA's files
with the names changed (`--maya-*` → `--pv-*`); where a value departs from MAYA's, the file says
why beside it.

## Colour

The palette is crimson, MAYA's Harvard crimson, in four themes: **Crimson** (the light theme),
**Dark**, **Blue** (the SAJHA server's #0079C1 / #003168) and **Green** (Rolex #006039 on cream).
The console's `console/web/static/css/tokens.css` is the only place a colour is defined.

| Token | Crimson | Dark | Use |
|---|---|---|---|
| `pv-crimson` (accent) | `#A51C30` | `#E47F92` | Primary. The middle streamline. Links, the primary action. |
| `pv-crimson-strong` | `#8A1626` | `#EA8FA0` | Hover and pressed states. |
| `pv-crimson-deep` | `#6E1120` | `#E07A8E` | Headings' first stop, rules. |
| `pv-crimson-tint` | `#FBEEF0` | `#2A1A1E` | Hovered rows and menu items. |
| `pv-ink` | `#1A1A1A` | `#ECECEF` | Body text, never pure `#000`. |
| `pv-canvas` (ground) | `#F7F5F2` | `#151517` | Page background. |
| `pv-nav-from` · `via` · `to` | `#5C0E1B` · `#A51C30` · `#293352` | `#2E0810` · `#6E1120` · `#1B2138` | The bar's gradient, white on every stop. |
| `pv-bad` (alert) | `#CC2200` | `#FF8B63` | **Semantic only** -- errors and critical state. Never decorative. |
| `pv-retract` | `#293352` | `#A9B6D6` | A weight of −1. A retraction is not an error, so it never wears the alert. |

Status colour (`ok`, `warn`, `bad`) is a separate ramp and never decorates: a running query and
a branded button must not be told apart by hue alone (design section 23.4). That is the one place
the console keeps a value MAYA does not have: MAYA's `bad` is its own crimson-strong.

## Typography

The console uses MAYA's type: the system face for everything, a monospace for code and data.

| Role | Face | Fallback |
|---|---|---|
| Display, headings, body, UI | `system-ui` (console); Source Serif 4 / Source Sans 3 (deck) | `-apple-system, "Segoe UI", Roboto, sans-serif` |
| Code, data, metrics | `ui-monospace` (console); Source Code Pro (deck) | `SFMono-Regular, Menlo, Consolas, monospace` |
| प्रवाह | Noto Sans Devanagari | `sans-serif` |

Use `tabular-nums` everywhere digits align -- every metric, every table column, every
latency figure.
