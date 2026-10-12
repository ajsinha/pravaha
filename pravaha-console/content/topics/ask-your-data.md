---
title: Ask your data
slug: ask-your-data
category: ai
order: 20
icon: chat-square-dots
summary: "A question in plain English becomes one snapshot SELECT over a view you may read, run as you: the SQL the model chose, the rows, a chart drawn from the result's shape, and follow-up questions that change the previous SQL."
audience: Everyone
keywords: [ask, ask your data, question, plain english, nl2sql, text to sql, chart, follow-up, conversation, "the SQL the model chose", labels-only, aggregates, truncated, Ask page, PRV-1088, PRV-7002]
guide: "ai#4-ask-your-data"
related: [ai-features, views-and-keys, ai-explain, row-filters-and-masks]
---

**Ask your data** answers a question about your views. The model is told the views you may read — names,
keys, columns, never a value — and writes **one snapshot `SELECT` over one view**; the engine runs it
through the same read path a point read and the PostgreSQL gateway use, **as you**: your grants, your row
filter, your masks. Two people asking the same question get the answers they are each entitled to.

## Where it is

- **Ask** (Catalog → Ask your data, `/ask`): a question box and a conversation. Each answer can be followed
  up — "and only EU?" — and the model is sent your last three questions and the SQL chosen for each, so it
  changes the previous query rather than starting again. **New conversation** clears it.
- **Views**: the same box under the toolbar, over every view you may read.
- **A view's page**: the box scoped to that view — the question is sent as being about that view.

## What an answer shows

- **The SQL the model chose**, in a disclosure, exactly as it ran — and **Open in Workbench**, which opens
  it as a copy in the editor.
- **The rows** the engine gave you, as a table. At most `pravaha.ai.features.ask-rows` (200); a note says
  when there were more.
- **A chart** the console draws when the result's shape suits one — Pravaha's choice, never the model's:
  one value is a figure; a time column with a measure a line, split into series by a text column when
  there is one; one category and one measure a bar while there are at most 30 rows; anything else stays a
  table. The colours are the theme's.
- **A summary** in words, when the node allows the model to see your figures (`aggregates`), and a badge
  saying which mode answered: **labels only** (the model saw names and types) or **aggregates**.

<!-- sql: read -->
```sql
SELECT user_id, SUM(spend) AS spend
FROM hourly_spend
GROUP BY user_id
```

That is the shape of what comes back for "total spend per user": one view, read, grouped — a read may
group because a view is bounded.

## When it cannot answer

- `PRV-1088` — the model's answer could not be used: the engine refused its SQL, and again after one
  repair turn; or it said the question cannot be answered from the views you may read. Nothing ran, and
  the page says what the model said.
- `PRV-7002` — the SQL names a view you may not read. The SQL is shown: a model swayed by a description
  is still refused by your grants.
- `PRV-1084`, `PRV-1085`, `PRV-1080` — the policy, a limit, the layer off ([who may](/help/topics/ai-features#who-may)).

One view per question: a join across views is not something it will write. Ask about one view, or
register a query that joins them and ask about that.
