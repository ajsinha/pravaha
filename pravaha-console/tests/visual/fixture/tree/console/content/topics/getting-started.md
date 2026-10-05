---
# Copyright (c) 2026 Ashutosh Sinha <ajsinha@gmail.com>. All rights reserved. A fixed fixture (ABOUTBASE-1):
# the real topic is console/content/topics/getting-started.md, which every other test reads. The two
# sections the start and overview screens' help cards open keep their anchors and first paragraphs.
title: Getting started
slug: getting-started
category: start
order: 10
icon: signpost-2
summary: "What Pravaha is and the three nouns it is built from; your first maintained view end to end, with the output of every step; and the console, screen by screen — the bar, the menus, the themes."
badge: START
audience: Everyone
keywords: [overview, introduction, getting started]
guide: quickstart
related: [clients, streams, views-and-keys]
---

This page has three parts: [the first part](#the-first-part), [the second part](#the-second-part)
and [the third part](#the-third-part). It is a fixed text for the visual tests, in the shape a topic
page has: a lead paragraph, sections, a table, code and a list.

## The first part {#the-first-part}

A topic states its subject in a sentence with **bold** where the key word is, and then explains it
in a paragraph long enough to wrap several times at the wide viewport and many more at the narrow
one. The words here are chosen once and do not change when the documentation does.

A second paragraph, with `inline code` and an *emphasised* phrase, because topics carry both.

## The second part {#the-second-part}

Most topics hold a comparison as a table:

| You would otherwise build | Here |
|---|---|
| A process that computes | A registered query |
| A store that keeps the result | The query's view |
| Code that keeps the two consistent | Nothing: one component |

## The third part {#the-third-part}

```sql
CREATE CONTINUOUS QUERY big_txn AS
SELECT user_id, SUM(amount) AS total FROM txn GROUP BY user_id
```

- A first item in a list.
- A second item, long enough to wrap onto a second line at the narrow viewport so the hanging indent
  is photographed.
- A third item.

## Your first maintained view {#your-first-maintained-view}

This part takes you from an empty directory to a view you can watch changing, and shows the exact
command and what comes back at every step. It uses one machine, one CSV file that you append to by
hand, and the three ways of reading an answer — the `pravaha` CLI, `psql`, and the Python SDK.

## The console, screen by screen {#the-console-screen-by-screen}

The console is the browser product for a running engine: where an analyst writes, checks and
registers continuous SQL, where a developer finds a view and copies the code that reads it, where an
operator answers *"is everything healthy, and if not, where?"*, and where anyone watches a view
change as the engine commits. This part walks every screen.

## Where next {#where-next}

The related topics below the page, and the contents on its right, are the page's own.
