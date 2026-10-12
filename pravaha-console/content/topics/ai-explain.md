---
title: Explain, why it changed, and Ask about this page
slug: ai-explain
category: ai
order: 40
icon: lightbulb
summary: "Explain in plain words: a query from the engine's own plan, a refusal and what to change, why a view changed since you last asked; a narrative of a batch of live changes; and Ask about this page, answered from the page's own help."
audience: Everyone
keywords: [explain, "Explain in plain words", light bulb, refusal, why, "Why did this change?", change, narrative, "Summarise changes", live, "Ask about this page", page, about, PRV-1088, PRV-4023, PRV-7003]
guide: "ai#7-explain"
related: [ai-features, reading-a-plan, sql-refusals, subscriptions]
---

## Explain in plain words

The light bulb (**Explain in plain words**) asks the engine to explain, with what it alone knows:

- **On a query's page** — the registered query. The engine plans it as you (you must be able to read
  everything it names) and the model is given the SQL **and the engine's physical plan**, so the
  explanation is grounded in what will run. The plan comes back with it.
- **Beside a refusal** — on the Workbench and on a query's page, next to a `PRV` code. When there is
  SQL, the engine checks it first and the model is given its verdict too; the answer says whether the
  statement is valid now, and which codes the engine raised.

## Why did this change?

On a view's page, **Why did this change?** explains the view's recent committed changes, narrowed for
you — your row filter and masks — as counts and up to twenty keys each way. Pravaha keeps a view's recent
changes in memory **from the first time anyone asks about it**: the first answer says it has just started
keeping them, and the next one, after commits, says what moved. With nothing committed since, no model is
asked. A view whose reads a security policy filters inside its plan is not explained (`PRV-7003`).

## Summarise changes

On a view's **Live** page, **Summarise changes** sends the batch of changes the page has received
(`+1` and `-1`, as the view's columns) and shows two or three sentences about them. Under `labels-only`
the model is told counts and keys only. Following the view never stops for it: a summary that is refused
says so once, and the rows keep coming.

## Ask about this page

The **About this page** panel at the foot of every screen has a box: **Ask about this page**. The console
sends the screen's key, the panel's own text and up to four of its help topics, and the answer comes from
that text alone — "The page does not say." when it does not. No data of any kind is read.

## What is never sent

A value of yours, under `labels-only`. A row the policy would not show you, under either mode. A key, ever:
the console asks the engine, which holds them. See [what a model is sent](/help/topics/ai-features#who-may).
