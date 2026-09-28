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

The palette is crimson, the console's (`console/web/templates/base.html`), chosen by the owner on
2026-09-28 so the product, the deck and every diagram carry one colour. It replaced an earlier
verdigris palette that only the mark used.

| Token | Light | Dark | Use |
|---|---|---|---|
| `flow` (accent) | `#A51C30` | `#E47F92` | Primary. The middle streamline. |
| `flow-d` (accent-deep) | `#8A1626` | `#DE6B81` | Gradient start, links, pressed states. |
| `flow-l` (accent-light) | `#C4384B` | `#EC9EAC` | The light streamline, highlights. |
| `ink` | `#1A1A1A` | `#ECECEF` | Body text, never pure `#000`. |
| `canvas` (ground) | `#F7F5F2` | `#151517` | Page background. |
| `stripe` (wash) | `#F1EEE9` | `#232329` | Selected rows, hover surfaces. |
| `bad` (alert) | `#CC2200` | as the console | **Semantic only** -- errors and critical state. Never decorative. |
| `retract` | `#293352` | as the console | A weight of −1. A retraction is not an error, so it never wears the alert. |

Status colour (`ok`, `warn`, `bad`) is a separate ramp and never decorates: a running query and
a branded button must not be told apart by hue alone (design section 23.4).

## Typography

| Role | Face | Fallback |
|---|---|---|
| Display, headings | Source Serif 4 (console), Source Sans 3 (deck) | `Georgia, serif` / `system-ui, sans-serif` |
| Body, UI | Source Sans 3 | `-apple-system, "Segoe UI", Roboto, sans-serif` |
| Code, data, metrics | Source Code Pro | `Consolas, "SF Mono", Menlo, monospace` |
| प्रवाह | Noto Sans Devanagari | `sans-serif` |

Use `tabular-nums` everywhere digits align -- every metric, every table column, every
latency figure.
